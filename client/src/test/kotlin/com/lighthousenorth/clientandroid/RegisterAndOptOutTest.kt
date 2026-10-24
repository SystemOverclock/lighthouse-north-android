package com.lighthousenorth.clientandroid

import com.lighthousenorth.clientandroid.errors.PushPermissionDeniedException
import com.lighthousenorth.clientandroid.errors.LighthouseNorthInvalidInputException
import com.lighthousenorth.clientandroid.model.DeviceState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Fluxo canônico registerForPush + optOut — paridade com a suíte do SDK
 * Flutter, incluindo a PROVA DE MINIMIZAÇÃO (corpo fechado em
 * platform/token/contactRef/consent) e a ordem deliberada do opt-out.
 */
class RegisterAndOptOutTest {

    // ------------------------------------------------------------------
    // registerForPush
    // ------------------------------------------------------------------

    @Test
    fun `registro feliz envia exatamente os 4 campos do contrato e persiste estado`() {
        val h = Harness()
        h.engine.enqueue(201)

        val reg = h.core.registerForPush(contactRef = "cliente-42", policyVersion = "2026-09")

        // MINIMIZAÇÃO por asserção de keys exatas:
        val body = h.engine.calls.single().json
        assertEquals(setOf("platform", "token", "contactRef", "consent"), body.keySet())
        assertEquals("android", body.getString("platform"))
        assertEquals(Harness.TOKEN, body.getString("token"))
        assertEquals("cliente-42", body.getString("contactRef"))
        val consent = body.getJSONObject("consent")
        assertEquals(setOf("granted", "capturedAt", "policyVersion"), consent.keySet())
        assertTrue(consent.getBoolean("granted"))
        assertEquals("2026-09", consent.getString("policyVersion"))
        assertTrue(
            Regex("""^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z$""")
                .matches(consent.getString("capturedAt")),
        )

        // Estado persistido (mínimo):
        val state = h.store.state!!
        assertEquals(Harness.TOKEN, state.token)
        assertEquals("cliente-42", state.contactRef)
        assertEquals("2026-09", state.policyVersion)
        assertEquals("android", state.platform?.wire)

        // Resultado com token mascarado:
        assertEquals("fake...cdef", reg.maskedToken)
        assertFalse(reg.updatedExisting) // 201 = device novo
    }

    @Test
    fun `permissao negada nao registra e nao gasta rede`() {
        val h = Harness()
        h.permissions.granted = false

        assertFailsWith<PushPermissionDeniedException> {
            h.core.registerForPush(contactRef = null, policyVersion = null)
        }

        assertEquals(0, h.engine.calls.size)
        assertNull(h.store.state)
    }

    @Test
    fun `contactRef fora de 1-200 caracteres falha antes de rede`() {
        val h = Harness()
        assertFailsWith<LighthouseNorthInvalidInputException> {
            h.core.registerForPush(contactRef = "", policyVersion = null)
        }
        assertFailsWith<LighthouseNorthInvalidInputException> {
            h.core.registerForPush(contactRef = "x".repeat(201), policyVersion = null)
        }
        assertEquals(0, h.engine.calls.size)
    }

    @Test
    fun `token fora de 16-4096 caracteres falha antes de rede`() {
        val h = Harness()
        h.tokens.tokenValue = "curto"
        assertFailsWith<LighthouseNorthInvalidInputException> {
            h.core.registerForPush(contactRef = null, policyVersion = null)
        }
        assertEquals(0, h.engine.calls.size)
    }

    @Test
    fun `200 do register significa update de device existente`() {
        val h = Harness()
        h.engine.enqueue(200)
        val reg = h.core.registerForPush(contactRef = null, policyVersion = null)
        assertTrue(reg.updatedExisting)
    }

    @Test
    fun `re-registro sem contactRef preserva o contactRef anterior do estado`() {
        val h = Harness()
        h.engine.enqueue(201)
        h.core.registerForPush(contactRef = "cliente-42", policyVersion = "2026-09")
        h.engine.enqueue(200)

        h.core.registerForPush(contactRef = null, policyVersion = null)

        // Corpo do 2º registro leva o contactRef persistido (paridade Dart:
        // `contactRef ?? _state?.contactRef`).
        val second = h.engine.calls[1].json
        assertEquals("cliente-42", second.getString("contactRef"))
        assertEquals("cliente-42", h.store.state?.contactRef)
        assertEquals("2026-09", h.store.state?.policyVersion)
    }

    // ------------------------------------------------------------------
    // optOut — ordem deliberada + best-effort
    // ------------------------------------------------------------------

    @Test
    fun `optOut revoga consent ANTES do unregister e limpa o estado`() {
        val h = Harness()
        h.engine.enqueue(201)
        h.core.registerForPush(contactRef = "cliente-42", policyVersion = "2026-09")
        h.engine.enqueue(200) // revoke
        h.engine.enqueue(200) // unregister

        val result = h.core.optOut()

        assertTrue(result.fullyOptedOut)
        assertEquals(3, h.engine.calls.size)
        // ORDEM: revoke (POST /v1/devices granted=false) vem ANTES do unregister.
        assertEquals("v1/devices", h.engine.calls[1].path)
        val revoke = h.engine.calls[1].json
        assertEquals(false, revoke.getJSONObject("consent").getBoolean("granted"))
        assertEquals("cliente-42", revoke.getString("contactRef"))
        assertEquals(Harness.TOKEN, revoke.getString("token"))
        assertEquals("v1/devices/unregister", h.engine.calls[2].path)
        assertEquals(Harness.TOKEN, h.engine.calls[2].json.getString("token"))
        assertNull(h.store.state)
    }

    @Test
    fun `optOut sem registro local e noop`() {
        val h = Harness()
        val result = h.core.optOut()
        assertTrue(result.fullyOptedOut)
        assertFalse(result.hadRegistration)
        assertEquals(0, h.engine.calls.size)
    }

    @Test
    fun `falha TOTAL de rede preserva o estado para re-tentar`() {
        val h = Harness()
        h.engine.enqueue(201)
        h.core.registerForPush(contactRef = "cliente-42", policyVersion = null)
        // Ambos os passos do optOut caem (rede) — 3 tentativas cada.
        repeat(6) { h.engine.enqueueError(java.io.IOException("offline")) }

        val result = h.core.optOut()

        assertFalse(result.fullyOptedOut)
        assertEquals(2, result.failures.size)
        assertFalse(result.stateCleared)
        assertEquals(Harness.TOKEN, h.store.state?.token) // preservado
        assertEquals("cliente-42", h.store.state?.contactRef)
    }

    @Test
    fun `falha apenas do revoke mantem unregister e limpa estado`() {
        val h = Harness()
        h.engine.enqueue(201)
        h.core.registerForPush(contactRef = "cliente-42", policyVersion = null)
        repeat(3) { h.engine.enqueueError(java.io.IOException("revoke caiu")) } // revoke falha (3 tentativas)
        h.engine.enqueue(200) // unregister ok

        val result = h.core.optOut()

        assertFalse(result.fullyOptedOut)
        assertEquals(false, result.consentRevoked)
        assertTrue(result.unregistered)
        assertTrue(result.stateCleared)
        assertNull(h.store.state)
        assertEquals(1, result.failures.size)
    }

    @Test
    fun `sem contactRef o optOut pula revoke e so unregister`() {
        val h = Harness()
        // Registra SEM contactRef (device solto, sem base marketing).
        h.engine.enqueue(201)
        h.core.registerForPush(contactRef = null, policyVersion = null)
        h.engine.enqueue(200)

        val result = h.core.optOut()

        assertNull(result.consentRevoked) // etapa não aplicável
        assertTrue(result.unregistered)
        assertEquals(2, h.engine.calls.size)
        assertTrue(result.fullyOptedOut)
    }

    @Test
    fun `sem token mas com contactRef registra skip do revoke (base permanece sem alvo)`() {
        val h = Harness()
        // Estado sem token (ex.: permissão do OS já revogada pelo watcher).
        h.store.write(DeviceState(contactRef = "cliente-42", policyVersion = "2026-09"))

        val result = h.core.optOut()

        assertNull(result.consentRevoked)
        assertTrue(result.unregistered) // nada a remover
        assertTrue(result.stateCleared)
        assertEquals(0, h.engine.calls.size) // nenhum POST possível
        assertTrue(h.logger.events("opt_out.consent_revoke_skipped").isNotEmpty())
    }
}
