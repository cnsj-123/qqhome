package com.qq.closie.ui.lifeos.finance

import com.qq.closie.life.ui.theme.BundledText as Text

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.qq.closie.life.finance.*
import com.qq.closie.ui.lifeos.theme.*
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** Compare complete calendar months/years, otherwise the preceding equal number of local days.
 * Local dates, not milliseconds, preserve day boundaries across DST changes. */
internal fun previousFinanceRange(range: FinanceDateRange, zone: ZoneId = ZoneId.systemDefault()): FinanceDateRange? {
    val start = range.startInclusive?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() } ?: return null
    val end = range.endExclusive?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() } ?: return null
    if (start.dayOfYear == 1 && end == start.plusYears(1)) return FinanceDateRange.year(start.minusYears(1), zone)
    if (start.dayOfMonth == 1 && end == start.plusMonths(1)) return FinanceDateRange.month(start.minusMonths(1), zone)
    val days = ChronoUnit.DAYS.between(start, end)
    return if (days > 0) FinanceDateRange.days(start.minusDays(days), start.minusDays(1), zone) else null
}

internal fun periodChange(current: Long, previous: Long): String {
    if (previous <= 0) return if (previous == 0L) "上期为零，不计算比例" else "上期为净冲减，不计算比例"
    val delta = BigDecimal.valueOf(current).subtract(BigDecimal.valueOf(previous))
        .multiply(BigDecimal(100)).divide(BigDecimal.valueOf(previous), 1, RoundingMode.HALF_UP)
    return "较上期 ${if (delta.signum() > 0) "+" else ""}${delta.toPlainString()}%"
}

@Composable
internal fun FinancePeriodSummary(current: Map<String, PersonalCostTotal>, previous: Map<String, PersonalCostTotal>?,
    dateUnknown:Map<String,Int> = emptyMap(),previousDateUnknown:Map<String,Int> = emptyMap()) {
    val colors = LocalLifeOsColors.current
    FinanceSection {
        Text("个人成本", style = LifeText.title)
        Text("按经济发生日与实际调整日归期 · 各币种分别统计", style = LifeText.caption, color = colors.muted)
        (current.keys + previous.orEmpty().keys + dateUnknown.keys + previousDateUnknown.keys).sorted().forEach { currency ->
            val totals = current[currency]
            val undated=dateUnknown[currency] ?: 0
            val previousUndated=previousDateUnknown[currency] ?: 0
            Text(currency, Modifier.padding(top = 10.dp), style = LifeText.caption, color = colors.accentDeep)
            if (totals == null) {
                Text(if(undated==0) "本期没有个人成本记录" else "本期已知小计 "+FinanceMoney.display(0,currency), style = LifeText.body)
            } else {
                Text("已确认 " + FinanceMoney.display(totals.confirmedMinor, currency), style = LifeText.body)
                if (!totals.complete) Text("另有 ${totals.unknownCount} 项承担待确定", style = LifeText.caption)
            }
            previous?.get(currency)?.let { prior ->
                Text("上期已确认 " + FinanceMoney.display(prior.confirmedMinor, currency), style = LifeText.caption, color = colors.muted)
                if (!prior.complete) Text("上期另有 ${prior.unknownCount} 项承担待确定", style = LifeText.caption)
                if (totals?.complete == true && prior.complete && undated==0 && previousUndated==0) {
                    Text(periodChange(totals.confirmedMinor, prior.confirmedMinor), style = LifeText.caption, color = colors.inkSecondary)
                }
            }
            if(undated>0 || previousUndated>0) Text("另有 ${maxOf(undated,previousUndated)} 项成本日期待确认，无法归期；本期与上期显示已知小计。",style=LifeText.caption,color=colors.muted)
            HorizontalDivider(Modifier.padding(top = 12.dp), color = colors.line)
        }
        if (current.isEmpty() && previous.orEmpty().isEmpty() && dateUnknown.isEmpty() && previousDateUnknown.isEmpty()) Text("当前范围还没有个人成本记录。", style = LifeText.body)
        if (previous == null) Text("选择日期范围后，可比较上一期。", style = LifeText.caption, color = colors.muted)
    }
}
