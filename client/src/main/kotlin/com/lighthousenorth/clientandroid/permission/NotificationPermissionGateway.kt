package com.lighthousenorth.clientandroid.permission

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.lighthousenorth.clientandroid.errors.LighthouseNorthNativeException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Gateway de permissão de notificação — 100% plataforma (zero androidx):
 *
 * - **API < 33**: notificações vêm habilitadas por padrão (sem runtime
 *   permission) ⇒ concedida;
 * - **API 33+**: `POST_NOTIFICATIONS` via dialog do OS.
 *
 * Para mostrar o dialog sem exigir que a Activity do host estenda nada, o
 * gateway adiciona um headless **android.app.Fragment** à Activity corrente
 * (API 23+) e o remove após o resultado. O `android.app.Fragment` está
 * DEPRECATED desde a 28 mas é plenamente funcional — é o preço deliberado
 * por NÃO puxar androidx-fragment (higiene de dependência do SDK; o caminho
 * androidx continua disponível ao host via implementação própria da port
 * [PermissionGateway]).
 *
 * A Activity corrente é rastreada via [Application.ActivityLifecycleCallbacks]
 * (o facade registra) — ou passada diretamente ao [request] pelo caller.
 */
class NotificationPermissionGateway(
    private val appContext: Context,
    private val activityProvider: () -> Activity?,
) : PermissionGateway {

    private val mainHandler = Handler(Looper.getMainLooper())

    /** Última leitura — base da detecção de revogação no resume (contrato §3.4). */
    @Volatile
    var lastKnownGranted: Boolean? = null
        private set

    override fun isGranted(): Boolean {
        if (Build.VERSION.SDK_INT < 33) return true
        val granted = appContext.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) lastKnownGranted = true
        return granted
    }

    override fun request(): Boolean {
        // Android < 33: notificações habilitadas por padrão — concedida.
        if (Build.VERSION.SDK_INT < 33) {
            lastKnownGranted = true
            return true
        }
        if (isGranted()) {
            lastKnownGranted = true
            return true
        }
        val activity = activityProvider()
            ?: throw LighthouseNorthNativeException("no_activity", "sem Activity anexada para pedir a permissão")
        if (activity.isFinishing || activity.isDestroyed) {
            throw LighthouseNorthNativeException("no_activity", "Activity finalizando — sem onde mostrar o dialog")
        }

        val latch = CountDownLatch(1)
        val outcome = booleanArrayOf(false)
        mainHandler.post {
            val fragment = PermissionFragment { granted ->
                outcome[0] = granted
                lastKnownGranted = granted
                latch.countDown()
            }
            activity.fragmentManager.beginTransaction()
                .add(fragment, PermissionFragment.TAG)
                .commitAllowingStateLoss()
            // O próprio fragment pede a permissão no onAttach (commit é async).
        }
        // Bloqueia a thread do SDK (nunca a main) até a resposta do usuário.
        // Timeout = fail-closed (trata como negada) — dialog abandonado não
        // deve deixar o fluxo pendurado para sempre.
        return try {
            latch.await(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS) && outcome[0]
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    /**
     * Headless fragment que pede `POST_NOTIFICATIONS` e recebe o resultado
     * (API 23+; só efetivamente usado em 33+).
     */
    @SuppressLint("DeprecatedApi") // android.app.Fragment — ver kdoc da classe
    class PermissionFragment(
        private val onResult: (Boolean) -> Unit,
    ) : android.app.Fragment() {

        private var delivered = false

        override fun onAttach(context: Context) {
            super.onAttach(context)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_CODE)
        }

        override fun onRequestPermissionsResult(
            requestCode: Int,
            permissions: Array<out String>,
            grantResults: IntArray,
        ) {
            if (requestCode != REQUEST_CODE) return
            if (delivered) return
            delivered = true
            val granted = grantResults.isNotEmpty() &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED
            if (isAdded) {
                fragmentManager?.beginTransaction()?.remove(this)?.commitAllowingStateLoss()
            }
            onResult(granted)
        }

        override fun onDetach() {
            // Activity destruída com o dialog aberto: entrega `false`
            // (fail-closed) para não deixar a promise pendurada.
            if (!delivered) {
                delivered = true
                onResult(false)
            }
            super.onDetach()
        }

        companion object {
            const val TAG = "lighthouse_north_permission_fragment"
            private const val REQUEST_CODE = 70423
        }
    }

    companion object {
        private const val REQUEST_TIMEOUT_SECONDS = 60L
    }
}

/**
 * Rastreia a Activity em resume (para o gateway ter alvo de dialog) e detecta
 * a revogação da permissão quando o usuário volta das Configurações
 * (contrato §3.4: verificação no ON_RESUME — base mínima da 3.2/3.3).
 */
class ActivityTracker(
    private val onPermissionRevoked: () -> Unit,
    private val gateway: NotificationPermissionGateway,
) : Application.ActivityLifecycleCallbacks {

    private var resumedActivity: Activity? = null

    fun currentActivity(): Activity? = resumedActivity

    override fun onActivityResumed(activity: Activity) {
        resumedActivity = activity
        checkPermissionRevokedOnResume()
    }

    override fun onActivityPaused(activity: Activity) {
        if (resumedActivity === activity) resumedActivity = activity
    }

    /** Revogação nas Configurações, detectada ao voltar ao app. */
    private fun checkPermissionRevokedOnResume() {
        val last = gateway.lastKnownGranted ?: return
        val now = gateway.isGranted()
        if (last && !now) {
            onPermissionRevoked()
        }
    }

    // Callbacks não utilizados — implementação obrigatória da interface.

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

    override fun onActivityStarted(activity: Activity) = Unit

    override fun onActivityStopped(activity: Activity) {
        if (resumedActivity === activity) resumedActivity = null
    }

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    override fun onActivityDestroyed(activity: Activity) {
        if (resumedActivity === activity) resumedActivity = null
    }
}
