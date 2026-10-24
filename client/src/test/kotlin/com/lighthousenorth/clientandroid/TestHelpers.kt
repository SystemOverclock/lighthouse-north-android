package com.lighthousenorth.clientandroid

import com.lighthousenorth.clientandroid.core.Cancellable
import com.lighthousenorth.clientandroid.core.Clock
import com.lighthousenorth.clientandroid.core.Logger
import com.lighthousenorth.clientandroid.core.LogLevel
import com.lighthousenorth.clientandroid.core.LighthouseNorthRandom
import com.lighthousenorth.clientandroid.core.Scheduler
import com.lighthousenorth.clientandroid.errors.LighthouseNorthApiException
import com.lighthousenorth.clientandroid.errors.LighthouseNorthException
import com.lighthousenorth.clientandroid.http.HttpEngine
import com.lighthousenorth.clientandroid.http.HttpRequestData
import com.lighthousenorth.clientandroid.http.HttpResponseData
import com.lighthousenorth.clientandroid.http.Transport
import com.lighthousenorth.clientandroid.permission.FakePermissionGateway
import com.lighthousenorth.clientandroid.push.FakeTokenProvider
import com.lighthousenorth.clientandroid.storage.InMemoryStateStore
import org.json.JSONObject
import java.io.IOException

// ---------------------------------------------------------------------------
// Fakes de infra (tempo virtual, engine gravador) — ZERO rede real.
// ---------------------------------------------------------------------------

/** Scheduler com tempo virtual: registra delays e ações sem executar nada
 *  até o teste disparar manualmente (paridade FakeScheduler do Dart). */
class ManualScheduler : Scheduler {
    data class ScheduledTask(val delayMs: Long, val action: () -> Unit, var cancelled: Boolean = false)

    val scheduled = mutableListOf<ScheduledTask>()
    val sleeps = mutableListOf<Long>()
    val asyncActions = mutableListOf<() -> Unit>()

    override fun sleep(delayMs: Long) {
        sleeps += delayMs
    }

    override fun schedule(delayMs: Long, action: () -> Unit): Cancellable {
        val task = ScheduledTask(delayMs, action)
        scheduled += task
        return object : Cancellable {
            override fun cancel() {
                task.cancelled = true
            }
        }
    }

    override fun executeAsync(action: () -> Unit) {
        asyncActions += action
    }

    /** Dispara a tarefa agendada mais antiga não cancelada (tick do heartbeat). */
    fun runNext(): Boolean {
        val task = scheduled.firstOrNull { !it.cancelled } ?: return false
        task.cancelled = true
        task.action()
        return true
    }
}

/** Engine que registra pedidos e responde de um script — zero rede. */
class RecordingEngine : HttpEngine {
    data class Call(
        val method: String,
        val url: String,
        val headers: Map<String, String>,
        val body: String,
    ) {
        val json: JSONObject get() = JSONObject(body)
        val path: String get() = url.substringAfter("://").substringAfter("/")
    }

    val calls = mutableListOf<Call>()
    val script = mutableListOf<(HttpRequestData) -> HttpResponseData>()

    fun enqueue(status: Int, body: String = "{\"ok\":true}") {
        script += { HttpResponseData(status, body) }
    }

    fun enqueueError(exception: IOException) {
        script += { throw exception }
    }

    @Throws(IOException::class)
    override fun execute(request: HttpRequestData): HttpResponseData {
        calls += Call(request.method, request.url, request.headers, request.body)
        val next = script.removeFirstOrNull()
            ?: throw IllegalStateException("RecordingEngine sem resposta scriptada (${request.url})")
        return next(request)
    }
}

/** Logger que acumula em memória para asserções. */
class RecordingLogger : Logger {
    data class Entry(val level: LogLevel, val event: String, val fields: Map<String, Any?>)

    val entries = mutableListOf<Entry>()
    override fun log(level: LogLevel, event: String, fields: Map<String, Any?>) {
        entries += Entry(level, event, fields)
    }

    fun events(name: String): List<Entry> = entries.filter { it.event == name }
}

// ---------------------------------------------------------------------------
// Harness — monta um ClientCore com TUDO fake.
// ---------------------------------------------------------------------------

class Harness(
    val engine: RecordingEngine = RecordingEngine(),
    val store: InMemoryStateStore = InMemoryStateStore(),
    val tokens: FakeTokenProvider = FakeTokenProvider(),
    val permissions: FakePermissionGateway = FakePermissionGateway(),
    val scheduler: ManualScheduler = ManualScheduler(),
    random: LighthouseNorthRandom = com.lighthousenorth.clientandroid.core.FixedLighthouseNorthRandom(0.5),
    clock: Clock = com.lighthousenorth.clientandroid.core.FakeClock(1_700_000_000_000),
    val logger: RecordingLogger = RecordingLogger(),
    baseUrl: String = "https://api.lighthouse-north.test",
) {
    val core: com.lighthousenorth.clientandroid.core.ClientCore = com.lighthousenorth.clientandroid.core.ClientCore(
        api = Transport(
            engine = engine,
            baseUrl = baseUrl,
            appKey = "ak_test_0000000000000000000000000001",
            scheduler = scheduler,
            random = random,
            logger = logger,
        ),
        store = store,
        tokenProvider = tokens,
        permissions = permissions,
        scheduler = scheduler,
        random = random,
        clock = clock,
        logger = logger,
    )

    companion object {
        const val APP_KEY = "ak_test_0000000000000000000000000001"
        const val TOKEN = "fake-fcm-token-0123456789abcdef"
    }
}

// ---------------------------------------------------------------------------
// Asserts auxiliares
// ---------------------------------------------------------------------------

fun assertLighthouseNorth(expected: Class<out LighthouseNorthException>, block: () -> Unit): LighthouseNorthException {
    try {
        block()
    } catch (e: LighthouseNorthException) {
        if (!expected.isInstance(e)) {
            throw AssertionError("esperava ${expected.simpleName}, veio ${e::class.simpleName}: $e")
        }
        return e
    }
    throw AssertionError("esperava ${expected.simpleName}, nada foi lançado")
}

val LighthouseNorthException.apiStatus: Int?
    get() = (this as? LighthouseNorthApiException)?.statusCode
