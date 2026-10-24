package com.lighthousenorth.clientandroid.storage

import com.lighthousenorth.clientandroid.errors.LighthouseNorthStorageException
import com.lighthousenorth.clientandroid.model.DeviceState

/**
 * Persistência do estado local. Implementação Android:
 * [com.lighthousenorth.clientandroid.storage.KeystoreStateStore] (Android Keystore
 * AES-256-GCM — o estado NUNCA fica em plaintext no SharedPreferences,
 * fechando a pendência P2 da 3.2).
 */
interface StateStore {
    /** `null` = sem estado OU estado corrompido (descartar e recomeçar). */
    @Throws(LighthouseNorthStorageException::class)
    fun read(): DeviceState?

    @Throws(LighthouseNorthStorageException::class)
    fun write(state: DeviceState)

    @Throws(LighthouseNorthStorageException::class)
    fun clear()
}

/** Store em memória para testes. */
class InMemoryStateStore : StateStore {
    var state: DeviceState? = null

    override fun read(): DeviceState? = state

    override fun write(state: DeviceState) {
        this.state = state
    }

    override fun clear() {
        state = null
    }
}
