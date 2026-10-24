package com.lighthousenorth.clientandroid

import com.lighthousenorth.clientandroid.core.FixedLighthouseNorthRandom
import com.lighthousenorth.clientandroid.errors.InsecureBaseUrlException
import com.lighthousenorth.clientandroid.errors.InvalidBaseUrlException
import com.lighthousenorth.clientandroid.errors.LighthouseNorthApiException
import com.lighthousenorth.clientandroid.errors.LighthouseNorthNetworkException
import com.lighthousenorth.clientandroid.errors.UnauthorizedAppKeyException
import com.lighthousenorth.clientandroid.http.RetryPolicy
import com.lighthousenorth.clientandroid.http.Transport
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Transporte: TLS-only, User-Agent/headers minimizados, retry exato
 * (500ms→1s→2s com jitter ±25%, máx. 3, só 408/429/5xx) — paridade com a
 * suíte do SDK Flutter. ZERO rede real (RecordingEngine).
 */
class TransportTest {

    private fun transport(engine: RecordingEngine, scheduler: ManualScheduler): Transport =
        Transport(
            engine = engine,
            baseUrl = "https://api.lighthouse-north.test",
            appKey = Harness.APP_KEY,
            scheduler = scheduler,
            random = FixedLighthouseNorthRandom(0.5),
            logger = RecordingLogger(),
        )

    // ------------------------------------------------------------------
    // Base URL: TLS-only com exceção documentada de loopback
    // ------------------------------------------------------------------

    @Test
    fun `https é aceito`() {
        val uri = Transport.validateBaseUrl("https://api.exemplo.com/")
        assertEquals("api.exemplo.com", uri.host)
    }

    @Test
    fun `http loopback é aceito para dev e testes`() {
        assertEquals("localhost", Transport.validateBaseUrl("http://localhost:3000").host)
        assertEquals("127.0.0.1", Transport.validateBaseUrl("http://127.0.0.1:3000").host)
        assertEquals(null, Transport.validateBaseUrl("http://[::1]:3000").userInfo)
    }

    @Test
    fun `http fora de loopback é rejeitado com erro tipado`() {
        assertFailsWith<InsecureBaseUrlException> { Transport.validateBaseUrl("http://api.exemplo.com") }
        assertFailsWith<InsecureBaseUrlException> { Transport.validateBaseUrl("http://10.0.2.2:3000") }
    }

    @Test
    fun `base url malformada é rejeitada`() {
        assertFailsWith<InvalidBaseUrlException> { Transport.validateBaseUrl("não é url") }
        assertFailsWith<InvalidBaseUrlException> { Transport.validateBaseUrl("ftp://x.com") }
        assertFailsWith<InvalidBaseUrlException> { Transport.validateBaseUrl("https://x.com?q=1") }
        assertFailsWith<InvalidBaseUrlException> { Transport.validateBaseUrl("https://x.com#f") }
        assertFailsWith<InvalidBaseUrlException> { Transport.validateBaseUrl("https://user:pw@x.com") }
        assertFailsWith<InvalidBaseUrlException> { Transport.validateBaseUrl("  ") }
    }

    // ------------------------------------------------------------------
    // Request exato: headers minimizados + UA próprio
    // ------------------------------------------------------------------

    @Test
    fun `request tem exatamente os headers do contrato`() {
        val engine = RecordingEngine()
        engine.enqueue(201)
        val transport = transport(engine, ManualScheduler())

        transport.postJson("/v1/devices", org.json.JSONObject().put("token", Harness.TOKEN))

        val call = engine.calls.single()
        assertEquals("POST", call.method)
        assertEquals("https://api.lighthouse-north.test/v1/devices", call.url)
        assertEquals(Harness.APP_KEY, call.headers["x-app-key"])
        assertEquals("application/json", call.headers["accept"])
        assertEquals("application/json; charset=utf-8", call.headers["content-type"])
        assertEquals(
            "lighthouse-north-client-android/0.1.0 (kotlin;android)",
            call.headers["user-agent"],
        )
        // Minimização: NENHUM header além dos 4 do contrato.
        assertEquals(setOf("x-app-key", "accept", "user-agent", "content-type"), call.headers.keys)
    }

    // ------------------------------------------------------------------
    // Retry: status e delays exatos
    // ------------------------------------------------------------------

    @Test
    fun `5xx tenta 3 vezes com backoff 500-1000-2000 e jitter neutro`() {
        val engine = RecordingEngine()
        engine.enqueue(500)
        engine.enqueue(503)
        engine.enqueue(200)
        val scheduler = ManualScheduler()
        val transport = transport(engine, scheduler)

        val status = transport.postJson("/v1/devices", org.json.JSONObject())

        assertEquals(200, status)
        assertEquals(3, engine.calls.size)
        // FixedLighthouseNorthRandom(0.5) → fator exatamente 1.0 (±25% neutro).
        assertEquals(listOf(500L, 1000L), scheduler.sleeps)
    }

    @Test
    fun `429 e 408 são retryáveis, 4xx de negócio NÃO`() {
        for (status in listOf(408, 429, 500)) {
            assertTrue(RetryPolicy.isRetryableStatus(status), "esperava retry em $status")
        }
        for (status in listOf(400, 401, 403, 404, 409, 422)) {
            assertTrue(!RetryPolicy.isRetryableStatus(status), "não esperava retry em $status")
        }
    }

    @Test
    fun `4xx de negócio falha na primeira tentativa`() {
        val engine = RecordingEngine()
        engine.enqueue(400, "{\"error\":\"invalid_input\"}")
        val transport = transport(engine, ManualScheduler())

        val e = assertFailsWith<LighthouseNorthApiException> {
            transport.postJson("/v1/devices", org.json.JSONObject())
        }
        assertEquals(400, e.statusCode)
        assertEquals("invalid_input", e.errorCode)
        assertEquals(1, engine.calls.size) // sem retry
    }

    @Test
    fun `401 vira UnauthorizedAppKeyException sem retry`() {
        val engine = RecordingEngine()
        engine.enqueue(401, "{\"error\":\"unauthorized\"}")
        val transport = transport(engine, ManualScheduler())

        assertFailsWith<UnauthorizedAppKeyException> {
            transport.postJson("/v1/devices", org.json.JSONObject())
        }
        assertEquals(1, engine.calls.size)
    }

    @Test
    fun `5xx persistente esgota em 3 tentativas e vira LighthouseNorthApiException`() {
        val engine = RecordingEngine()
        engine.enqueue(500)
        engine.enqueue(500)
        engine.enqueue(500)
        val scheduler = ManualScheduler()
        val transport = transport(engine, scheduler)

        val e = assertFailsWith<LighthouseNorthApiException> {
            transport.postJson("/v1/devices", org.json.JSONObject())
        }
        assertEquals(500, e.statusCode)
        assertEquals(3, engine.calls.size)
        assertEquals(listOf(500L, 1000L), scheduler.sleeps)
    }

    @Test
    fun `falha de rede persistente esgota em 3 tentativas e vira LighthouseNorthNetworkException`() {
        val engine = RecordingEngine()
        engine.enqueueError(IOException("sem rota para o host"))
        engine.enqueueError(IOException("sem rota para o host"))
        engine.enqueueError(IOException("sem rota para o host"))
        val scheduler = ManualScheduler()
        val transport = transport(engine, scheduler)

        assertFailsWith<LighthouseNorthNetworkException> {
            transport.postJson("/v1/devices", org.json.JSONObject())
        }
        assertEquals(3, engine.calls.size)
        assertEquals(listOf(500L, 1000L), scheduler.sleeps)
    }

    @Test
    fun `jitter multiplica o backoff entre 0,75x e 1,25x`() {
        val policy = RetryPolicy()
        // random=0 → fator 0,75 (teto inferior); random=1 → 1,25 (teto superior).
        assertEquals(375L, policy.delayFor(1, FixedLighthouseNorthRandom(0.0)))
        assertEquals(625L, policy.delayFor(1, FixedLighthouseNorthRandom(1.0)))
        assertEquals(750L, policy.delayFor(2, FixedLighthouseNorthRandom(0.0)))
        assertEquals(1250L, policy.delayFor(2, FixedLighthouseNorthRandom(1.0)))
        assertEquals(1500L, policy.delayFor(3, FixedLighthouseNorthRandom(0.0)))
        assertEquals(2500L, policy.delayFor(3, FixedLighthouseNorthRandom(1.0)))
    }
}
