package com.lighthousenorth.clientandroid.http

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Engine de produção sobre **HttpURLConnection** (decisão deliberada — ver
 * README "OkHttp vs HttpURLConnection"):
 *
 * - ZERO dependências externas no app hospedeiro (higiene de dependência é
 *   requisito de produto para um SDK de terceiros);
 * - as 3 rotas de devices são POSTs simples — sem necessidade de pooling,
 *   HTTP/2 ou interceptors;
 * - timeout por tentativa (connect+read) de 10s, redirects desligados
 *   (a API nunca redireciona; desligar remove a conversão silenciosa
 *   POST→GET do HUC em redirect).
 *
 * Camadas TLS/certificados: as do OS (mesma confiança do app hospedeiro).
 * O SDK é TLS-only por construção — a validação de esquema acontece no
 * [Transport] ANTES de qualquer engine receber a URL.
 */
class HttpUrlConnectionEngine(
    private val timeoutMs: Int = 10_000,
) : HttpEngine {

    override fun execute(request: HttpRequestData): HttpResponseData {
        // Belt de defesa em profundidade: só https:// (ou http loopback
        // validado pelo Transport) chega aqui; engine rejeita o resto.
        val url = URL(request.url)
        val scheme = url.protocol.lowercase()
        val host = url.host
        val loopback = host == "localhost" || host == "127.0.0.1" ||
            host == "[::1]" || host == "::1"
        if (scheme != "https" && !(scheme == "http" && loopback)) {
            throw IOException("esquema recusado pelo engine: $scheme")
        }

        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = request.method
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            instanceFollowRedirects = false
            doOutput = request.body.isNotEmpty()
            for ((name, value) in request.headers) setRequestProperty(name, value)
        }
        try {
            if (request.body.isNotEmpty()) {
                connection.outputStream.use { it.write(request.body.toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..399) connection.inputStream else connection.errorStream
            val body = stream?.use { it.readTextCap(4096) } ?: ""
            return HttpResponseData(status, body)
        } finally {
            connection.disconnect()
        }
    }

    /** Lê até [cap] chars (snippet de erro basta; 4k é o teto documentado). */
    private fun java.io.InputStream.readTextCap(cap: Int): String {
        val buffer = ByteArray(2048)
        val sb = StringBuilder()
        while (sb.length < cap) {
            val read = this.read(buffer)
            if (read < 0) break
            sb.append(String(buffer, 0, read, Charsets.UTF_8))
        }
        return if (sb.length > cap) sb.substring(0, cap) else sb.toString()
    }
}
