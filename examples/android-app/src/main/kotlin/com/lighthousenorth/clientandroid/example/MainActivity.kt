package com.lighthousenorth.clientandroid.example

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.lighthousenorth.clientandroid.LighthouseNorthAndroidClient
import com.lighthousenorth.clientandroid.core.LogLevel
import com.lighthousenorth.clientandroid.core.redactSecret
import com.lighthousenorth.clientandroid.errors.FirebaseNotConfiguredException
import com.lighthousenorth.clientandroid.errors.PushPermissionDeniedException
import com.lighthousenorth.clientandroid.http.HttpEngine
import com.lighthousenorth.clientandroid.http.HttpRequestData
import com.lighthousenorth.clientandroid.http.HttpResponseData
import com.lighthousenorth.clientandroid.push.FakeTokenProvider
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList

/**
 * App mínimo de integração com o SDK Android nativo (codinome) — evidência
 * central da fatia 3.3: instala/roda no emulador e demonstra o FLUXO LEGAL
 * completo (opt-in com consentimento → estado → opt-out fácil e gratuito).
 *
 * - TOKEN: [FakeTokenProvider] (o Firebase do host/dono ainda não existe —
 *   ação do dono; com Firebase real, o SDK usa FcmTokenProvider sozinho).
 * - TRANSPORTE: "fake" (demo offline, engine scriptado — zero rede) ou REAL
 *   apontando para a API local do compose pelo emulador (10.0.2.2:3000).
 */
class MainActivity : Activity() {

    private lateinit var appKeyField: EditText
    private lateinit var baseUrlField: EditText
    private lateinit var contactRefField: EditText
    private lateinit var fakeTransportSwitch: Switch
    private lateinit var statusView: TextView
    private lateinit var logView: TextView

    private var client: LighthouseNorthAndroidClient? = null

    private val logLines = CopyOnWriteArrayList<String>()

    /** Engine scriptado p/ demo offline — mesmas respostas da API real. */
    private var demoEngine: DemoEngine? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        appKeyField = findViewById(R.id.appKey)
        baseUrlField = findViewById(R.id.apiBaseUrl)
        contactRefField = findViewById(R.id.contactRef)
        fakeTransportSwitch = findViewById(R.id.fakeTransport)
        statusView = findViewById(R.id.status)
        logView = findViewById(R.id.log)

        baseUrlField.setText(DEFAULT_BASE_URL)
        fakeTransportSwitch.isChecked = true // demo offline é o default da evidência

        findViewById<Button>(R.id.register).setOnClickListener { registerForPush() }
        findViewById<Button>(R.id.optOut).setOnClickListener { optOut() }

        render()
    }

    // ------------------------------------------------------------------
    // Fluxos
    // ------------------------------------------------------------------

    private fun ensureClient(): LighthouseNorthAndroidClient {
        client?.let { return it }
        val appKey = appKeyField.text.toString().trim()
        if (appKey.isEmpty()) {
            throw com.lighthousenorth.clientandroid.errors.InvalidAppKeyException(
                "preencha a app key do painel do tenant (Apps > Chaves)",
            )
        }
        val logger = object : com.lighthousenorth.clientandroid.core.Logger {
            override fun log(level: LogLevel, event: String, fields: Map<String, Any?>) {
                logLines.add(0, "${level.name.take(4).padEnd(4)} $event ${fields.filterKeys { it != "hint" }}")
                runOnUiThread { render() }
            }
        }
        val demo = if (fakeTransportSwitch.isChecked) DemoEngine() else null
        demoEngine = demo
        return LighthouseNorthAndroidClient.init(
            this,
            appKey,
            apiBaseUrl = baseUrlField.text.toString().trim(),
            config = LighthouseNorthAndroidClient.Config(
                logger = logger,
                engine = demo,
                // FakeTokenProvider: sem Firebase do host, o token real não
                // existe (ação do dono). O resto do fluxo é o de produção.
                tokenProvider = FakeTokenProvider(),
                stateStore = null, // produção: Android Keystore
            ),
        ).also { client = it }
    }

    private fun registerForPush() {
        val created = runCatching { ensureClient() }
        val sdk = created.getOrElse { e -> show(e.toString(), error = true); return }
        val contactRef = contactRefField.text.toString().trim().takeIf { it.isNotEmpty() }
        sdk.registerForPush(this, contactRef = contactRef, policyVersion = POLICY_VERSION) {
            it.onSuccess { reg ->
                sdk.startHeartbeat()
                show(
                    "Device registrado (${if (reg.updatedExisting) "atualizado" else "novo"}) · " +
                        "token ${reg.maskedToken}",
                )
            }
            it.onFailure { e -> renderFailure(e) }
        }
    }

    private fun optOut() {
        val created = runCatching { ensureClient() }
        val sdk = created.getOrElse { e -> show(e.toString(), error = true); return }
        sdk.optOut { result ->
            result.onSuccess { r ->
                if (r.fullyOptedOut) {
                    show("Push desativado — consentimento revogado e device removido.")
                } else {
                    // Requisito legal: falha parcial/total NÃO pode parecer sucesso.
                    show(
                        "Opt-out INCOMPLETO (" +
                            r.failures.joinToString(", ") { it.step.name } +
                            ") — toque de novo para re-tentar.",
                        error = true,
                    )
                }
            }
            result.onFailure { e -> renderFailure(e) }
        }
    }

    private fun renderFailure(e: Throwable) {
        when (e) {
            is PushPermissionDeniedException ->
                show("Permissão de notificação negada — o device NÃO foi registrado.", error = true)
            is FirebaseNotConfiguredException ->
                show("Host sem Firebase — use o provider fake (dev) ou configure o Firebase.", error = true)
            else -> show(e.toString(), error = true)
        }
    }

    // ------------------------------------------------------------------
    // Render
    // ------------------------------------------------------------------

    private fun show(message: String, error: Boolean = false) {
        runOnUiThread {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            logLines.add(0, if (error) "!! $message" else ">> $message")
            render()
        }
    }

    private fun render() {
        val sdk = client
        val state = sdk?.currentState
        val status = buildString {
            appendLine("Inicializado: ${if (sdk != null) "sim" else "não"}")
            appendLine("Registrado: ${if (state?.token != null) "sim" else "não"}")
            appendLine("Heartbeat: ${if (sdk?.isHeartbeatRunning == true) "ativo" else "parado"}")
            appendLine("Token (mascarado): ${state?.token?.let { redactSecret(it) } ?: "—"}")
            append("contactRef: ${state?.contactRef ?: "—"}")
        }
        statusView.text = status
        logView.text = logLines.take(14).joinToString("\n")
    }

    // ------------------------------------------------------------------
    // Engine de demo (offline) — respostas uniformes como a API real
    // ------------------------------------------------------------------

    /**
     * DemoEngine: POST /v1/devices → 201 (novo) / 200 (seguido); refresh →
     * 200; unregister → 200. Registra tudo no log da UI. ZERO rede.
     */
    private inner class DemoEngine : HttpEngine {
        private var seenTokens = mutableSetOf<String>()

        override fun execute(request: HttpRequestData): HttpResponseData {
            val path = request.url.substringAfter("://").substringAfter("/")
            val body = request.body.takeIf { it.isNotEmpty() }
                ?.let { runCatching { JSONObject(it) }.getOrNull() }
            val token = body?.optString("token")?.takeIf { it.isNotEmpty() }
            val isNew = token != null && token !in seenTokens
            if (token != null) seenTokens += token

            // Android org.json não tem keySet() — keys() (Iterator) é API 1.
            val keys = body?.keys()?.asSequence()?.joinToString(",") ?: ""
            logLines.add(0, "→ POST /$path $keys")
            runOnUiThread { render() }

            val status = when {
                path == "v1/devices" && isNew -> 201
                else -> 200 // register conhecido/refresh/unregister: uniformes
            }
            return HttpResponseData(status, "{\"ok\":true}")
        }
    }

    companion object {
        /** 10.0.2.2 = loopback do HOST visto do emulador (API do compose). */
        private const val DEFAULT_BASE_URL = "http://10.0.2.2:3000"
        private const val POLICY_VERSION = "2026-09"
    }
}
