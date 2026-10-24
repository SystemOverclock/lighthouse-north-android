package com.lighthousenorth.clientandroid

import com.lighthousenorth.clientandroid.core.FixedLighthouseNorthRandom
import com.lighthousenorth.clientandroid.core.HeartbeatController
import com.lighthousenorth.clientandroid.errors.LighthouseNorthInvalidInputException
import com.lighthousenorth.clientandroid.model.DeviceState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Heartbeat (jitter ±10%, retomada pós-restart, ciclo sobrevive a falha de
 * rede), permission watcher (unregister automático preservando contactRef) e
 * rotação de token FCM (nova na 3.3) — paridade com o SDK Flutter onde
 * aplicável.
 */
class HeartbeatWatcherRotationStateTest {

    // ------------------------------------------------------------------
    // Heartbeat
    // ------------------------------------------------------------------

    @Test
    fun `start sem token registrado nao agenda nada`() {
        val h = Harness()
        assertFalse(h.core.heartbeat.start())
        assertTrue(h.scheduler.scheduled.isEmpty())
    }

    @Test
    fun `start persiste a configuracao e agenda o primeiro tick com jitter`() {
        val h = Harness()
        h.engine.enqueue(201)
        h.core.registerForPush(contactRef = "c1", policyVersion = null)

        assertTrue(h.core.heartbeat.start(intervalMs = 60_000))

        // FixedLighthouseNorthRandom(0.5) → fator exatamente 1.0 (±10% neutro).
        assertEquals(60_000L, h.scheduler.scheduled.single().delayMs)
        assertEquals(true, h.store.state?.heartbeatActive)
        assertEquals(60_000L, h.store.state?.heartbeatIntervalMs)
    }

    @Test
    fun `jitter do heartbeat varia entre 0,9x e 1,1x do intervalo`() {
        assertEquals(90_000L, HeartbeatController.jitter(100_000, FixedLighthouseNorthRandom(0.0)))
        assertEquals(110_000L, HeartbeatController.jitter(100_000, FixedLighthouseNorthRandom(1.0)))
        assertEquals(100_000L, HeartbeatController.jitter(100_000, FixedLighthouseNorthRandom(0.5)))
    }

    @Test
    fun `tick chama refresh com o token e re-agenda o proximo`() {
        val h = Harness()
        h.store.write(DeviceState(token = Harness.TOKEN))
        h.core.heartbeat.start(intervalMs = 60_000)
        h.engine.enqueue(200)

        assertTrue(h.scheduler.runNext())

        assertEquals("v1/devices/refresh", h.engine.calls.single().path)
        assertEquals(Harness.TOKEN, h.engine.calls.single().json.getString("token"))
        // ciclo re-agendado
        assertEquals(2, h.scheduler.scheduled.size)
    }

    @Test
    fun `falha de rede no refresh NAO derruba o ciclo`() {
        val h = Harness()
        h.store.write(DeviceState(token = Harness.TOKEN))
        h.core.heartbeat.start(intervalMs = 60_000)
        repeat(3) { h.engine.enqueueError(java.io.IOException("offline")) }

        h.scheduler.runNext()

        // 3 tentativas do refresh falharam, mas o próximo tick foi agendado.
        assertEquals(3, h.engine.calls.size)
        assertEquals(2, h.scheduler.scheduled.size)
        assertTrue(h.core.heartbeat.running)
    }

    @Test
    fun `token sumiu do estado para o heartbeat e persiste inativo`() {
        val h = Harness()
        h.store.write(DeviceState(token = Harness.TOKEN, heartbeatActive = true))
        h.core.heartbeat.start(intervalMs = 60_000)
        h.store.write(DeviceState(token = null, heartbeatActive = true))

        h.scheduler.runNext()

        assertFalse(h.core.heartbeat.running)
        assertEquals(false, h.store.state?.heartbeatActive)
    }

    @Test
    fun `resume apos restart retoma o timer e faz refresh imediato`() {
        val h = Harness()
        // Estado de um app que fechou com heartbeat ativo.
        h.store.write(
            DeviceState(
                platform = com.lighthousenorth.clientandroid.model.DevicePlatform.ANDROID,
                token = Harness.TOKEN,
                heartbeatActive = true,
                heartbeatIntervalMs = 3_600_000,
            ),
        )

        h.core.resumeAfterInit()

        assertTrue(h.core.heartbeat.running)
        assertEquals(1, h.scheduler.asyncActions.size) // refresh imediato
        h.engine.enqueue(200)
        h.scheduler.asyncActions.single().invoke()
        assertEquals("v1/devices/refresh", h.engine.calls.single().path)
    }

    // ------------------------------------------------------------------
    // Permission watcher
    // ------------------------------------------------------------------

    @Test
    fun `revogacao da permissao faz unregister preservando contactRef para re-consentimento`() {
        val h = Harness()
        h.engine.enqueue(201)
        h.core.registerForPush(contactRef = "cliente-42", policyVersion = "2026-09")
        h.engine.enqueue(200)

        val event = h.core.onPermissionRevoked()

        assertTrue(event.unregistered)
        assertEquals("v1/devices/unregister", h.engine.calls[1].path)
        val state = h.store.state!!
        assertNull(state.token)
        assertEquals("cliente-42", state.contactRef) // preservado
        assertEquals("2026-09", state.policyVersion)
    }

    @Test
    fun `unregister do watcher falhando emite evento com unregistered false e preserva estado`() {
        val h = Harness()
        h.engine.enqueue(201)
        h.core.registerForPush(contactRef = "cliente-42", policyVersion = null)
        repeat(3) { h.engine.enqueueError(java.io.IOException("offline")) }

        val event = h.core.onPermissionRevoked()

        assertFalse(event.unregistered)
        assertTrue(event.error != null)
        assertEquals(Harness.TOKEN, h.store.state?.token) // optOut() continua disponível
    }

    // ------------------------------------------------------------------
    // Rotação de token FCM (3.3)
    // ------------------------------------------------------------------

    @Test
    fun `rotacao registra o novo token, remove o antigo e preserva contactRef`() {
        val h = Harness()
        h.engine.enqueue(201)
        h.core.registerForPush(contactRef = "cliente-42", policyVersion = "2026-09")
        val novo = "fake-fcm-token-NOVO-9876543210abcdef"
        h.engine.enqueue(200) // register novo
        h.engine.enqueue(200) // unregister antigo

        val result = h.core.onNewToken(novo)

        assertTrue(result.changed)
        assertTrue(result.registered)
        assertEquals(true, result.oldRemoved)

        // Registro do NOVO token SEM consent (@gate QA 3.3: rotação não é
        // ato de consentimento — reafirmar granted=true ressuscitaria
        // consentimento revogado no intervalo do optOut parcial):
        assertEquals("v1/devices", h.engine.calls[1].path)
        val register = h.engine.calls[1].json
        assertEquals(novo, register.getString("token"))
        assertEquals("cliente-42", register.getString("contactRef"))
        assertFalse(register.has("consent"))

        // Antigo removido (evita push duplicado na janela de 90d):
        assertEquals("v1/devices/unregister", h.engine.calls[2].path)
        assertEquals(Harness.TOKEN, h.engine.calls[2].json.getString("token"))

        // Estado atualizado:
        assertEquals(novo, h.store.state?.token)
        assertEquals("cliente-42", h.store.state?.contactRef)
    }

    @Test
    fun `rotacao sem estado anterior registra o token sem consent`() {
        val h = Harness()
        val novo = "fake-fcm-token-NOVO-9876543210abcdef"
        h.engine.enqueue(201)

        val result = h.core.onNewToken(novo)

        assertTrue(result.changed)
        assertNull(result.oldRemoved) // não havia antigo
        val register = h.engine.calls.single().json
        assertEquals(setOf("platform", "token"), register.keySet()) // sem contactRef/consent
    }

    @Test
    fun `rotacao com token igual e noop`() {
        val h = Harness()
        h.engine.enqueue(201)
        h.core.registerForPush(contactRef = "c1", policyVersion = null)

        val result = h.core.onNewToken(Harness.TOKEN)

        assertFalse(result.changed)
        assertEquals(1, h.engine.calls.size) // só o register original
    }

    @Test
    fun `rotacao com falha no unregister do antigo segue em frente e reporta`() {
        val h = Harness()
        h.engine.enqueue(201)
        h.core.registerForPush(contactRef = "c1", policyVersion = null)
        val novo = "fake-fcm-token-NOVO-9876543210abcdef"
        h.engine.enqueue(200) // register novo ok
        repeat(3) { h.engine.enqueueError(java.io.IOException("offline")) } // unregister antigo cai

        val result = h.core.onNewToken(novo)

        assertTrue(result.registered)
        assertEquals(false, result.oldRemoved)
        assertEquals(novo, h.store.state?.token) // novo token persistido mesmo assim
    }

    @Test
    fun `rotacao com token invalido falha antes de rede`() {
        val h = Harness()
        assertFailsWith<LighthouseNorthInvalidInputException> { h.core.onNewToken("curto") }
        assertEquals(0, h.engine.calls.size)
    }

    // ------------------------------------------------------------------
    // Estado (formato §5 do contrato)
    // ------------------------------------------------------------------

    @Test
    fun `estado faz round-trip exato pelo formato versionado`() {
        val state = DeviceState(
            platform = com.lighthousenorth.clientandroid.model.DevicePlatform.ANDROID,
            token = Harness.TOKEN,
            contactRef = "cliente-42",
            policyVersion = "2026-09",
            registeredAtMillis = 1_700_000_000_000,
            heartbeatActive = true,
            heartbeatIntervalMs = 86_400_000,
        )
        val json = com.lighthousenorth.clientandroid.model.StateCodec.toJsonString(state)
        val keys = org.json.JSONObject(json).keySet()
        assertEquals(
            setOf("v", "platform", "token", "contactRef", "policyVersion", "registeredAt", "heartbeatActive", "heartbeatIntervalMs"),
            keys,
        )
        assertEquals(state, com.lighthousenorth.clientandroid.model.StateCodec.fromJsonString(json))
    }

    @Test
    fun `estado corrompido ou de versao desconhecida e descartado`() {
        assertNull(com.lighthousenorth.clientandroid.model.StateCodec.fromJsonString("não é json"))
        assertNull(com.lighthousenorth.clientandroid.model.StateCodec.fromJsonString("{\"v\":99}"))
        assertNull(com.lighthousenorth.clientandroid.model.StateCodec.fromJsonString("{\"v\":1,\"platform\":\"tvOS\"}"))
        // Campos com tipos tortos não explodem — viram null/ausentes:
        val state = com.lighthousenorth.clientandroid.model.StateCodec.fromJsonString(
            "{\"v\":1,\"token\":123}",
        )
        assertNull(state?.token)
    }

    @Test
    fun `redact mascara segredos como no SDK Flutter`() {
        assertEquals("fake...cdef", com.lighthousenorth.clientandroid.core.redactSecret(Harness.TOKEN))
        assertEquals("***", com.lighthousenorth.clientandroid.core.redactSecret("curto"))
        assertEquals("ak_1...wxyz", com.lighthousenorth.clientandroid.core.redactSecret("ak_1234567890wxyz"))
    }
}
