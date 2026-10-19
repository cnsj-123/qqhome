package com.qq.closie.life.finance

data class FinanceObligationRow(val obligation: FinanceObligationEntity, val settledMinor: Long,
    val nonCashResolvedMinor: Long, val openMinor: Long, val cashEvidenceComplete: Boolean)

object FinanceObligationProjection {
    fun rows(s: FinanceSnapshot): List<FinanceObligationRow> {
        val v = s.v16 ?: return emptyList()
        val settlements = v.settlementRows.filter { it.voidedAt == null }.groupBy { it.obligationId }
        val resolutions = v.resolutionRows.filter { it.voidedAt == null }.groupBy { it.obligationId }
        return v.obligationRows.filter { it.voidedAt == null }.map {
            val cash = exactSum(settlements[it.id].orEmpty().map { row -> row.amountMinor })
            val nonCash = exactSum(resolutions[it.id].orEmpty().map { row -> row.amountMinor })
            FinanceObligationRow(it, cash, nonCash, Math.subtractExact(Math.subtractExact(it.amountMinor, cash), nonCash),
                cash == it.amountMinor && resolutions[it.id].isNullOrEmpty())
        }
    }
}
