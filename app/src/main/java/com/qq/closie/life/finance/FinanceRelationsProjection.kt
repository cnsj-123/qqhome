package com.qq.closie.life.finance

import java.math.BigInteger

/** Derived funds management only. Gross payments and each real return remain separate canonical entries. */
data class FinanceFundsMatter(
    val event: FinanceEventEntity, val paidMinor: Long, val feeMinor: Long,
    val returns: Map<FinanceFlowRole, Long>, val pendingMinor: Long?, val differenceMinor: Long,
    val accounts: List<String>, val rows: List<FinanceLedgerRow>, val pendingIsUpperBound: Boolean
) {
    val completed get() = pendingMinor == 0L
}

object FinanceRelationsProjection {
    val natures = setOf(FinanceNature.REIMBURSABLE, FinanceNature.PROXY_PURCHASE, FinanceNature.SPLIT)
    private val returnRoles = setOf(FinanceFlowRole.REFUND, FinanceFlowRole.REIMBURSEMENT,
        FinanceFlowRole.SETTLEMENT, FinanceFlowRole.REBATE, FinanceFlowRole.CASHBACK)

    fun matters(snapshot: FinanceSnapshot): List<FinanceFundsMatter> {
        val v4 = snapshot.v4 ?: return emptyList()
        val ledger = FinanceProjection.ledger(snapshot).filter { it.transfer == null }.associateBy { it.entry.id }
        val byEvent = v4.activeLinks.groupBy { it.eventId }
        val expected = v4.expected.filter { it.cancelledAt == null }.groupBy { it.eventId }
        val expectedEver=v4.expected.map {it.eventId}.toSet()
        val obligations=FinanceObligationProjection.rows(snapshot).groupBy {it.obligation.eventId}
        return v4.events.filter { it.voidedAt == null && it.nature in natures }.map { event ->
            val links = byEvent[event.id].orEmpty().filter { link ->
                ledger[link.entryId]?.let { it.entry.voidedAt == null && it.currencyCode == event.currencyCode } == true
            }
            fun sum(role: FinanceFlowRole, direction: FinanceDirection) = links.filter {
                it.role == role && ledger.getValue(it.entryId).entry.direction == direction
            }.fold(BigInteger.ZERO) { total, link -> total + link.allocatedMinor.toBigInteger() }
            val paid = sum(FinanceFlowRole.PAYMENT, FinanceDirection.OUTFLOW)
            val fee = sum(FinanceFlowRole.FEE, FinanceDirection.OUTFLOW)
            val returns = returnRoles.associateWith { sum(it, FinanceDirection.INFLOW) }
            val received = returns.values.fold(BigInteger.ZERO, BigInteger::add)
            val outflow = links.filter { ledger.getValue(it.entryId).entry.direction == FinanceDirection.OUTFLOW }
                .fold(BigInteger.ZERO) { total, link -> total + link.allocatedMinor.toBigInteger() }
            val inflow = links.filter { ledger.getValue(it.entryId).entry.direction == FinanceDirection.INFLOW }
                .fold(BigInteger.ZERO) { total, link -> total + link.allocatedMinor.toBigInteger() }
            val plans = expected[event.id].orEmpty()
            val remaining = if(!obligations[event.id].isNullOrEmpty()) exactSum(obligations.getValue(event.id).map {it.openMinor}).toBigInteger()
            else if (plans.any { it.amountMinor == null }) null else if (plans.isNotEmpty()) {
                // Expected money is a target, never an entry. Only the matching role fulfils it.
                plans.groupBy { it.role }.entries.fold(BigInteger.ZERO) { total, (role, flows) ->
                    val target = flows.fold(BigInteger.ZERO) { value, flow -> value + flow.amountMinor!!.toBigInteger() }
                    val direction = if (role in returnRoles) FinanceDirection.INFLOW else FinanceDirection.OUTFLOW
                    total + (target - sum(role, direction)).max(BigInteger.ZERO)
                }
            } else if(event.id in expectedEver) BigInteger.ZERO
            else (paid + fee - (event.personalShareMinor ?: 0L).toBigInteger() - received).max(BigInteger.ZERO)
            val rows = links.mapNotNull { ledger[it.entryId] }.distinctBy { it.entry.id }.sortedByDescending { it.entry.occurredAt }
            FinanceFundsMatter(event, paid.longValueExact(), fee.longValueExact(), returns.mapValues { it.value.longValueExact() },
                remaining?.longValueExact(), (inflow - outflow).longValueExact(), rows.map { it.account.name }.distinct(), rows,
                plans.isEmpty() && event.id !in expectedEver && obligations[event.id].isNullOrEmpty() &&
                    event.personalShareMinor == null && event.nature in setOf(FinanceNature.SPLIT, FinanceNature.REIMBURSABLE))
        }.sortedByDescending { it.event.occurredAt }
    }
}
