package com.lighthousenorth.clientandroid.model

import com.lighthousenorth.clientandroid.core.Iso8601
import org.json.JSONException
import org.json.JSONObject

/**
 * Plataforma do device — ESPELHO exato do enum da API (`android|ios|web`).
 * Contrato fixado na fatia 3.2: se a API mudar os valores, aqui muda junto
 * (divergência = bug das duas pontas).
 */
enum class DevicePlatform(val wire: String) {
    ANDROID("android"),
    IOS("ios"),
    WEB("web");

    companion object {
        fun fromWire(wire: String): DevicePlatform? = when (wire) {
            "android" -> ANDROID
            "ios" -> IOS
            "web" -> WEB
            else -> null
        }
    }
}

/**
 * Evidência do consentimento push capturada no app (LGPD art. 11 — a
 * plataforma grava a trilha no contato quando acompanhada de `contactRef`).
 *
 * Semântica do contrato 3.2:
 * - `granted=true`  + contactRef → cria base legal push (escopo marketing);
 * - `granted=false` + contactRef → revoga a base push vigente (opt-out de
 *   canal — o device permanece para transacional até o unregister).
 */
data class ConsentEvidence(
    val granted: Boolean,
    val capturedAtMillis: Long,
    val policyVersion: String? = null,
) {
    /** Serialização EXATA do contrato — nenhum campo extra (minimização). */
    fun toJson(): JSONObject = JSONObject()
        .put("granted", granted)
        .put("capturedAt", Iso8601.format(capturedAtMillis))
        .apply { policyVersion?.let { put("policyVersion", it) } }
}

/** Resultado de um `registerForPush` bem-sucedido (token só mascarado). */
data class RegistrationResult(
    val platform: DevicePlatform,
    val maskedToken: String,
    val contactRef: String?,
    val consent: ConsentEvidence,
    /** `true` quando a API respondeu 200 (token já conhecido — update); `false` para 201. */
    val updatedExisting: Boolean,
)

/** Etapas do opt-out (para reportar falha por passo). */
enum class OptOutStep { CONSENT_REVOKE, UNREGISTER }

/** Falha de UMA etapa do opt-out (o fluxo continua — best-effort). */
data class OptOutStepFailure(val step: OptOutStep, val reason: String)

/**
 * Resultado de `optOut()` — o método NÃO lança exceção de rede: opt-out é
 * requisito legal "fácil e gratuito", então o fluxo é best-effort e o
 * resultado reporta exatamente o que aconteceu.
 */
data class OptOutResult(
    val hadRegistration: Boolean,
    /** `true`/`false`; `null` = etapa não aplicável (sem contactRef). */
    val consentRevoked: Boolean?,
    /** `true` quando o device foi removido OU não havia o que remover. */
    val unregistered: Boolean,
    /** Estado local limpo. Em falha TOTAL de rede o SDK mantém o mínimo. */
    val stateCleared: Boolean,
    val failures: List<OptOutStepFailure> = emptyList(),
) {
    val fullyOptedOut: Boolean get() = failures.isEmpty() && stateCleared
}

/**
 * Evento pós-unregister automático quando o usuário revoga a permissão de
 * notificação no OS (cobrança honesta — o SDK JÁ removeu o device).
 */
data class PermissionRevocation(
    val unregistered: Boolean,
    val error: String? = null,
)

/**
 * Resultado da rotação de token FCM (`onNewToken`) — nova na 3.3: registra o
 * NOVO token (upsert, reafirmando consent quando há contactRef — idempotente
 * por transição no servidor) e faz unregister best-effort do antigo.
 */
data class TokenRotationResult(
    val changed: Boolean,
    val registered: Boolean,
    /** `null` quando não havia token antigo; `false` quando o unregister falhou. */
    val oldRemoved: Boolean?,
    val updatedExisting: Boolean,
)
