package com.qq.closie.life.finance

import java.math.BigInteger

enum class FinanceAdjustmentIssue(val label: String) {
    NO_ACTIVE_PAYMENT_COST("没有可计入个人成本的有效付款"),
    EXCEEDS_PAYMENT_COST("累计调整超过有效付款成本")
}

/** Read-only unresolved semantics, not income or a persisted balance. Amount is the real allocation. */
data class FinanceUnassignedAdjustment(val linkId: String, val eventId: String, val row: FinanceLedgerRow,
    val role: FinanceFlowRole, val amountMinor: Long, val issue: FinanceAdjustmentIssue)

/** Statistics interpret confirmed typed relationships, never import evidence or expected money. */
object FinancePersonalProjection {
    private val adjustmentRoles = setOf(FinanceFlowRole.REFUND, FinanceFlowRole.REBATE, FinanceFlowRole.CASHBACK)

    private fun activePayment(link: FinanceEventEntryLinkEntity, ledger: Map<String, FinanceLedgerRow>,
        currency: String): FinanceLedgerRow? = ledger[link.entryId]?.takeIf {
        link.role == FinanceFlowRole.PAYMENT && link.allocatedMinor > 0 && it.transfer == null &&
            it.entry.direction == FinanceDirection.OUTFLOW && it.currencyCode == currency
    }

    /** Evaluate the entire event, even for a filtered period. Never choose or split adjustments by order. */
    private fun adjustmentIssues(v4: FinanceV4Snapshot, ledger: Map<String, FinanceLedgerRow>): Map<String, FinanceAdjustmentIssue> {
        val byEvent = v4.activeLinks.groupBy { it.eventId }
        return buildMap {
            v4.events.filter { it.voidedAt == null && it.nature in setOf(FinanceNature.PERSONAL, FinanceNature.STORED_VALUE) }.forEach { event ->
                val links = byEvent[event.id].orEmpty()
                val adjustments = links.filter { link -> link.role in adjustmentRoles &&
                    ledger[link.entryId]?.let { it.transfer == null && it.entry.direction == FinanceDirection.INFLOW } == true }
                if (adjustments.isEmpty()) return@forEach
                val cost = links.filter { activePayment(it, ledger, event.currencyCode)?.entry?.statPolicy == FinanceStatPolicy.INCLUDE }
                    .fold(BigInteger.ZERO) { sum, link -> sum + link.allocatedMinor.toBigInteger() }
                val adjusted = adjustments.fold(BigInteger.ZERO) { sum, link -> sum + link.allocatedMinor.toBigInteger() }
                when {
                    cost.signum() == 0 -> put(event.id, FinanceAdjustmentIssue.NO_ACTIVE_PAYMENT_COST)
                    adjusted > cost -> put(event.id, FinanceAdjustmentIssue.EXCEEDS_PAYMENT_COST)
                }
            }
        }
    }

    /** Unsafe event adjustments stay visible, wholly unresolved; their real account flows remain intact. */
    fun unassignedAdjustments(snapshot: FinanceSnapshot, selected: List<FinanceLedgerRow>): List<FinanceUnassignedAdjustment> {
        val v4 = snapshot.v4 ?: return emptyList()
        val issues = adjustmentIssues(v4, FinanceProjection.ledger(snapshot).associateBy { it.entry.id })
        val links = v4.activeLinks.groupBy { it.entryId }
        return selected.filter { it.transfer == null && it.entry.statPolicy != FinanceStatPolicy.EXCLUDE }.flatMap { row ->
            links[row.entry.id].orEmpty().filter { it.role in adjustmentRoles }.mapNotNull { link ->
                issues[link.eventId]?.let { FinanceUnassignedAdjustment(link.id, link.eventId, row, link.role, link.allocatedMinor, it) }
            }
        }
    }

    fun rows(snapshot: FinanceSnapshot, selected: List<FinanceLedgerRow>): List<FinanceLedgerRow> {
        val v4 = snapshot.v4 ?: return selected.filter { it.entry.statPolicy != FinanceStatPolicy.EXCLUDE }
        val events = v4.events.associateBy { it.id }
        val entries = snapshot.entries.associateBy { it.id }
        val links = v4.activeLinks.groupBy { it.entryId }
        val eventLinks = v4.activeLinks.groupBy { it.eventId }
        val ledgerById = FinanceProjection.ledger(snapshot).associateBy { it.entry.id }
        val issues = adjustmentIssues(v4, ledgerById)
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
                        link.role in adjustmentRoles -> if (event.id in issues) null else -link.allocatedMinor
                        row.entry.direction == FinanceDirection.OUTFLOW -> link.allocatedMinor
                        link.role == FinanceFlowRole.OTHER -> return@mapNotNull row.copy(entry = row.entry.copy(amountMinor = link.allocatedMinor))
                        else -> null
                    }
                }
                expense?.let { amount ->
                    var attributed = row
                    if (link.role in adjustmentRoles) {
                        val context = eventLinks[event.id].orEmpty()
                            .mapNotNull { activePayment(it, ledgerById, event.currencyCode) }
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
