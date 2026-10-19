package com.qq.closie.life.finance

import java.time.LocalDate
import java.time.ZoneId

/** Original civil date and the zone/range actually confirmed, independent of future device zones. */
data class FinanceCivilDate private constructor(val localDate:String,val zoneId:String,
    val startInclusive:Long,val endExclusive:Long,val basisVersion:Int) {
    companion object {
        const val LOCAL_DAY_AT_CONFIRMATION=1
        fun capture(date:LocalDate,zone:ZoneId):FinanceCivilDate {
            val start=date.atStartOfDay(zone).toInstant().toEpochMilli()
            val end=date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            require(end>start) {"所选时区不存在这个日历日"}
            return FinanceCivilDate(date.toString(),zone.id,start,end,LOCAL_DAY_AT_CONFIRMATION)
        }
        fun stored(date:String?,zone:String?,start:Long?,end:Long?,version:Int?):FinanceCivilDate? {
            if(date==null || zone==null || start==null || end==null || version!=LOCAL_DAY_AT_CONFIRMATION || end<=start) return null
            return runCatching {
                require(LocalDate.parse(date).toString()==date)
                ZoneId.of(zone) // Validate the identifier, never consult the current device zone.
                // The recorded interval is authoritative. Do not reinterpret it using newer tzdb rules.
                FinanceCivilDate(date,zone,start,end,LOCAL_DAY_AT_CONFIRMATION)
            }.getOrNull()
        }
        fun validate(precision:FinanceTimePrecision,at:Long?,date:String?,zone:String?,start:Long?,end:Long?,version:Int?) {
            val any=listOf(date,zone,start,end,version).any {it!=null}
            if(!any) return // Old DATE archives have no recoverable civil-date evidence; remain unknown.
            val evidence=requireNotNull(stored(date,zone,start,end,version)) {"日历日期证据不完整或版本未知"}
            require(precision==FinanceTimePrecision.DATE && at!=null && at>=evidence.startInclusive && at<evidence.endExclusive) {
                "日历日期证据与精度或原时间不一致"
            }
        }
        fun validateNew(precision:FinanceTimePrecision,at:Long?,evidence:FinanceCivilDate?) {
            require((precision==FinanceTimePrecision.DATE)==(evidence!=null)) {"仅日期记录须明确原始日历日期和时区"}
            if(evidence!=null) {
                require(evidence==capture(LocalDate.parse(evidence.localDate),ZoneId.of(evidence.zoneId))) {"日期依据与所选时区不一致"}
                validate(precision,at,evidence.localDate,evidence.zoneId,evidence.startInclusive,evidence.endExclusive,evidence.basisVersion)
            }
        }
    }
}

fun FinanceEntryTimingEntity.postedCivilDate():FinanceCivilDate? = FinanceCivilDate.stored(
    postedLocalDate,postedZoneId,postedDayStartAt,postedDayEndExclusive,postedDateBasisVersion)
fun FinanceObservationEntity.observedCivilDate():FinanceCivilDate? = FinanceCivilDate.stored(
    observedLocalDate,observedZoneId,observedDayStartAt,observedDayEndExclusive,observedDateBasisVersion)

internal data class FinanceTimeBounds(val earliest:Long,val latest:Long)
internal fun financeTimeBounds(at:Long?,precision:FinanceTimePrecision,civilDate:FinanceCivilDate?):FinanceTimeBounds? {
    if(at==null) return null
    return when(precision) {
        FinanceTimePrecision.INSTANT -> FinanceTimeBounds(at,at)
        FinanceTimePrecision.DATE -> civilDate?.takeIf {at>=it.startInclusive && at<it.endExclusive}
            ?.let {FinanceTimeBounds(it.startInclusive,Math.subtractExact(it.endExclusive,1L))}
        FinanceTimePrecision.UNKNOWN,FinanceTimePrecision.LEGACY -> null
    }
}
internal fun FinanceEntryTimingEntity.postedTimeBounds()=financeTimeBounds(postedAt,postedPrecision,postedCivilDate())

fun FinanceObservationEntity.timeLabel():String = if(precision==FinanceTimePrecision.DATE)
    observedCivilDate()?.let {it.localDate+" · "+it.zoneId+"（仅日期）"} ?: "日期资料不足（旧记录缺少日期/时区依据）"
    else FinanceTime.input(observedAt)
fun FinanceEntryTimingEntity.postedTimeLabel():String = when {
    postedPrecision==FinanceTimePrecision.DATE -> postedCivilDate()?.let {it.localDate+" · "+it.zoneId+"（仅日期）"}
        ?: "日期资料不足（旧记录缺少日期/时区依据）"
    postedAt==null -> "未知"
    else -> FinanceTime.input(postedAt)
}
