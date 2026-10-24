package com.lighthousenorth.clientandroid.core

import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Fonte de tempo (wall clock UTC em millis) — injetável para testes. */
interface Clock {
    fun nowMillis(): Long
}

class WallClock : Clock {
    override fun nowMillis(): Long = System.currentTimeMillis()
}

/** Clock fixo para testes (avançável manualmente). */
class FakeClock(var millis: Long = 0L) : Clock {
    override fun nowMillis(): Long = millis
}

/** Fonte de aleatoriedade para jitter (heartbeat e backoff de retry). */
interface LighthouseNorthRandom {
    fun nextDouble(): Double
}

/** Fonte real (java.util.Random), aceita seed para reprodutibilidade. */
class RealLighthouseNorthRandom(seed: Long? = null) : LighthouseNorthRandom {
    private val random = if (seed != null) java.util.Random(seed) else java.util.Random()
    override fun nextDouble(): Double = random.nextDouble()
}

/** Devolve sempre o mesmo valor (0.0..1.0) — pina os fatores de jitter
 *  exatamente nos limites das fórmulas em teste (paridade FixedLighthouseNorthRandom). */
class FixedLighthouseNorthRandom(val value: Double) : LighthouseNorthRandom {
    init {
        require(value in 0.0..1.0) { "FixedLighthouseNorthRandom espera valor em [0.0, 1.0]" }
    }

    override fun nextDouble(): Double = value
}

/**
 * ISO-8601 UTC com precisão de milissegundos (`yyyy-MM-dd'T'HH:mm:ss.SSS'Z'`).
 * O formato é aceito pela API (zod iso com offset — o smoke 3.2 provou o par
 * ISO↔zod com microssegundos Dart; millis é o subconjunto estrito).
 *
 * SimpleDateFormat não é thread-safe: o SDK serializa as chamadas em um
 * executor próprio, mas o `@Synchronized` garante contra usos paralelos.
 */
object Iso8601 {
    private val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
        isLenient = false
    }

    @Synchronized
    fun format(millis: Long): String = format.format(Date(millis))

    /** `null` quando malformado (estado corrompido ⇒ descartar, não explodir). */
    @Synchronized
    fun parse(text: String): Long? = try {
        format.parse(text)?.time
    } catch (_: ParseException) {
        null
    }
}
