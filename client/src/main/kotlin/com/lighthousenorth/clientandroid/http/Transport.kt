package com.lighthousenorth.clientandroid.http

import com.lighthousenorth.clientandroid.core.Logger
import com.lighthousenorth.clientandroid.core.LighthouseNorthRandom
import com.lighthousenorth.clientandroid.core.Scheduler
import com.lighthousenorth.clientandroid.errors.InsecureBaseUrlException
import com.lighthousenorth.clientandroid.errors.InvalidBaseUrlException
import com.lighthousenorth.clientandroid.errors.LighthouseNorthApiException
import com.lighthousenorth.clientandroid.errors.LighthouseNorthNetworkException
import com.lighthousenorth.clientandroid.errors.UnauthorizedAppKeyException
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.URI

/**
 * Transporte HTTP do SDK — ÚNICA saída de rede da library.
 *
 * Garantes transversais (herdados do SDK Flutter, contrato NATIVE_CONTRACT §2):
 * - **TLS-only**: `https://` sempre; `http://` só para localhost (dev/testes);
 * - timeout por tentativa de 10s (engine);
 * - retry com backoff exponencial + jitter (máx. 3 tentativas, só
 *   operações idempotentes, só 408/429/5xx);
 * - User-Agent próprio e fixo — nenhum dado do app hospedeiro;
 * - corpo EXATAMENTE o do contrato (minimização por construção: quem monta
 *   o JSON é o caller com um objeto fechado).
 */
class Transport(
    val engine: HttpEngine,
    baseUrl: String,
    val appKey: String,
    private val scheduler: Scheduler,
    private val random: LighthouseNorthRandom,
    private val logger: Logger,
    private val retryPolicy: RetryPolicy = RetryPolicy(),
) {
    val baseUrl: URI = validateBaseUrl(baseUrl)

    /**
     * User-Agent de toda requisição — identidade do SDK apenas (nome, versão,
     * linguagem, SO). Contrato de minimização: NADA do app hospedeiro aqui.
     */
    val userAgent: String = "lighthouse-north-client-android/$SDK_VERSION (kotlin;android)"

    /**
     * POST JSON. Retorna o status HTTP (200 ou 200/201 para register).
     *
     * @param idempotent habilita a política de retry — as três rotas de
     * devices são idempotentes por contrato, então o SDK sempre passa `true`;
     * o parâmetro existe para futuras rotas não idempotentes nunca pegarem
     * retry por engano.
     */
    @Throws(LighthouseNorthApiException::class, LighthouseNorthNetworkException::class)
    fun postJson(path: String, body: JSONObject, idempotent: Boolean = true): Int {
        var attempt = 0
        while (true) {
            attempt++
            try {
                val response = engine.execute(buildRequest(path, body))
                val status = response.statusCode
                if (status in 200..299) return status
                if (RetryPolicy.isRetryableStatus(status) && idempotent &&
                    attempt < retryPolicy.maxAttempts
                ) {
                    sleepBackoff(attempt, path, status)
                    continue
                }
                throw exceptionForStatus(status, response.body)
            } catch (e: IOException) {
                if (!idempotent || attempt >= retryPolicy.maxAttempts) {
                    throw LighthouseNorthNetworkException(
                        "falha de rede/timeout após $attempt tentativa(s) em $path",
                        e,
                    )
                }
                sleepBackoff(attempt, path)
            }
        }
    }

    fun buildRequest(path: String, body: JSONObject): HttpRequestData = HttpRequestData(
        method = "POST",
        url = joinUrl(baseUrl, path).toASCIIString(),
        headers = mapOf(
            "x-app-key" to appKey,
            "accept" to "application/json",
            "user-agent" to userAgent,
            "content-type" to "application/json; charset=utf-8",
        ),
        body = body.toString(),
    )

    private fun sleepBackoff(failedAttempt: Int, path: String, status: Int? = null) {
        val delay = retryPolicy.delayFor(failedAttempt, random)
        logger.log(
            com.lighthousenorth.clientandroid.core.LogLevel.WARNING,
            "http.retry",
            buildMap {
                put("path", path)
                status?.let { put("statusCode", it) }
                put("attempt", failedAttempt)
                put("delayMs", delay)
            },
        )
        scheduler.sleep(delay)
    }

    private fun exceptionForStatus(status: Int, bodyText: String): LighthouseNorthApiException {
        var errorCode: String? = null
        var snippet = ""
        try {
            val decoded = JSONObject(bodyText)
            errorCode = decoded.optString("error").takeIf { it.isNotEmpty() }
                ?: decoded.optString("code").takeIf { it.isNotEmpty() }
            snippet = if (bodyText.length > 200) bodyText.substring(0, 200) else bodyText
        } catch (_: JSONException) {
            // corpo não-JSON — segue com snippet vazio
        }
        if (status == 401) {
            return UnauthorizedAppKeyException(
                "app key rejeitada pela API (401) — confira a key gerada no painel do tenant",
                errorCode,
            )
        }
        return LighthouseNorthApiException(
            status,
            "erro $status da API",
            errorCode,
            if (snippet.isNotEmpty()) mapOf("body" to snippet) else emptyMap(),
        )
    }

    companion object {
        const val SDK_VERSION = "0.1.0"

        /**
         * Valida e normaliza a base URL da API.
         *
         * Regras (security-first, paridade com o SDK Flutter): `https://`
         * sempre; `http://` aceito SOMENTE em `localhost`/`127.0.0.1`/`[::1]`
         * para dev e testes; sem query/fragmento.
         */
        fun validateBaseUrl(raw: String): URI {
            val trimmed = raw.trim().trimEnd('/')
            val uri = try {
                URI(trimmed)
            } catch (_: java.net.URISyntaxException) {
                throw InvalidBaseUrlException()
            }
            val scheme = uri.scheme?.lowercase()
            val host = uri.host?.let { it.removePrefix("[").removeSuffix("]") }
            if (scheme !in setOf("http", "https") ||
                host.isNullOrEmpty() ||
                uri.rawQuery != null ||
                uri.rawFragment != null ||
                uri.rawUserInfo != null
            ) {
                throw InvalidBaseUrlException()
            }
            val isLoopback = host == "localhost" || host == "127.0.0.1" || host == "::1"
            if (scheme == "https" || (scheme == "http" && isLoopback)) {
                return uri
            }
            throw InsecureBaseUrlException(raw)
        }

        /** Resolve `path` contra a base (sem biblioteca — URI.resolve). */
        fun joinUrl(base: URI, path: String): URI {
            val resolved = base.resolve(path)
            return resolved
        }
    }
}
