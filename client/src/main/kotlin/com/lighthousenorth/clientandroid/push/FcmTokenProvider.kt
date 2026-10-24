package com.lighthousenorth.clientandroid.push

import com.google.android.gms.tasks.Tasks
import com.google.firebase.messaging.FirebaseMessaging
import com.lighthousenorth.clientandroid.errors.FirebaseNotConfiguredException
import com.lighthousenorth.clientandroid.errors.LighthouseNorthException
import com.lighthousenorth.clientandroid.errors.PushTokenUnavailableException
import com.lighthousenorth.clientandroid.model.DevicePlatform
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Token FCM do app HOSPEDEIRO — o SDK NÃO embute Firebase (decisão 3.2):
 * `firebase-messaging` entra como `compileOnly`; hosts SEM Firebase recebem o
 * erro tipado [FirebaseNotConfiguredException] (paridade exata com o plugin
 * embutido do client-flutter).
 *
 * Bloqueia pela task (Tasks.await com timeout) — roda na thread do SDK.
 */
class FcmTokenProvider : TokenProvider {

    override fun getToken(): String {
        return try {
            val task = FirebaseMessaging.getInstance().token
            val token = Tasks.await(task, TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (token.isNullOrEmpty()) {
                throw PushTokenUnavailableException("FirebaseMessaging.getToken devolveu token nulo")
            }
            token
        } catch (e: NoClassDefFoundError) {
            throw FirebaseNotConfiguredException(
                "app sem firebase-messaging — adicione o Firebase do host (ver README do SDK)",
            )
        } catch (e: IllegalStateException) {
            throw FirebaseNotConfiguredException(
                "FirebaseApp não inicializado no host (faltando google-services.json/config): ${e.message}",
            )
        } catch (e: ExecutionException) {
            throw PushTokenUnavailableException(
                "FirebaseMessaging.getToken falhou: ${e.cause?.message ?: e.message}",
            )
        } catch (e: TimeoutException) {
            throw PushTokenUnavailableException("timeout aguardando o token FCM (${TIMEOUT_SECONDS}s)")
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw PushTokenUnavailableException("interrupção aguardando o token FCM")
        }
    }

    override fun platform(): DevicePlatform = DevicePlatform.ANDROID

    companion object {
        private const val TIMEOUT_SECONDS = 15L
    }
}
