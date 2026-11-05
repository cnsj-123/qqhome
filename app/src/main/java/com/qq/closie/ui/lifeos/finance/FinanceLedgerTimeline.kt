package com.qq.closie.ui.lifeos.finance

import com.qq.closie.life.finance.*
import java.time.Instant
import java.time.ZoneId

/** A refund or settlement represents its cash date, never the original economic event. */
internal fun unrepresentedEconomicCosts(
    costs: List<PersonalCostRow>,
    snapshot: FinanceSnapshot,
    displayedRows: List<FinanceLedgerRow>,
    economicDates: Map<String, Long>,
    zone: ZoneId = ZoneId.systemDefault()
): List<PersonalCostRow> {
    fun day(time: Long) = Instant.ofEpochMilli(time).atZone(zone).toLocalDate()
    val payments = displayedRows.filter {
        it.transfer == null && it.entry.voidedAt == null && it.entry.direction == FinanceDirection.OUTFLOW
    }.associateBy { it.entry.id }
    val represented = snapshot.v4?.activeLinks.orEmpty().mapNotNull { link ->
        if (link.role != FinanceFlowRole.PAYMENT && link.role != FinanceFlowRole.FEE) return@mapNotNull null
        val row = payments[link.entryId] ?: return@mapNotNull null
        link.eventId to day(economicDates[row.entry.id] ?: row.entry.occurredAt)
    }.toSet()
    return costs.filter { it.entryId == null && (it.eventId to day(it.occurredAt)) !in represented }
}
