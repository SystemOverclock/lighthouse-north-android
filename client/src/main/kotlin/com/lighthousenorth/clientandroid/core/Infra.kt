package com.lighthousenorth.clientandroid.core

/** Níveis de log do SDK (silencioso por padrão — NoopLogger). */
enum class LogLevel { DEBUG, INFO, WARNING, ERROR }

/**
 * Log estruturado opcional do SDK. O integrador pluga o próprio sink
 * (ex.: gravar em Crashlytics/console). Segredos NUNCA chegam inteiros aqui —
 * token/app key passam antes por [redactSecret].
 */
interface Logger {
    fun log(level: LogLevel, event: String, fields: Map<String, Any?> = emptyMap())
}

object NoopLogger : Logger {
    override fun log(level: LogLevel, event: String, fields: Map<String, Any?>) = Unit
}

/** Logger em memória — usado pelo app de exemplo e pelos testes. */
class InMemoryLogger : Logger {
    data class Entry(val level: LogLevel, val event: String, val fields: Map<String, Any?>)

    private val _entries = mutableListOf<Entry>()
    val entries: List<Entry> get() = _entries

    override fun log(level: LogLevel, event: String, fields: Map<String, Any?>) {
        _entries += Entry(level, event, fields)
    }
}

/**
 * Máscaras de segredo — token e app key NUNCA aparecem inteiros em log,
 * erro ou evento (princípio "zero segredos em código E em log" do projeto).
 * Paridade exata com `redactSecret` do SDK Flutter.
 */
fun redactSecret(value: String): String {
    if (value.length <= 8) return "***"
    return "${value.substring(0, 4)}...${value.substring(value.length - 4)}"
}

/** Tarefa agendada cancelável (heartbeat). */
interface Cancellable {
    fun cancel()
}

/**
 * Agenda/executa trabalho — port injetável para testes (tempo virtual) e
 * implementação Android (executor próprio).
 *
 * O SDK NUNCA bloqueia a main thread: [sleep] só roda na thread do SDK;
 * [schedule] dispara a ação em worker próprio; [executeAsync] é
 * fire-and-forget para o refresh imediato pós-restart.
 */
interface Scheduler {
    /** Bloqueia a thread corrente pelo delay (usado no backoff de retry). */
    fun sleep(delayMs: Long)

    /** Agenda a ação após o delay; retorna handle cancelável. */
    fun schedule(delayMs: Long, action: () -> Unit): Cancellable

    /** Fire-and-forget em worker do SDK. */
    fun executeAsync(action: () -> Unit)
}

object NoopCancellable : Cancellable {
    override fun cancel() = Unit
}

/**
 * Scheduler de produção: java.util.concurrent puro (sem dependência Android).
 * Um único worker serializa as ações do SDK — o mesmo isolamento lógico do
 * event loop single-threaded do SDK Flutter.
 */
class ExecutorScheduler(
    private val executor: java.util.concurrent.ScheduledExecutorService =
        java.util.concurrent.Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "lighthouse-client").apply { isDaemon = true }
        },
) : Scheduler {

    override fun sleep(delayMs: Long) {
        try {
            Thread.sleep(delayMs)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    override fun schedule(delayMs: Long, action: () -> Unit): Cancellable {
        val future = executor.schedule(action, delayMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        return object : Cancellable {
            override fun cancel() {
                future.cancel(false)
            }
        }
    }

    override fun executeAsync(action: () -> Unit) {
        executor.execute(action)
    }

    /** Executa a ação imediatamente no worker serial do SDK. */
    fun execute(action: () -> Unit) {
        executor.execute(action)
    }

    fun shutdown() {
        executor.shutdownNow()
    }
}
