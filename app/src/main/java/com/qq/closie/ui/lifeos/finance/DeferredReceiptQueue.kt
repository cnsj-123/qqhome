package com.qq.closie.ui.lifeos.finance

import kotlinx.coroutines.CancellationException

/** Main-thread presentation queue only. Dropped callbacks never delete the durable inbox candidate. */
internal class DeferredReceiptQueue(private val limit: Int = 32) {
    private data class Pending(val owner: Any, val eligible: () -> Boolean, val show: suspend () -> Boolean)
    private val pending = linkedMapOf<String, Pending>()
    private var advancing = false
    init { require(limit > 0) }
    fun defer(id: String, owner: Any, eligible: () -> Boolean, show: suspend () -> Boolean) {
        pending[id] = Pending(owner, eligible, show)
        while (pending.size > limit) pending.remove(pending.keys.first())
    }
    fun removeOwner(owner: Any) { pending.entries.removeAll { it.value.owner === owner } }
    suspend fun advance() {
        if (advancing) return
        advancing = true
        try {
            while (pending.isNotEmpty()) {
                val key = pending.keys.first()
                val next = pending.remove(key) ?: continue
                try { if (next.eligible() && next.show()) return }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { /* Inbox and notification remain available. */ }
            }
        } finally { advancing = false }
    }
}

internal object FinanceReceiptQueue { val pending = DeferredReceiptQueue() }
