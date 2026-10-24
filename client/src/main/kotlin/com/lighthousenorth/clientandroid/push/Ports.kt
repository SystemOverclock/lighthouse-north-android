package com.lighthousenorth.clientandroid.push

import com.lighthousenorth.clientandroid.errors.LighthouseNorthException
import com.lighthousenorth.clientandroid.model.DevicePlatform

/**
 * Fonte de permissão + token push — TODO acesso a OS/Firebase passa por
 * esta interface, o que torna o SDK:
 *
 * - **testável** sem device/emulador (implementação fake em testes);
 * - **extensível**: um app hospedeiro que já usa Firebase implementa esta
 *   interface em ~6 linhas (ver README); o SDK não embute Firebase.
 */
interface TokenProvider {
    /**
     * Obtém o token push do app HOSPEDEIRO. Lança
     * [com.lighthousenorth.clientandroid.errors.FirebaseNotConfiguredException] quando
     * o host não tem Firebase — nunca crasha, sempre erro tipado.
     */
    @Throws(LighthouseNorthException::class)
    fun getToken(): String

    /** Plataforma atual para o registro na API (`android|ios|web`). */
    fun platform(): DevicePlatform
}

/** Provider de desenvolvimento/testes: sem OS, sem Firebase, sem rede. */
class FakeTokenProvider(
    var tokenValue: String? = "fake-fcm-token-0123456789abcdef",
) : TokenProvider {
    var tokenRequestCount = 0
        private set

    override fun getToken(): String {
        tokenRequestCount++
        val t = tokenValue ?: throw IllegalStateException("FakeTokenProvider.tokenValue nulo")
        if (t.isEmpty()) throw IllegalStateException("FakeTokenProvider.tokenValue vazio")
        return t // validação de comprimento (16..4096) é papel do CORE, não do provider
    }

    override fun platform(): DevicePlatform = DevicePlatform.ANDROID
}
