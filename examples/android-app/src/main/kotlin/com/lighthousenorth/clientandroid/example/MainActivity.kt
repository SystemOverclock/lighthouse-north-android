// Exemplo mínimo de integração com o SDK Lighthouse North para Android.
//
// O SDK precisa de 3 coisas do seu app:
//  1. Inicialização com a app key (do painel, nunca hardcoded)
//  2. Registro de push a partir de uma ação do usuário
//  3. Um botão de descadastro sempre visível nas configurações
package com.lighthousenorth.clientandroid.example

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.lighthousenorth.clientandroid.LighthouseNorthAndroidClient

class MainActivity : Activity() {

    private lateinit var client: LighthouseNorthAndroidClient
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Layout simples (em produção use ViewBinding/Compose)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 64, 32, 32)
        }
        status = TextView(this).apply { text = "Iniciando..." }
        val registerButton = Button(this).apply { text = "Ativar notificações" }
        val optOutButton = Button(this).apply { text = "Descadastrar" }
        layout.addView(status)
        layout.addView(registerButton)
        layout.addView(optOutButton)
        setContentView(layout)

        // 1. INICIALIZAR — substitua pela sua app key (BuildConfig ou env)
        client = LighthouseNorthAndroidClient.getInstance(this)
        client.init(
            appKey = "ak_SUA_KEY_AQUI",
            apiBaseUrl = "https://lighthousenorth.com.br",
        ) { result ->
            status.text = if (result.isSuccess) "SDK pronto" else "Erro: ${result.exceptionOrNull()}"
        }

        // 2. REGISTRAR — a partir do clique (NUNCA no onCreate)
        registerButton.setOnClickListener {
            // Em produção: obtenha o token FCM do seu Firebase
            client.registerForPush(
                fcmToken = "SEU_TOKEN_FCM",
                contactRef = "meu-user-id",
                policyVersion = "2026-09",
            ) { result ->
                if (result.isSuccess) {
                    client.startHeartbeat()
                    status.text = "Push ativo"
                } else {
                    status.text = "Erro: ${result.exceptionOrNull()}"
                }
            }
        }

        // 3. DESCADASTRAR — sempre disponível, nunca falha
        optOutButton.setOnClickListener {
            client.optOut { result ->
                status.text = "Descadastrado"
                Toast.makeText(this, "Device removido da plataforma", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
