package com.lighthousenorth.clientandroid.permission

/**
 * Acesso à permissão de notificação do OS — port injetável (testes usam fake).
 *
 * [request] é BLOQUEANTE até o usuário responder ao dialog do OS (ou falhar):
 * o facade do SDK chama em thread própria, nunca na main thread.
 */
interface PermissionGateway {
    /** Permissão atual, SEM pedir (informativa; falha ⇒ true, ver contrato). */
    fun isGranted(): Boolean

    /**
     * Pede a permissão (dialog do OS) e bloqueia até a resposta.
     * Retorna `true` (concedida) / `false` (negada — não é erro).
     * Implementação Android lança
     * [com.lighthousenorth.clientandroid.errors.LighthouseNorthNativeException] `no_activity`
     * quando não há Activity para mostrar o dialog.
     */
    fun request(): Boolean
}

/** Gateway fake para testes/dev. */
class FakePermissionGateway(
    var granted: Boolean = true,
) : PermissionGateway {
    var requestCount = 0
        private set

    override fun isGranted(): Boolean = granted

    override fun request(): Boolean {
        requestCount++
        return granted
    }
}
