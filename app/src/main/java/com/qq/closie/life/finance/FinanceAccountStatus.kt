package com.qq.closie.life.finance

import java.time.YearMonth
import java.time.ZoneId

enum class FinanceTrust(val label:String) { RECONCILED("已核对"), DERIVED("从起点推算"),
    INCOMPLETE("资料不足"), INVALIDATED("核对已失效"), DIFFERENT("核对有差额") }
data class FinanceAccountStatus(val trust:FinanceTrust,val explanation:String,
    val conclusion:FinanceReconciliationResult?=null)
data class FinanceObservationInterval(val first:FinanceObservationEntity,val last:FinanceObservationEntity,
    val observedMovementMinor:Long,val bookMovementMinor:Long?,val unexplainedMinor:Long?)
data class FinanceCreditStatement(val range:FinanceDateRange,val dueAt:Long,val outflowMinor:Long,
    val inflowMinor:Long,val entries:List<FinanceEntryEntity>,val unknownPostingCount:Int)

/** Display confidence is derived from evidence; saving an observation never adjusts the cash book. */
object FinanceAccountStatusProjection {
    fun status(s:FinanceSnapshot,accountId:String):FinanceAccountStatus {
        val v=s.v16
        val observations=v?.observationRows.orEmpty().filter { it.accountId==accountId && it.voidedAt==null }.associateBy { it.id }
        val last=v?.reconciliationRows.orEmpty().filter { it.voidedAt==null && it.observationId in observations }
            .maxByOrNull { it.confirmationOrder }
        if(last==null) return FinanceAccountStatus(FinanceTrust.DERIVED,"余额由起点及真实流水推算，尚无已保存的核对结论。")
        val result=FinanceAccountProjection.reconcile(s,observations.getValue(last.observationId),last)
        return when {
            result.invalidated -> FinanceAccountStatus(FinanceTrust.INVALIDATED,"起点、流水或入账事实已改变，请重新核对。",result)
            result.bookMinor==null -> FinanceAccountStatus(FinanceTrust.INCOMPLETE,"当前时间口径缺少起点或确知入账日，无法得出差额。",result)
            result.differenceMinor!=0L -> FinanceAccountStatus(FinanceTrust.DIFFERENT,"观察余额与账面存在差额；没有生成调账流水。",result)
            else -> FinanceAccountStatus(FinanceTrust.RECONCILED,"仅代表所选观察时点与口径相符；之后余额仍由流水推算。",result)
        }
    }
    fun compare(s:FinanceSnapshot,first:FinanceObservationEntity,last:FinanceObservationEntity):FinanceObservationInterval {
        require(first.accountId==last.accountId && first.basis==last.basis && first.observedAt<last.observedAt)
        require(first.voidedAt==null && last.voidedAt==null)
        val a=FinanceAccountProjection.reconcile(s,first).bookMinor
        val b=FinanceAccountProjection.reconcile(s,last).bookMinor
        val observed=Math.subtractExact(last.amountMinor,first.amountMinor)
        val movement=if(a==null || b==null) null else Math.subtractExact(b,a)
        return FinanceObservationInterval(first,last,observed,movement,movement?.let { Math.subtractExact(observed,it) })
    }
    fun statement(s:FinanceSnapshot,accountId:String,closingMonth:YearMonth):FinanceCreditStatement {
        val v=requireNotNull(s.v16)
        val cycle=requireNotNull(v.creditCycleRows.singleOrNull { it.accountId==accountId && it.voidedAt==null })
        val zone=ZoneId.of(cycle.zoneId)
        fun close(month:YearMonth)=month.atDay(minOf(cycle.closingDay,month.lengthOfMonth()))
        val close=close(closingMonth)
        val start=close(closingMonth.minusMonths(1)).plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val end=close.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val dueMonth=if(closingMonth.atDay(minOf(cycle.dueDay,closingMonth.lengthOfMonth())).isAfter(close)) closingMonth else closingMonth.plusMonths(1)
        val due=dueMonth.atDay(minOf(cycle.dueDay,dueMonth.lengthOfMonth())).atStartOfDay(zone).toInstant().toEpochMilli()
        val timing=v.entryTimingRows.filter { it.voidedAt==null }.associateBy { it.entryId }
        val all=s.entries.filter { it.accountId==accountId && it.voidedAt==null }
        val known=all.filter { row -> timing[row.id]?.postedAt?.let { it>=start && it<end }==true }
        return FinanceCreditStatement(FinanceDateRange(start,end),due,
            exactSum(known.filter { it.direction==FinanceDirection.OUTFLOW }.map { it.amountMinor }),
            exactSum(known.filter { it.direction==FinanceDirection.INFLOW }.map { it.amountMinor }),known,
            all.count { timing[it.id]?.postedAt==null })
    }
}
