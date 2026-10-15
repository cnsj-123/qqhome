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
import java.math.BigInteger
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
internal fun FinancePeriodSummary(current: Map<String, FinanceTotals>, previous: Map<String, FinanceTotals>?) {
    val colors = LocalLifeOsColors.current
    FinanceSection {
        Text("这段日子的收支", style = LifeText.title)
        Text("当前范围 · 个人收支 · 各币种分别统计", style = LifeText.caption, color = colors.muted)
        (current.keys + previous.orEmpty().keys).sorted().forEach { currency ->
            val totals = current[currency] ?: FinanceTotals()
            Text(currency, Modifier.padding(top = 10.dp), style = LifeText.caption, color = colors.accentDeep)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf("收入" to BigInteger.valueOf(totals.incomeMinor), "支出" to BigInteger.valueOf(totals.expenseMinor),
                    "结余" to (BigInteger.valueOf(totals.incomeMinor) - BigInteger.valueOf(totals.expenseMinor))).forEach { (label, amount) ->
                    Column(Modifier.weight(1f)) {
                        Text(label, style = LifeText.caption, color = colors.muted)
                        Text(BigDecimal(amount, FinanceMoney.fractionDigits(currency)).toPlainString(), style = LifeText.body)
                    }
                }
            }
            previous?.let {
                val prior = it[currency] ?: FinanceTotals()
                Text("支出 " + periodChange(totals.expenseMinor, prior.expenseMinor), style = LifeText.caption, color = colors.inkSecondary)
                Text("上期支出 ${FinanceMoney.display(prior.expenseMinor, currency)} · 收入 ${FinanceMoney.display(prior.incomeMinor, currency)}",
                    style = LifeText.caption, color = colors.muted)
            }
            HorizontalDivider(Modifier.padding(top = 12.dp), color = colors.line)
        }
        if (current.isEmpty() && previous.orEmpty().isEmpty()) Text("当前范围还没有个人收支。", style = LifeText.body)
        if (previous == null) Text("选择日期范围后，可比较上一期。", style = LifeText.caption, color = colors.muted)
    }
}
