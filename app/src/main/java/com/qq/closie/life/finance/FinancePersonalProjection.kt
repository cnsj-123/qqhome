package com.qq.closie.life.finance

import java.math.BigInteger

/** Statistics interpret confirmed typed relationships, never import evidence or expected money. */
object FinancePersonalProjection {
    fun rows(snapshot: FinanceSnapshot, selected: List<FinanceLedgerRow>): List<FinanceLedgerRow> {
        val v4 = snapshot.v4 ?: return selected.filter { it.entry.statPolicy != FinanceStatPolicy.EXCLUDE }
        val events = v4.events.associateBy { it.id }
        val entries = snapshot.entries.associateBy { it.id }
        val links = v4.activeLinks.groupBy { it.entryId }
        val eventLinks = v4.activeLinks.groupBy { it.eventId }
        val ledgerById = FinanceProjection.ledger(snapshot).associateBy { it.entry.id }
        val shares = mutableMapOf<Pair<String, String>, Long>()
        for (event in v4.events.filter { it.voidedAt == null && it.nature in setOf(FinanceNature.SPLIT, FinanceNature.REIMBURSABLE) }) {
            val paymentLinks = eventLinks[event.id].orEmpty().filter {
                it.role in setOf(FinanceFlowRole.PAYMENT, FinanceFlowRole.FEE) &&
                    entries[it.entryId]?.voidedAt == null && entries[it.entryId]?.direction == FinanceDirection.OUTFLOW
            }.sortedBy { it.entryId }
            val total = paymentLinks.fold(BigInteger.ZERO) { sum, it -> sum + it.allocatedMinor.toBigInteger() }
            val share = event.personalShareMinor?.toBigInteger() ?: continue
            if (total.signum() == 0) continue
            var assigned = BigInteger.ZERO
            paymentLinks.forEachIndexed { i, link ->
                val part = if (i == paymentLinks.lastIndex) share - assigned else share * link.allocatedMinor.toBigInteger() / total
                assigned += part
                shares[event.id to link.entryId] = part.longValueExact()
            }
        }
        return selected.filter { it.transfer == null && it.entry.statPolicy != FinanceStatPolicy.EXCLUDE }.flatMap { row ->
            val allocations = links[row.entry.id].orEmpty()
            if (allocations.isEmpty()) return@flatMap listOf(row) // Existing F1 ordinary flows.
            allocations.mapNotNull { link ->
                val event = events[link.eventId]?.takeIf { it.voidedAt == null } ?: return@mapNotNull null
                val expense: Long? = when (event.nature) {
                    FinanceNature.PROXY_PURCHASE, FinanceNature.OTHER -> null
                    FinanceNature.SPLIT, FinanceNature.REIMBURSABLE -> shares[event.id to row.entry.id]
                    FinanceNature.PERSONAL, FinanceNature.STORED_VALUE -> when {
                        link.role in setOf(FinanceFlowRole.REFUND, FinanceFlowRole.REBATE, FinanceFlowRole.CASHBACK) -> -link.allocatedMinor
                        row.entry.direction == FinanceDirection.OUTFLOW -> link.allocatedMinor
                        link.role == FinanceFlowRole.OTHER -> return@mapNotNull row.copy(entry = row.entry.copy(amountMinor = link.allocatedMinor))
                        else -> null
                    }
                }
                expense?.let { amount ->
                    var attributed = row
                    if (link.role in setOf(FinanceFlowRole.REFUND, FinanceFlowRole.REBATE, FinanceFlowRole.CASHBACK)) {
                        val context = eventLinks[event.id].orEmpty()
                            .filter { it.role in setOf(FinanceFlowRole.PAYMENT, FinanceFlowRole.FEE) }
                            .mapNotNull { ledgerById[it.entryId] }
                        val categoryIds = context.map { it.entry.categoryId }.distinct()
                        attributed = if (context.isNotEmpty() && categoryIds.size == 1) row.copy(
                            entry = row.entry.copy(categoryId = categoryIds.single()), category = context.first().category)
                        else row.copy(entry = row.entry.copy(categoryId = null),
                            category = "调整待归属", categoryOverride = "调整待归属")
                        val tagSets = context.map { it.tags.toSet() }.distinct()
                        attributed = attributed.copy(tags = if (context.isNotEmpty() && tagSets.size == 1)
                            tagSets.single().sorted() else listOf("调整待归属"))
                    }
                    attributed.copy(entry = attributed.entry.copy(amountMinor = amount, direction = FinanceDirection.OUTFLOW))
                }
            }
        }
    }

    /** These are real flows including transfers and excluded-stat rows; never personal income. */
    fun realTotals(rows: List<FinanceLedgerRow>, accountId: String? = null): Map<String, FinanceTotals> = rows
        .flatMap { row -> if (row.transfer == null) listOf(row) else listOf(
            row.copy(transfer = null),
            row.copy(transfer = null, account = requireNotNull(row.targetAccount), entry = row.entry.copy(direction = FinanceDirection.INFLOW))
        ) }.filter { accountId == null || it.account.id == accountId }.groupBy { it.currencyCode }.mapValues { (_, group) ->
            fun sum(direction: FinanceDirection) = group.filter { it.entry.direction == direction }
                .fold(BigInteger.ZERO) { sum, it -> sum + it.entry.amountMinor.toBigInteger() }.longValueExact()
            FinanceTotals(sum(FinanceDirection.OUTFLOW), sum(FinanceDirection.INFLOW))
        }
}
