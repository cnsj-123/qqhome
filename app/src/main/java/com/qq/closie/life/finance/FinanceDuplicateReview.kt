package com.qq.closie.life.finance

/** Weak matches are review hints ONLY. They never merge evidence or modify a posted transaction. */
object FinanceDuplicateReview {
    fun candidates(id: String, kind: FinanceProposalKind, amount: Long, occurredAt: Long,
        proposals: List<FinanceProposalEntity>, snapshot: FinanceSnapshot): Set<String> {
        fun nearby(time: Long) = java.math.BigInteger.valueOf(time).subtract(java.math.BigInteger.valueOf(occurredAt))
            .abs() <= java.math.BigInteger.valueOf(5 * 60 * 1000L)
        val identity = proposals.find { it.id == id }?.let(FinanceEvidenceOrigin::strongReference)
        val other = proposals.filter { it.id != id && it.status !in setOf(FinanceProposalStatus.REJECTED, FinanceProposalStatus.STALE) &&
            (identity == null || FinanceEvidenceOrigin.strongReference(it) != identity) &&
            it.kind == kind && it.amountMinor == amount && nearby(it.occurredAt) }
        val entries = snapshot.entries.filter { it.voidedAt == null && it.amountMinor == amount && nearby(it.occurredAt) &&
            snapshot.accounts.any { a -> a.id == it.accountId && a.currencyCode == "CNY" } &&
            (kind == FinanceProposalKind.TRANSFER || (it.direction == FinanceDirection.INFLOW) == (kind == FinanceProposalKind.INCOME)) }
        // Revisions invalidate an acknowledgement when the underlying candidate changes.
        return other.map { "proposal:${it.id}:${it.revision}" }.toSet() + entries.map { "entry:${it.id}:${it.revision}" }
    }
}
