package com.qq.closie.life.finance

import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.Instant
import java.time.DateTimeException

enum class FinanceCivilDateTrust { CONFIRMED, LEGACY_UNKNOWN, HISTORICAL_UNVERIFIED, INVALID }
data class FinanceCivilDateAssessment(val trust:FinanceCivilDateTrust,val evidence:FinanceCivilDate?)

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
        /** Classification never repairs persisted evidence or claims to reconstruct an old tzdb. */
        fun assess(date:String?,zone:String?,start:Long?,end:Long?,version:Int?):FinanceCivilDateAssessment {
            val invalid=FinanceCivilDateAssessment(FinanceCivilDateTrust.INVALID,null)
            if(listOf(date,zone,start,end,version).all {it==null})
                return FinanceCivilDateAssessment(FinanceCivilDateTrust.LEGACY_UNKNOWN,null)
            if(date==null || zone==null || start==null || end==null || version!=LOCAL_DAY_AT_CONFIRMATION || end<=start) return invalid
            return try {
                val parsed=LocalDate.parse(date)
                if(parsed.toString()!=date) return invalid
                val originalZone=ZoneId.of(zone)
                // Necessary, not sufficient: each midnight boundary must fit Java's possible
                // offsets [-18h,+18h]. A gap straddling midnight also obeys this envelope:
                // transition = local-before - offset-before = local-after - offset-after.
                // See CI52-FIX.md for the proof and false-positive limits. Never grant trust
                // from this envelope, never use it to include/exclude a financial record.
                fun possibleBoundary(day:LocalDate,epoch:Long):Boolean {
                    val instant=Instant.ofEpochMilli(epoch)
                    val midnight=day.atStartOfDay()
                    return instant>=midnight.toInstant(ZoneOffset.MAX) && instant<=midnight.toInstant(ZoneOffset.MIN)
                }
                if(!possibleBoundary(parsed,start) || !possibleBoundary(parsed.plusDays(1),end)) return invalid
                val evidence=FinanceCivilDate(date,zone,start,end,LOCAL_DAY_AT_CONFIRMATION)
                val current=try {capture(parsed,originalZone)} catch(_:DateTimeException) {null}
                    catch(_:IllegalArgumentException) {null} catch(_:ArithmeticException) {null}
                // Fixed rules include named ZoneRegion aliases (UTC, Etc/UTC, UTC+09:00),
                // not just ZoneOffset instances. They cannot explain a shifted day boundary.
                if(originalZone.rules.isFixedOffset && evidence!=current) return invalid
                FinanceCivilDateAssessment(if(evidence==current) FinanceCivilDateTrust.CONFIRMED
                    else FinanceCivilDateTrust.HISTORICAL_UNVERIFIED,evidence)
            } catch(_:DateTimeException) {invalid}
        }
        fun stored(date:String?,zone:String?,start:Long?,end:Long?,version:Int?):FinanceCivilDate? =
            assess(date,zone,start,end,version).evidence
        fun validate(precision:FinanceTimePrecision,at:Long?,date:String?,zone:String?,start:Long?,end:Long?,version:Int?) {
            val assessment=assess(date,zone,start,end,version)
            if(assessment.trust==FinanceCivilDateTrust.LEGACY_UNKNOWN) return
            val evidence=requireNotNull(assessment.evidence) {"日历日期证据矛盾、不完整或版本未知"}
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

fun FinanceCivilDate.assessment():FinanceCivilDateAssessment =
    FinanceCivilDate.assess(localDate,zoneId,startInclusive,endExclusive,basisVersion)

fun FinanceEntryTimingEntity.postedCivilDateAssessment():FinanceCivilDateAssessment = FinanceCivilDate.assess(
    postedLocalDate,postedZoneId,postedDayStartAt,postedDayEndExclusive,postedDateBasisVersion)
fun FinanceObservationEntity.observedCivilDateAssessment():FinanceCivilDateAssessment = FinanceCivilDate.assess(
    observedLocalDate,observedZoneId,observedDayStartAt,observedDayEndExclusive,observedDateBasisVersion)
fun FinanceEntryTimingEntity.postedCivilDate():FinanceCivilDate? = postedCivilDateAssessment().evidence
fun FinanceObservationEntity.observedCivilDate():FinanceCivilDate? = observedCivilDateAssessment().evidence

internal data class FinanceTimeBounds(val earliest:Long,val latest:Long)
internal fun financeTimeBounds(at:Long?,precision:FinanceTimePrecision,civilDate:FinanceCivilDate?):FinanceTimeBounds? {
    if(at==null) return null
    return when(precision) {
        FinanceTimePrecision.INSTANT -> FinanceTimeBounds(at,at)
        FinanceTimePrecision.DATE -> civilDate?.takeIf {it.assessment().trust==FinanceCivilDateTrust.CONFIRMED && at>=it.startInclusive && at<it.endExclusive}
            ?.let {FinanceTimeBounds(it.startInclusive,Math.subtractExact(it.endExclusive,1L))}
        FinanceTimePrecision.UNKNOWN,FinanceTimePrecision.LEGACY -> null
    }
}
internal fun FinanceEntryTimingEntity.postedTimeBounds()=financeTimeBounds(postedAt,postedPrecision,postedCivilDate())

/** All DATE displays share the same confidence wording; raw epoch never supplies a missing date. */
private fun civilDateLabel(at:Long?,assessment:FinanceCivilDateAssessment):String {
    val evidence=assessment.evidence
    val invalid="日期资料无效（不能作为已确认日期）"
    if(evidence!=null && (at==null || at<evidence.startInclusive || at>=evidence.endExclusive)) return invalid
    return when(assessment.trust) {
        FinanceCivilDateTrust.CONFIRMED -> requireNotNull(evidence).let {it.localDate+" · "+it.zoneId+"（仅日期）"}
        FinanceCivilDateTrust.LEGACY_UNKNOWN -> "日期资料不足（历史记录缺少原始日期/时区依据）"
        FinanceCivilDateTrust.HISTORICAL_UNVERIFIED -> requireNotNull(evidence).let {
            it.localDate+" · "+it.zoneId+"（历史日期参考；确认区间无法核验，资料不足）"}
        FinanceCivilDateTrust.INVALID -> invalid
    }
}
fun FinanceObservationEntity.timeLabel():String = if(precision==FinanceTimePrecision.DATE)
    civilDateLabel(observedAt,observedCivilDateAssessment())
    else FinanceTime.input(observedAt)
fun FinanceEntryTimingEntity.postedTimeLabel():String = when {
    postedPrecision==FinanceTimePrecision.DATE -> civilDateLabel(postedAt,postedCivilDateAssessment())
    postedAt==null -> "未知"
    else -> FinanceTime.input(postedAt)
}
