package com.lighthousenorth.clientandroid

import android.app.Activity
import android.app.Application
import android.content.Context
import com.lighthousenorth.clientandroid.core.ClientCore
import com.lighthousenorth.clientandroid.core.Clock
import com.lighthousenorth.clientandroid.core.ExecutorScheduler
import com.lighthousenorth.clientandroid.core.LogLevel
import com.lighthousenorth.clientandroid.core.Logger
import com.lighthousenorth.clientandroid.core.LighthouseNorthRandom
import com.lighthousenorth.clientandroid.core.NoopLogger
import com.lighthousenorth.clientandroid.core.RealLighthouseNorthRandom
import com.lighthousenorth.clientandroid.core.WallClock
import com.lighthousenorth.clientandroid.errors.InvalidAppKeyException
import com.lighthousenorth.clientandroid.errors.LighthouseNorthClientClosedException
import com.lighthousenorth.clientandroid.errors.LighthouseNorthNotInitializedException
import com.lighthousenorth.clientandroid.http.HttpEngine
import com.lighthousenorth.clientandroid.http.HttpUrlConnectionEngine
import com.lighthousenorth.clientandroid.http.Transport
import com.lighthousenorth.clientandroid.model.DeviceState
import com.lighthousenorth.clientandroid.model.OptOutResult
import com.lighthousenorth.clientandroid.model.PermissionRevocation
import com.lighthousenorth.clientandroid.model.RegistrationResult
import com.lighthousenorth.clientandroid.model.TokenRotationResult
import com.lighthousenorth.clientandroid.permission.ActivityTracker
import com.lighthousenorth.clientandroid.permission.NotificationPermissionGateway
import com.lighthousenorth.clientandroid.push.FcmTokenProvider
import com.lighthousenorth.clientandroid.push.TokenProvider
import com.lighthousenorth.clientandroid.storage.KeystoreStateStore
import com.lighthousenorth.clientandroid.storage.StateStore

/**
 * SDK Android nativo (codinome — nome de publicação Maven pendente do dono)
 * para integrar o app do cliente à plataforma LighthouseNorth de disparo push.
 * A plataforma cuida do disparo e da segmentação; o SDK coleta o MÍNIMO
 * (platform/token/contactRef/consent) e oferece opt-out fácil e gratuito.
 *
 * Ciclo de vida típico:
 * ```kotlin
 * val client = LighthouseNorthAndroidClient.init(
 *     context, appKey,            // do painel do tenant — NUNCA hardcoded
 *     apiBaseUrl = "https://api.seu-ambiente.lighthouse",
 * )
 * client.registerForPush(activity, contactRef = "cliente-42", policyVersion = "2026-09") {
 *     it.onSuccess { client.startHeartbeat() }
 * }
 * // o usuário pede para sair de push em qualquer tela:
 * client.optOut { result -> /* nunca falha por rede — ver OptOutResult */ }
 * ```
 *
 * Todas as chamadas são serializadas em um executor single-thread do SDK —
 * a main thread NUNCA é bloqueada; callbacks voltam no MESMO executor
 * (poste para a main thread se a UI precisar).
 */
class LighthouseNorthAndroidClient private constructor(
    private val appContext: Context,
    internal val core: ClientCore,
    private val gateway: NotificationPermissionGateway,
    internal val tracker: ActivityTracker,
    private val scheduler: ExecutorScheduler,
    private val ownScheduler: Boolean,
) {

    /** Listener da revogação da permissão no OS (contrato §3.4). */
    @Volatile
    var onPermissionRevoked: ((PermissionRevocation) -> Unit)? = null

    /** Listener da rotação do token FCM (contrato 3.3 — onNewToken). */
    @Volatile
    var onTokenRotated: ((TokenRotationResult) -> Unit)? = null

    /** Activity pinada pelo integrador p/ o dialog de permissão. */
    @Volatile
    private var pinnedActivity: Activity? = null

    @Volatile
    private var closed = false

    /** Última visão síncrona do estado local (para UI). */
    val currentState: DeviceState?
        get() = core.store.read()

    /** Se há um device registrado localmente (token presente). */
    val isRegistered: Boolean
        get() = currentState?.token != null

    val isHeartbeatRunning: Boolean
        get() = core.heartbeat.running

    // -----------------------------------------------------------------
    // API pública
    // -----------------------------------------------------------------

    /**
     * Registra o device para push — fluxo COMPLETO (permissão OS → token do
     * host → POST /v1/devices com consent → persiste estado mínimo).
     *
     * [activity] é opcional: o SDK usa a Activity em resume rastreada
     * automaticamente. Permissão negada ⇒ callback com
     * [com.lighthousenorth.clientandroid.errors.PushPermissionDeniedException] e o
     * device NÃO é registrado (não é crash — trate e siga).
     */
    fun registerForPush(
        activity: Activity? = null,
        contactRef: String? = null,
        policyVersion: String? = null,
        callback: ((Result<RegistrationResult>) -> Unit)? = null,
    ) {
        if (activity != null) pinnedActivity = activity
        runAsync(callback) { core.registerForPush(contactRef, policyVersion) }
    }

    /**
     * OPT-OUT COMPLETO — requisito legal BLOQUEANTE (fácil e gratuito).
     * Best-effort: revoga consent ANTES do unregister; nunca falha o callback
     * por rede — o [OptOutResult] reporta cada passo.
     */
    fun optOut(callback: ((Result<OptOutResult>) -> Unit)? = null) {
        runAsync(callback) { core.optOut() }
    }

    /** Liga o heartbeat (default 24h com jitter ±10%; sobrevive a restart). */
    fun startHeartbeat(intervalMs: Long = HeartbeatDefault): Boolean {
        ensureOpen()
        return core.heartbeat.start(intervalMs)
    }

    /** Desliga o heartbeat (o registro do device permanece). */
    fun stopHeartbeat() {
        ensureOpen()
        core.heartbeat.stop()
    }

    /**
     * Notifica o SDK de um token FCM NOVO — chamado automaticamente quando o
     * host declara [LighthouseNorthFirebaseMessagingService] no manifest, ou
     * manualmente no `onNewToken` de um service próprio.
     */
    fun onNewToken(
        newToken: String,
        callback: ((Result<TokenRotationResult>) -> Unit)? = null,
    ) {
        runAsync(callback) { core.onNewToken(newToken) }
    }

    /** Variante bloqueante de [registerForPush] — roda na thread do CALLER
     *  (para integradores com coroutines próprias). */
    fun registerForPushBlocking(
        contactRef: String? = null,
        policyVersion: String? = null,
    ): RegistrationResult {
        ensureOpen()
        return core.registerForPush(contactRef, policyVersion)
    }

    /** Variante bloqueante de [optOut] — roda na thread do CALLER. */
    fun optOutBlocking(): OptOutResult {
        ensureOpen()
        return core.optOut()
    }

    /** Libera o SDK (executor, watcher). O app normalmente não chama isto. */
    fun close() {
        closed = true
        core.close()
        (appContext as? Application)?.unregisterActivityLifecycleCallbacks(tracker)
        if (ownScheduler) scheduler.shutdown()
        if (instance === this) instance = null
    }

    // -----------------------------------------------------------------
    // internals
    // -----------------------------------------------------------------

    private fun ensureOpen() {
        if (closed) throw LighthouseNorthClientClosedException()
    }

    private fun <T> runAsync(callback: ((Result<T>) -> Unit)?, block: () -> T) {
        ensureOpen()
        scheduler.execute {
            val outcome = runCatching(block)
            callback?.invoke(outcome)
        }
    }

    /** Revogação detectada pelo tracker (resume) — unregister + evento. */
    internal fun handlePermissionRevokedFromWatcher() {
        scheduler.execute {
            if (closed) return@execute
            val event = core.onPermissionRevoked()
            onPermissionRevoked?.invoke(event)
        }
    }

    companion object {
        /**
         * Default provisório — o domínio oficial acompanha o nome de
         * publicação do produto (pendência do dono). Aponte `apiBaseUrl`
         * explicitamente em dev/staging (ex.: `http://10.0.2.2:3000` para a
         * API local a partir do emulador — loopback do host, permitido).
         */
        const val DEFAULT_API_BASE_URL = "https://api.lighthouse-north.example"

        /** Versão do SDK (integra o User-Agent de toda requisição). */
        const val SDK_VERSION: String = Transport.SDK_VERSION

        const val HeartbeatDefault: Long = 24L * 60 * 60 * 1000

        @Volatile
        private var instance: LighthouseNorthAndroidClient? = null

        /** Instância ativa (ou [LighthouseNorthNotInitializedException]). */
        fun get(): LighthouseNorthAndroidClient = instance ?: throw LighthouseNorthNotInitializedException()

        fun isInitialized(): Boolean = instance != null

        /**
         * Inicializa (e retorna) o cliente singleton. Se um heartbeat estava
         * ativo, o timer é RETOMADO aqui (sobrevive a restart) com um refresh
         * imediato fire-and-forget.
         *
         * @param appKey vem do PAINEL DO TENANT (Apps > Chaves) — é config do
         * app cliente, NUNCA hardcoded no fonte (ver README).
         */
        @JvmStatic
        @JvmOverloads
        fun init(
            context: Context,
            appKey: String,
            apiBaseUrl: String = DEFAULT_API_BASE_URL,
            config: Config = Config(),
        ): LighthouseNorthAndroidClient {
            validateAppKey(appKey, config.logger)

            instance?.let { previous -> previous.close() }

            val appContext = context.applicationContext
            val scheduler = ExecutorScheduler()
            val holder = arrayOfNulls<LighthouseNorthAndroidClient>(1)
            val gateway = NotificationPermissionGateway(appContext) {
                holder[0]?.pinnedActivity ?: holder[0]?.tracker?.currentActivity()
            }
            val tracker = ActivityTracker(
                onPermissionRevoked = { holder[0]?.handlePermissionRevokedFromWatcher() },
                gateway = gateway,
            )
            val random = config.random ?: RealLighthouseNorthRandom()
            val client = LighthouseNorthAndroidClient(
                appContext = appContext,
                core = ClientCore(
                    api = Transport(
                        engine = config.engine ?: HttpUrlConnectionEngine(),
                        baseUrl = apiBaseUrl,
                        appKey = appKey,
                        scheduler = scheduler,
                        random = random,
                        logger = config.logger,
                    ),
                    store = config.stateStore ?: KeystoreStateStore(appContext, config.logger),
                    tokenProvider = config.tokenProvider ?: FcmTokenProvider(),
                    permissions = gateway,
                    scheduler = scheduler,
                    random = random,
                    clock = config.clock ?: WallClock(),
                    logger = config.logger,
                ),
                gateway = gateway,
                tracker = tracker,
                scheduler = scheduler,
                ownScheduler = true,
            )
            holder[0] = client
            (appContext as? Application)?.registerActivityLifecycleCallbacks(tracker)
            instance = client
            scheduler.executeAsync { client.core.resumeAfterInit() }
            return client
        }

        /** Rotação vinda do [LighthouseNorthFirebaseMessagingService] do host. */
        internal fun dispatchNewTokenFromService(newToken: String) {
            val current = instance ?: return // SDK não inicializado: rotação ignorada
            current.onNewToken(newToken) { outcome ->
                outcome.onSuccess { current.onTokenRotated?.invoke(it) }
                current.core.logger.log(
                    LogLevel.WARNING,
                    "rotation.service_dispatch",
                    outcome.exceptionOrNull()?.let { mapOf("error" to it.toString()) }
                        ?: mapOf("status" to "ok"),
                )
            }
        }

        private fun validateAppKey(appKey: String, logger: Logger) {
            if (appKey.trim().isEmpty()) {
                throw InvalidAppKeyException(
                    "app key vazia — gere uma no painel do tenant (Apps > Chaves)",
                )
            }
            if (appKey.any { it.isWhitespace() }) {
                throw InvalidAppKeyException(
                    "app key contém espaços/quebras — recopie o valor do painel",
                )
            }
            if (appKey.length < 16) {
                throw InvalidAppKeyException(
                    "app key curta demais — material ak_... tem tamanho fixo",
                )
            }
            if (!appKey.startsWith("ak_")) {
                logger.log(
                    LogLevel.WARNING,
                    "init.app_key_unexpected_prefix",
                    mapOf(
                        "hint" to "material de app key começa com ak_ — o servidor " +
                            "responderá 401 se estiver errada",
                    ),
                )
            }
        }
    }

    /** Injeções opcionais (testes/dev: engine fake, provider fake, store fake). */
    data class Config(
        val logger: Logger = NoopLogger,
        val engine: HttpEngine? = null,
        val tokenProvider: TokenProvider? = null,
        val stateStore: StateStore? = null,
        val clock: Clock? = null,
        val random: LighthouseNorthRandom? = null,
    )
}
