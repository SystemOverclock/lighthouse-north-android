package com.lighthousenorth.clientandroid.storage

import android.content.Context
import android.util.Base64
import com.lighthousenorth.clientandroid.core.LogLevel
import com.lighthousenorth.clientandroid.core.Logger
import com.lighthousenorth.clientandroid.errors.LighthouseNorthStorageException
import com.lighthousenorth.clientandroid.model.DeviceState
import com.lighthousenorth.clientandroid.model.StateCodec
import java.security.KeyStore
import java.security.ProviderException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Persistência do estado com **Android Keystore** — fecha a pendência P2 da
 * 3.2 (estado plaintext em SharedPreferences).
 *
 * Design:
 * - chave AES-256-GCM não exportável no AndroidKeyStore (alias por app);
 * - cada write gera IV novo (nunca reuso de IV com a mesma chave — GCM);
 * - formato no SharedPreferences("lighthouse_north_client")["state_enc"]:
 *   `base64(iv[12] || ciphertext+tag)`;
 * - MIGRAÇÃO transparente do plaintext legado ["state"] (formato escrito
 *   pelos nativos embutidos da 3.2): lê, regrava cifrado, REMOVE o plaintext;
 * - estado corrompido (AEADBadTag / JSON inválido / versão desconhecida) ⇒
 *   `null` + log — o SDK recomeça limpo (contrato §5);
 * - Keystore quebrado (KeyStoreException/ProviderException) ⇒
 *   [LighthouseNorthStorageException] — FAIL CLOSED: nunca degrada para plaintext.
 */
class KeystoreStateStore(
    context: Context,
    private val logger: Logger,
) : StateStore {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun read(): DeviceState? {
        // 1) Blob cifrado (formato atual).
        val encrypted = prefs.getString(STATE_KEY_ENC, null)
        if (encrypted != null) {
            return readEncrypted(encrypted)
        }
        // 2) Legado plaintext (nativos da 3.2) — migra para cifrado e remove.
        val legacy = prefs.getString(STATE_KEY_LEGACY, null)
        if (legacy != null) {
            val state = StateCodec.fromJsonString(legacy)
            if (state == null) {
                logger.log(LogLevel.WARNING, "storage.state_discarded", mapOf("reason" to "legado corrompido"))
            }
            if (state != null) {
                runCatching { write(state) }
                    .onFailure {
                        logger.log(
                            LogLevel.WARNING,
                            "storage.legacy_migration_failed",
                            mapOf("error" to it.toString()),
                        )
                        return state // plaintext legível permanece válido nesta leitura
                    }
            }
            prefs.edit().remove(STATE_KEY_LEGACY).apply()
            return state
        }
        return null
    }

    override fun write(state: DeviceState) {
        val plain = StateCodec.toJsonString(state).toByteArray(Charsets.UTF_8)
        val blob = try {
            KeystoreCipher.encrypt(plain)
        } catch (e: GeneralSecurityOrKeystoreFailure) {
            throw LighthouseNorthStorageException(
                "keystore indisponível para gravar o estado local (fail closed — sem fallback plaintext)",
                mapOf("error" to e.toString()),
            )
        }
        val ok = prefs.edit().putString(STATE_KEY_ENC, encode(blob)).commit()
        if (!ok) {
            throw LighthouseNorthStorageException("falha ao persistir o estado local (commit)")
        }
    }

    override fun clear() {
        val ok = prefs.edit().remove(STATE_KEY_ENC).remove(STATE_KEY_LEGACY).commit()
        if (!ok) {
            throw LighthouseNorthStorageException("falha ao limpar o estado local (commit)")
        }
    }

    private fun readEncrypted(encoded: String): DeviceState? {
        val blob = try {
            decode(encoded)
        } catch (_: IllegalArgumentException) {
            null
        }
        if (blob == null) {
            logger.log(LogLevel.WARNING, "storage.state_discarded", mapOf("reason" to "base64 inválido"))
            return null
        }
        val plain = try {
            KeystoreCipher.decrypt(blob)
        } catch (e: GeneralSecurityOrKeystoreFailure) {
            throw LighthouseNorthStorageException(
                "keystore indisponível para ler o estado local (fail closed)",
                mapOf("error" to e.toString()),
            )
        } catch (_: AEADCorrupt) {
            logger.log(LogLevel.WARNING, "storage.state_discarded", mapOf("reason" to "cifra/tag inválida"))
            return null
        }
        val raw = String(plain, Charsets.UTF_8)
        val state = StateCodec.fromJsonString(raw)
        if (state == null) {
            logger.log(LogLevel.WARNING, "storage.state_discarded", mapOf("reason" to "corrompido/versão desconhecida"))
        }
        return state
    }

    private fun encode(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.NO_WRAP)

    private fun decode(text: String): ByteArray =
        Base64.decode(text, Base64.NO_WRAP)

    companion object {
        private const val PREFS_NAME = "lighthouse_north_client"
        private const val STATE_KEY_ENC = "state_enc"
        private const val STATE_KEY_LEGACY = "state" // escrito pelos nativos 3.2
    }
}

// ---------------------------------------------------------------------------
// Camada criptográfica isolada — erros distintos para fail-closed vs corrupt
// ---------------------------------------------------------------------------

internal class GeneralSecurityOrKeystoreFailure(cause: Throwable) : Exception(cause)
internal class AEADCorrupt : Exception()

internal object KeystoreCipher {
    private const val KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "lighthouse_north_client_state_v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_LENGTH = 12 // AndroidKeyStore AES/GCM sempre emite 12
    private const val TAG_BITS = 128

    fun encrypt(plain: ByteArray): ByteArray {
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            require(cipher.iv.size == IV_LENGTH) { "IV inesperado do Keystore" }
            cipher.doFinal(plain).let { ciphertext -> cipher.iv + ciphertext }
        } catch (e: java.security.GeneralSecurityException) {
            throw GeneralSecurityOrKeystoreFailure(e)
        } catch (e: ProviderException) {
            throw GeneralSecurityOrKeystoreFailure(e)
        }
    }

    fun decrypt(blob: ByteArray): ByteArray {
        if (blob.size <= IV_LENGTH) throw AEADCorrupt()
        return try {
            val iv = blob.copyOfRange(0, IV_LENGTH)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, iv))
            cipher.doFinal(blob, IV_LENGTH, blob.size - IV_LENGTH)
        } catch (e: javax.crypto.AEADBadTagException) {
            throw AEADCorrupt()
        } catch (e: java.security.GeneralSecurityException) {
            throw GeneralSecurityOrKeystoreFailure(e)
        } catch (e: ProviderException) {
            throw GeneralSecurityOrKeystoreFailure(e)
        }
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE)
        keyStore.load(null)
        (keyStore.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance("AES", KEYSTORE)
        generator.init(
            android.security.keystore.KeyGenParameterSpec.Builder(
                ALIAS,
                android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or
                    android.security.keystore.KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }
}
