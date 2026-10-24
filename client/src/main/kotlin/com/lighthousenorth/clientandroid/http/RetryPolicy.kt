package com.lighthousenorth.clientandroid.http

import com.lighthousenorth.clientandroid.core.LighthouseNorthRandom
import kotlin.math.roundToLong

/**
 * Política de retry: backoff exponencial (base 500ms → 1s → 2s) com jitter
 * multiplicativo de ±25%, no máximo [maxAttempts] tentativas — paridade
 * exata com o RetryPolicy do SDK Flutter.
 */
class RetryPolicy(
    val maxAttempts: Int = 3,
    val baseDelayMs: Long = 500,
) {
    /**
     * Delay após a tentativa [failedAttempt] (1-based) falhar.
     * Com `FixedLighthouseNorthRandom(0.5)` o fator é exatamente 1.0 (determinístico).
     */
    fun delayFor(failedAttempt: Int, random: LighthouseNorthRandom): Long {
        val exponential = baseDelayMs shl (failedAttempt - 1)
        val factor = 0.75 + 0.5 * random.nextDouble()
        return (exponential * factor).roundToLong()
    }

    companion object {
        /**
         * HTTP que vale retry: timeout de request (408), rate limit (429) e
         * erros de servidor (5xx). Nenhum 4xx de negócio (400/401/404...) —
         * retry não muda a resposta.
         */
        fun isRetryableStatus(statusCode: Int): Boolean {
            return statusCode == 408 || statusCode == 429 || statusCode >= 500
        }
    }
}
