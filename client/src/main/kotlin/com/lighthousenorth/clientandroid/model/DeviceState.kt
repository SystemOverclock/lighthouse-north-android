package com.lighthousenorth.clientandroid.model

import com.lighthousenorth.clientandroid.core.Iso8601
import org.json.JSONException
import org.json.JSONObject

/**
 * Estado local do SDK — o MÍNIMO para heartbeat e opt-out funcionarem.
 * Nada além desses campos é persistido (minimização LGPD por construção).
 * Formato idêntico ao do SDK Flutter (docs/NATIVE_CONTRACT.md §5) — os SDKs
 * nativo e embutido conseguem ler o estado um do outro se coexistirem.
 */
data class DeviceState(
    val platform: DevicePlatform? = null,
    val token: String? = null,
    val contactRef: String? = null,
    val policyVersion: String? = null,
    val registeredAtMillis: Long? = null,
    val heartbeatActive: Boolean = false,
    val heartbeatIntervalMs: Long? = null,
)

/**
 * Codec do JSON de estado (§5 do contrato) — PURO (sem Android), testado em
 * JVM. Estado corrompido/versão desconhecida ⇒ `null` (descartar e recomeçar
 * limpo: o servidor é a fonte da verdade, o registro é upsert).
 */
object StateCodec {
    const val VERSION = 1

    fun toJsonString(state: DeviceState): String {
        val json = JSONObject()
            .put("v", VERSION)
            .put("heartbeatActive", state.heartbeatActive)
        state.platform?.let { json.put("platform", it.wire) }
        state.token?.let { json.put("token", it) }
        state.contactRef?.let { json.put("contactRef", it) }
        state.policyVersion?.let { json.put("policyVersion", it) }
        state.registeredAtMillis?.let { json.put("registeredAt", Iso8601.format(it)) }
        state.heartbeatIntervalMs?.let { json.put("heartbeatIntervalMs", it) }
        return json.toString()
    }

    fun fromJsonString(raw: String): DeviceState? {
        return try {
            val decoded = JSONObject(raw)
            // Tipagem ESTRITA (paridade com jsonDecode do Dart): org.json
            // COERCE números para String em optString — aqui só String é String.
            val versionValue = decoded.opt("v")
            if (versionValue !is Int || versionValue != VERSION) return null
            val platformWire = decoded.opt("platform") as? String
            val platform = when (platformWire) {
                null, "" -> null
                else -> DevicePlatform.fromWire(platformWire) ?: return null
            }
            DeviceState(
                platform = platform,
                token = (decoded.opt("token") as? String)?.takeIf { it.isNotEmpty() },
                contactRef = (decoded.opt("contactRef") as? String)?.takeIf { it.isNotEmpty() },
                policyVersion = (decoded.opt("policyVersion") as? String)?.takeIf { it.isNotEmpty() },
                registeredAtMillis = (decoded.opt("registeredAt") as? String)
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { Iso8601.parse(it) },
                heartbeatActive = decoded.opt("heartbeatActive") == true,
                heartbeatIntervalMs = when (val interval = decoded.opt("heartbeatIntervalMs")) {
                    is Int -> interval.toLong()
                    is Long -> interval
                    else -> null
                },
            )
        } catch (_: JSONException) {
            null
        }
    }
}
