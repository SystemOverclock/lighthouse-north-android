package com.lighthousenorth.clientandroid.http

import java.io.IOException

/** Pedido/respuesta imutáveis da camada de engine (injetável para testes). */
data class HttpRequestData(
    val method: String,
    val url: String,
    val headers: Map<String, String>,
    val body: String,
)

data class HttpResponseData(
    val statusCode: Int,
    val body: String,
)

/**
 * Engine HTTP do SDK — a ÚNICA fronteira com java.net. Testes injetam fakes
 * (ZERO rede real na suíte JVM); a implementação de produção é
 * [HttpUrlConnectionEngine].
 */
interface HttpEngine {
    /**
     * Executa o pedido. Falhas de rede/timeout saem como [IOException] — o
     * [Transport] decide retry/backoff. Código de status de erro (4xx/5xx)
     * NÃO é exceção: volta como [HttpResponseData] normal.
     */
    @Throws(IOException::class)
    fun execute(request: HttpRequestData): HttpResponseData
}
