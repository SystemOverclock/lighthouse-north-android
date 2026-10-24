package com.lighthousenorth.clientandroid.errors

/**
 * Hierarquia de erros do SDK — toda exceção lançada pelo SDK é subclasse de
 * [LighthouseNorthException] (o app hospedeiro nunca recebe uma exceção "crua" do
 * transporte/canal sem tipo). Espelha a hierarquia `sealed` do SDK Flutter
 * (`packages/client-flutter/lib/src/errors/errors.dart`) — divergência de
 * comportamento entre as duas pontas = bug (docs/NATIVE_CONTRACT.md).
 */
sealed class LighthouseNorthException(
    message: String,
    val fields: Map<String, Any?> = emptyMap(),
) : Exception(message)

/** Erros de configuração do `init` (app key inválida, base URL insegura etc.). */
sealed class LighthouseNorthConfigurationException(message: String, fields: Map<String, Any?> = emptyMap()) :
    LighthouseNorthException(message, fields)

/** `apiBaseUrl` com `http://` fora de localhost — o SDK é TLS-only. */
class InsecureBaseUrlException(val url: String) : LighthouseNorthConfigurationException(
    "base URL insegura: http:// só é aceito em localhost (dev/testes); use https://",
    mapOf("url" to url),
)

/** `apiBaseUrl` que não parseia como URL http(s) com host. */
class InvalidBaseUrlException : LighthouseNorthConfigurationException(
    "base URL inválida — informe algo como https://api.exemplo.com (sem query/fragmento)",
)

/** App key evidentemente inválida (vazia, curta demais ou com espaços). */
class InvalidAppKeyException(message: String) : LighthouseNorthConfigurationException(message)

/** `LighthouseNorthAndroidClient.get()` usado antes de `init`. */
class LighthouseNorthNotInitializedException : LighthouseNorthException(
    "LighthouseNorthAndroidClient não inicializado — chame LighthouseNorthAndroidClient.init(...) antes",
)

/** Método chamado em um cliente já fechado (`close()`). */
class LighthouseNorthClientClosedException : LighthouseNorthException(
    "LighthouseNorthAndroidClient já foi fechado — chame init(...) novamente",
)

/**
 * Entrada inválida do lado do integrador (token fora de 16..4096 chars,
 * contactRef fora de 1..200) — espelha as regras da API para falhar antes
 * de gastar rede.
 */
class LighthouseNorthInvalidInputException(val field: String, reason: String) :
    LighthouseNorthException("entrada inválida ($field): $reason", mapOf("field" to field))

/**
 * Usuário NEGOU a permissão de notificação do OS — o device NÃO é registrado.
 * Fluxo esperado: o app hospedeiro trata e segue (não é falha).
 */
class PushPermissionDeniedException : LighthouseNorthException(
    "permissão de notificação negada pelo usuário — device não registrado",
)

/**
 * O token push não pôde ser obtido (qualquer causa que não a negação
 * explícita acima). [FirebaseNotConfiguredException] é o subtipo para o caso
 * "host sem Firebase".
 */
open class PushTokenUnavailableException(message: String, fields: Map<String, Any?> = emptyMap()) :
    LighthouseNorthException(message, fields)

/**
 * O app hospedeiro NÃO tem Firebase configurado (o SDK não embute Firebase —
 * ele lê o token do app hospedeiro; ver README, seção Integração).
 */
class FirebaseNotConfiguredException(detail: String) :
    PushTokenUnavailableException("Firebase não configurado no app hospedeiro — $detail")

/** Falha de rede/timeout após todas as tentativas. */
class LighthouseNorthNetworkException(message: String, cause: Throwable? = null) :
    LighthouseNorthException(message, mapOf("cause" to (cause?.toString() ?: ""))) {
    init {
        if (cause != null) initCause(cause)
    }
}

/**
 * Erro HTTP da API fora da política de retry. [statusCode] e [errorCode]
 * quando o corpo traz um código estável.
 */
open class LighthouseNorthApiException(
    val statusCode: Int,
    message: String,
    val errorCode: String? = null,
    extra: Map<String, Any?> = emptyMap(),
) : LighthouseNorthException(
    message,
    buildMap {
        put("statusCode", statusCode)
        if (errorCode != null) put("errorCode", errorCode)
        putAll(extra)
    },
)

/** X-App-Key rejeitada (401 uniforme da API) — key revogada, errada ou de outro ambiente. */
class UnauthorizedAppKeyException(message: String, errorCode: String? = null) :
    LighthouseNorthApiException(401, message, errorCode)

/** Falha ao ler/escrever o estado local persistido. */
class LighthouseNorthStorageException(message: String, fields: Map<String, Any?> = emptyMap()) :
    LighthouseNorthException(message, fields)

/** Falha da camada nativa (Activity ausente para permissão etc.). */
class LighthouseNorthNativeException(val code: String, message: String) :
    LighthouseNorthException(message, mapOf("code" to code))
