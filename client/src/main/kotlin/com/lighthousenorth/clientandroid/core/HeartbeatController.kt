package com.lighthousenorth.clientandroid.core

import com.lighthousenorth.clientandroid.http.Transport
import com.lighthousenorth.clientandroid.model.DeviceState
import com.lighthousenorth.clientandroid.storage.StateStore
import org.json.JSONObject
import kotlin.math.roundToLong

/**
 * Heartbeat do device: chama `POST /v1/devices/refresh` a cada intervalo
 * com JITTER de ±10% — sem o jitter, todos os devices reinstalariam juntos
 * (thundering herd) e o rate de 600/min por app key seria desnecessariamente
 * pressionado nos picos.
 *
 * O intervalo ativo é persistido no estado — o `init` retoma o timer após
 * restart do app (paridade com o HeartbeatController do SDK Flutter).
 */
class HeartbeatController(
    private val api: Transport,
    private val store: StateStore,
    private val scheduler: Scheduler,
    private val random: LighthouseNorthRandom,
    private val logger: Logger,
) {
    var running: Boolean = false
        private set
    var intervalMs: Long = DEFAULT_INTERVAL_MS
        private set

    private var pending: Cancellable? = null

    /** Liga o heartbeat (persistindo a configuração). Retorna `false` quando
     *  não há token registrado — nada a renovar. */
    fun start(intervalMs: Long = DEFAULT_INTERVAL_MS): Boolean {
        cancel(persist = false)
        this.intervalMs = intervalMs
        val state = store.read()
        if (state?.token == null) {
            logger.log(LogLevel.WARNING, "heartbeat.start_skipped", mapOf("reason" to "sem token registrado"))
            return false
        }
        store.write(
            state.copy(heartbeatActive = true, heartbeatIntervalMs = intervalMs),
        )
        running = true
        scheduleNext()
        logger.log(LogLevel.INFO, "heartbeat.started", mapOf("intervalMs" to intervalMs))
        return true
    }

    /**
     * Desliga o heartbeat. Com [persist] (padrão), grava `heartbeatActive:
     * false` no estado — usado pelo `stopHeartbeat()` público. Sem [persist],
     * apenas cancela o timer (usado nos fluxos que vão reescrever/limpar o
     * estado em seguida).
     */
    fun stop(persist: Boolean = true) {
        cancel(persist = persist)
    }

    private fun cancel(persist: Boolean) {
        running = false
        pending?.cancel()
        pending = null
        if (persist) {
            val state = store.read()
            if (state != null && state.heartbeatActive) {
                store.write(state.copy(heartbeatActive = false, heartbeatIntervalMs = null))
            }
        }
    }

    private fun scheduleNext() {
        if (!running) return
        val jitterFactor = 0.9 + random.nextDouble() * 0.2 // ±10%
        val delay = (intervalMs * jitterFactor).roundToLong()
        pending?.cancel()
        pending = scheduler.schedule(delay) { tick() }
    }

    private fun tick() {
        if (!running) return
        val state = store.read()
        if (state == null || state.token == null) {
            logger.log(LogLevel.WARNING, "heartbeat.stopped", mapOf("reason" to "token sumiu do estado"))
            running = false
            if (state != null) {
                store.write(state.copy(heartbeatActive = false, heartbeatIntervalMs = null))
            }
            return
        }
        try {
            api.postJson("/v1/devices/refresh", JSONObject().put("token", state.token), idempotent = true)
            logger.log(LogLevel.DEBUG, "heartbeat.refresh_ok")
        } catch (e: Exception) {
            // Falha de refresh NÃO derruba o heartbeat (rede instável é o caso
            // normal de vida de device); o próximo tick tenta de novo.
            logger.log(LogLevel.ERROR, "heartbeat.refresh_failed", mapOf("error" to e.toString()))
        }
        scheduleNext()
    }

    companion object {
        const val DEFAULT_INTERVAL_MS: Long = 24L * 60L * 60L * 1000L // 24h

        /** Jitter ±10% exposto para teste de limites (paridade Dart). */
        fun jitter(intervalMs: Long, random: LighthouseNorthRandom): Long =
            (intervalMs * (0.9 + 0.2 * random.nextDouble())).roundToLong()
    }
}
