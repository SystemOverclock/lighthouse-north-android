package com.lighthousenorth.clientandroid

import com.google.firebase.messaging.FirebaseMessagingService

/**
 * Service OPCIONAL de rotação de token FCM — fecha a pendência "onNewToken"
 * da 3.2 (NATIVE_CONTRACT §6): o Firebase rotaciona o token do device e o
 * SDK re-registra automaticamente (upsert do novo + unregister best-effort
 * do antigo), sem depender de ação do app.
 *
 * Por que OPCIONAL e fora do manifest da library: o SDK não embute Firebase
 * (decisão 3.2) — declarar este service no manifest do SDK QUEBRARIA hosts
 * sem Firebase. O host COM Firebase o declara manualmente:
 *
 * ```xml
 * <service
 *     android:name="com.lighthousenorth.clientandroid.LighthouseNorthFirebaseMessagingService"
 *     android:exported="false">
 *     <intent-filter>
 *         <action android:name="com.google.firebase.MESSAGING_EVENT" />
 *     </intent-filter>
 * </service>
 * ```
 *
 * Hosts que JÁ têm seu próprio FirebaseMessagingService: encaminhem o token
 * com `LighthouseNorthAndroidClient.get().onNewToken(newToken) { ... }` — mesmo efeito.
 * (Este service NÃO intercepta mensagens: `onMessageReceived` não é tocado —
 * plain-text por construção, o OS exibe.)
 */
class LighthouseNorthFirebaseMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        LighthouseNorthAndroidClient.dispatchNewTokenFromService(token)
    }

    // onMessageReceived DELIBERADAMENTE não sobrescrito: o SDK não renderiza
    // nem inspeciona conteúdo de push (plain-text por construção — a entrega
    // visual é do OS/host).
}
