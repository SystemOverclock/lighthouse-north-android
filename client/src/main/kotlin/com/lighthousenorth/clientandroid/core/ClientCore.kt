package com.lighthousenorth.clientandroid.core

import com.lighthousenorth.clientandroid.errors.LighthouseNorthClientClosedException
import com.lighthousenorth.clientandroid.errors.LighthouseNorthException
import com.lighthousenorth.clientandroid.errors.LighthouseNorthInvalidInputException
import com.lighthousenorth.clientandroid.errors.LighthouseNorthNativeException
import com.lighthousenorth.clientandroid.errors.PushPermissionDeniedException
import com.lighthousenorth.clientandroid.errors.PushTokenUnavailableException
import com.lighthousenorth.clientandroid.http.Transport
import com.lighthousenorth.clientandroid.model.ConsentEvidence
import com.lighthousenorth.clientandroid.model.DevicePlatform
import com.lighthousenorth.clientandroid.model.DeviceState
import com.lighthousenorth.clientandroid.model.OptOutResult
import com.lighthousenorth.clientandroid.model.OptOutStep
import com.lighthousenorth.clientandroid.model.OptOutStepFailure
import com.lighthousenorth.clientandroid.model.PermissionRevocation
import com.lighthousenorth.clientandroid.model.RegistrationResult
import com.lighthousenorth.clientandroid.model.TokenRotationResult
import com.lighthousenorth.clientandroid.permission.PermissionGateway
import com.lighthousenorth.clientandroid.push.TokenProvider
import com.lighthousenorth.clientandroid.storage.StateStore
import org.json.JSONObject

/**
 * Núcleo do SDK — 100% Kotlin puro (zero API Android), com TODAS as ports
 * injetadas (transporte/estado/token/permissão/tempo). Os testes JVM exercem
 * esta classe diretamente; o facade Android ([com.lighthousenorth.clientandroid.LighthouseNorthAndroidClient])
 * apenas conecta as implementações de produção.
 *
 * Todos os métodos são BLOQUEANTES e devem rodar na thread do SDK (o facade
 * serializa em um executor single-thread — mesmo isolamento lógico do event
 * loop do Dart).
 *
 * CONTRATOS DE PRODUTO (paridade exata com o SDK Flutter — divergência = bug):
 * - **Minimização**: o SDK coleta/envia EXATAMENTE `platform`, `token`,
 *   `contactRef` e `consent`. Nada de IP custom, UA extra, identificadores
 *   de propaganda ou dados do app hospedeiro.
 * - **Opt-out fácil e gratuito**: [optOut] nunca lança exceção de rede.
 */
class ClientCore(
    val api: Transport,
    val store: StateStore,
    val tokenProvider: TokenProvider,
    val permissions: PermissionGateway,
    scheduler: Scheduler,
    random: LighthouseNorthRandom,
    val clock: Clock,
    val logger: Logger,
) {
    val heartbeat = HeartbeatController(api, store, scheduler, random, logger)
    private val scheduler: Scheduler = scheduler

    @Volatile
    private var closed = false

    // -----------------------------------------------------------------
    // registerForPush
    // -----------------------------------------------------------------

    /**
     * Registra o device para push — o fluxo COMPLETO:
     *
     * 1. pede permissão de notificação ao usuário (OS);
     * 2. obtém o token push do app hospedeiro;
     * 3. `POST /v1/devices` com `consent {granted: true, capturedAt, policyVersion}`
     *    — evidência do opt-in que a plataforma grava no contato;
     * 4. persiste localmente o mínimo (token/contactRef) p/ heartbeat e opt-out.
     *
     * Permissão NEGADA → [PushPermissionDeniedException] e o device NÃO é
     * registrado (não é crash — trate no app e siga).
     */
    fun registerForPush(contactRef: String?, policyVersion: String?): RegistrationResult {
        ensureUsable()
        validateContactRef(contactRef)

        val platform = tokenProvider.platform()

        val granted = try {
            permissions.request()
        } catch (e: LighthouseNorthException) {
            throw e
        } catch (e: Exception) {
            throw LighthouseNorthNativeException("request_permission_failed", "falha ao pedir permissão: $e")
        }
        if (!granted) {
            logger.log(LogLevel.INFO, "register.permission_denied", mapOf("platform" to platform.wire))
            throw PushPermissionDeniedException()
        }

        val token = obtainToken()

        if (token.length < 16 || token.length > 4096) {
            throw LighthouseNorthInvalidInputException(
                "token",
                "deve ter entre 16 e 4096 caracteres (recebido: ${token.length})",
            )
        }

        val consent = ConsentEvidence(
            granted = true,
            capturedAtMillis = clock.nowMillis(),
            policyVersion = policyVersion,
        )
        val body = JSONObject()
            .put("platform", platform.wire)
            .put("token", token)
        val previous = store.read()
        val effectiveContactRef = contactRef ?: previous?.contactRef
        effectiveContactRef?.let { body.put("contactRef", it) }
        body.put("consent", consent.toJson())
        val status = api.postJson("/v1/devices", body, idempotent = true)

        val state = DeviceState(
            platform = platform,
            token = token,
            contactRef = effectiveContactRef,
            policyVersion = policyVersion ?: previous?.policyVersion,
            registeredAtMillis = clock.nowMillis(),
            heartbeatActive = previous?.heartbeatActive ?: false,
            heartbeatIntervalMs = previous?.heartbeatIntervalMs,
        )
        store.write(state)

        logger.log(
            LogLevel.INFO,
            "register.success",
            mapOf(
                "platform" to platform.wire,
                "token" to redactSecret(token),
                "statusCode" to status,
            ),
        )
        return RegistrationResult(
            platform = platform,
            maskedToken = redactSecret(token),
            contactRef = effectiveContactRef,
            consent = consent,
            updatedExisting = status == 200,
        )
    }

    // -----------------------------------------------------------------
    // optOut — REQUISITO LEGAL BLOQUEANTE
    // -----------------------------------------------------------------

    /**
     * OPT-OUT COMPLETO — best-effort, em ordem: (1) revoga a base de
     * marketing (`consent granted=false` com o `contactRef` persistido);
     * (2) remove o device (`POST /v1/devices/unregister`). Cada passo falha
     * independentemente — nunca lança exceção de rede; o resultado reporta o
     * que aconteceu.
     *
     * Estado local: limpo sempre que um passo efetivo teve sucesso. Em falha
     * TOTAL o SDK preserva o mínimo (token/contactRef) para poder RE-TENTAR —
     * descartar o token descartaria a capacidade de concluir o opt-out.
     */
    fun optOut(): OptOutResult {
        ensureUsable()
        val state = store.read()
        val token = state?.token
        val contactRef = state?.contactRef
        val platform = state?.platform
        val policyVersion = state?.policyVersion

        if (token == null && contactRef == null) {
            heartbeat.stop(persist = false)
            store.clear()
            logger.log(LogLevel.INFO, "opt_out.noop", mapOf("reason" to "sem registro local"))
            return OptOutResult(
                hadRegistration = false,
                consentRevoked = null,
                unregistered = true,
                stateCleared = true,
            )
        }

        val failures = mutableListOf<OptOutStepFailure>()
        var consentRevoked: Boolean? = null

        // PASSO 1 — revogar a base marketing ANTES do unregister: revogar
        // depois recriaria o device (o POST de registro é upsert por token).
        if (contactRef != null && token != null && platform != null) {
            try {
                val consent = ConsentEvidence(
                    granted = false,
                    capturedAtMillis = clock.nowMillis(),
                    policyVersion = policyVersion,
                )
                api.postJson(
                    "/v1/devices",
                    JSONObject()
                        .put("platform", platform.wire)
                        .put("token", token)
                        .put("contactRef", contactRef)
                        .put("consent", consent.toJson()),
                    idempotent = true,
                )
                consentRevoked = true
            } catch (e: Exception) {
                consentRevoked = false
                failures += OptOutStepFailure(OptOutStep.CONSENT_REVOKE, e.toString())
                logger.log(
                    LogLevel.ERROR,
                    "opt_out.consent_revoke_failed",
                    mapOf("error" to e.toString(), "token" to redactSecret(token)),
                )
            }
        } else if (contactRef != null) {
            // Sem token (ex.: permissão do OS já revogada e device já removido).
            // Não há POST possível — a base permanece mas SEM device não há push
            // para este aparelho. Registrado para rastreio legal.
            logger.log(
                LogLevel.WARNING,
                "opt_out.consent_revoke_skipped",
                mapOf("reason" to "sem token local — device já removido; base permanece sem alvo"),
            )
        }

        // PASSO 2 — remover o device (hard delete na plataforma).
        var unregistered = false
        if (token != null) {
            try {
                api.postJson(
                    "/v1/devices/unregister",
                    JSONObject().put("token", token),
                    idempotent = true,
                )
                unregistered = true
            } catch (e: Exception) {
                failures += OptOutStepFailure(OptOutStep.UNREGISTER, e.toString())
                logger.log(
                    LogLevel.ERROR,
                    "opt_out.unregister_failed",
                    mapOf("error" to e.toString(), "token" to redactSecret(token)),
                )
            }
        } else {
            unregistered = true // nada a remover
        }

        val somethingSucceeded = consentRevoked == true || unregistered
        var stateCleared = false
        heartbeat.stop(persist = false)
        if (somethingSucceeded) {
            store.clear()
            stateCleared = true
        }

        logger.log(
            LogLevel.INFO,
            "opt_out.completed",
            mapOf(
                "consentRevoked" to consentRevoked,
                "unregistered" to unregistered,
                "stateCleared" to stateCleared,
                "failures" to failures.size,
            ),
        )
        return OptOutResult(
            hadRegistration = true,
            consentRevoked = consentRevoked,
            unregistered = unregistered,
            stateCleared = stateCleared,
            failures = failures.toList(),
        )
    }

    // -----------------------------------------------------------------
    // permission watcher (revogação no OS)
    // -----------------------------------------------------------------

    /**
     * Revogação da permissão no OS: unregister automático (cobrança honesta)
     * + evento para o app hospedeiro. Nunca lança para o caller (listener).
     */
    fun onPermissionRevoked(): PermissionRevocation {
        if (closed) return PermissionRevocation(unregistered = false, error = "client fechado")
        logger.log(LogLevel.WARNING, "permission.revoked")
        var unregistered = false
        var failure: Exception? = null
        val state = store.read()
        val token = state?.token
        if (token != null) {
            try {
                api.postJson(
                    "/v1/devices/unregister",
                    JSONObject().put("token", token),
                    idempotent = true,
                )
                unregistered = true
            } catch (e: Exception) {
                failure = e
            }
        } else {
            unregistered = true
        }
        if (unregistered && state != null) {
            // Token sai do estado; contactRef/policyVersion ficam para um
            // eventual re-consentimento futuro. Heartbeat para (sem token,
            // nada a renovar).
            heartbeat.stop(persist = false)
            store.write(
                state.copy(token = null, heartbeatActive = false, heartbeatIntervalMs = null),
            )
        }
        if (failure != null) {
            logger.log(
                LogLevel.ERROR,
                "permission.revoked_unregister_failed",
                mapOf("error" to failure.toString()),
            )
        }
        return PermissionRevocation(unregistered = unregistered, error = failure?.toString())
    }

    // -----------------------------------------------------------------
    // rotação de token FCM (onNewToken) — nova na 3.3
    // -----------------------------------------------------------------

    /**
     * Rotação do token FCM detectada (broadcast nativo via service opcional
     * do host, ou chamada direta). Fluxo:
     *
     * 1. registra o NOVO token (`POST /v1/devices` — upsert) SEM consent:
     *    o estado legal do contato NÃO muda com a rotação. Reafirmar
     *    `granted=true` aqui RESSUSCITARIA consentimento revogado no
     *    intervalo (optOut parcial preserva contactRef/token localmente
     *    para permitir re-tentativa; retirada pelo painel idem) — a base
     *    vigente no servidor é a autoridade (@gate QA 3.3, correção em
     *    paridade nas 3 pontas + NATIVE_CONTRACT §3);
     * 2. unregister best-effort do token ANTIGO (sem isto, o device antigo
     *    permaneceria ativo até o expurgo de 90d e o usuário levaria push
     *    duplicado na janela);
     * 3. atualiza o estado local preservando contactRef/policyVersion e a
     *    configuração do heartbeat (o próximo tick já usa o token novo).
     */
    fun onNewToken(newToken: String): TokenRotationResult {
        ensureUsable()
        if (newToken.length < 16 || newToken.length > 4096) {
            throw LighthouseNorthInvalidInputException(
                "token",
                "rotação: novo token fora de 16..4096 (recebido: ${newToken.length})",
            )
        }
        val platform = tokenProvider.platform()
        val previous = store.read()
        val oldToken = previous?.token

        if (oldToken == newToken) {
            return TokenRotationResult(
                changed = false,
                registered = false,
                oldRemoved = null,
                updatedExisting = true,
            )
        }

        // SEM consent (ver docstring): rotação não é ato de consentimento —
        // o contactRef segue para manter o device vinculado ao contato.
        val body = JSONObject()
            .put("platform", platform.wire)
            .put("token", newToken)
        previous?.contactRef?.let { body.put("contactRef", it) }
        val status = api.postJson("/v1/devices", body, idempotent = true)

        var oldRemoved: Boolean? = null
        if (oldToken != null) {
            oldRemoved = try {
                api.postJson(
                    "/v1/devices/unregister",
                    JSONObject().put("token", oldToken),
                    idempotent = true,
                )
                true
            } catch (e: Exception) {
                logger.log(
                    LogLevel.WARNING,
                    "rotation.old_unregister_failed",
                    mapOf("error" to e.toString(), "token" to redactSecret(oldToken)),
                )
                false
            }
        }

        store.write(
            (previous ?: DeviceState()).copy(
                platform = platform,
                token = newToken,
                registeredAtMillis = clock.nowMillis(),
            ),
        )
        logger.log(
            LogLevel.INFO,
            "rotation.completed",
            mapOf(
                "token" to redactSecret(newToken),
                "oldRemoved" to oldRemoved,
                "statusCode" to status,
            ),
        )
        return TokenRotationResult(
            changed = true,
            registered = true,
            oldRemoved = oldRemoved,
            updatedExisting = status == 200,
        )
    }

    // -----------------------------------------------------------------
    // ciclo de vida
    // -----------------------------------------------------------------

    /**
     * Retoma o heartbeat pós-restart: timer re-agendado + refresh imediato
     * fire-and-forget (mantém `last_seen` honesto a cada abertura do app —
     * o expurgo da plataforma é 90d sem last_seen).
     */
    fun resumeAfterInit() {
        val state = store.read()
        if (state == null || !state.heartbeatActive || state.token == null) {
            return
        }
        val interval = state.heartbeatIntervalMs ?: HeartbeatController.DEFAULT_INTERVAL_MS
        heartbeat.start(interval)
        logger.log(LogLevel.INFO, "heartbeat.resumed", mapOf("intervalMs" to interval))

        val token = state.token
        scheduler.executeAsync {
            try {
                api.postJson(
                    "/v1/devices/refresh",
                    JSONObject().put("token", token),
                    idempotent = true,
                )
                logger.log(LogLevel.DEBUG, "heartbeat.resume_refresh_ok")
            } catch (e: Exception) {
                logger.log(
                    LogLevel.ERROR,
                    "heartbeat.resume_refresh_failed",
                    mapOf("error" to e.toString()),
                )
            }
        }
    }

    fun close() {
        closed = true
        heartbeat.stop(persist = false)
    }

    // -----------------------------------------------------------------
    // internals
    // -----------------------------------------------------------------

    private fun obtainToken(): String {
        return try {
            tokenProvider.getToken()
        } catch (e: LighthouseNorthException) {
            throw e
        } catch (e: Exception) {
            throw PushTokenUnavailableException("falha ao obter token push: $e")
        }
    }

    private fun validateContactRef(contactRef: String?) {
        if (contactRef != null && (contactRef.isEmpty() || contactRef.length > 200)) {
            throw LighthouseNorthInvalidInputException("contactRef", "deve ter entre 1 e 200 caracteres")
        }
    }

    private fun ensureUsable() {
        if (closed) throw LighthouseNorthClientClosedException()
    }
}
