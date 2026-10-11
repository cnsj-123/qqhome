package com.qq.closie.ui.lifeos.finance

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qq.closie.life.finance.*
import com.qq.closie.ui.lifeos.theme.*
import java.math.BigDecimal
import java.math.BigInteger
import java.time.LocalDate
import java.time.YearMonth

internal data class LedgerDay(val count: Int, val spending: BigInteger, val income: BigInteger)
internal fun ledgerDays(rows: List<FinanceLedgerRow>, currency: String): Map<LocalDate, LedgerDay> =
    rows.filter { it.currencyCode == currency }.groupBy { it.localDay() }.mapValues { (_, day) ->
        fun sum(direction: FinanceDirection) = day.filter { it.transfer == null && it.entry.direction == direction }
            .fold(BigInteger.ZERO) { total, row -> total + BigInteger.valueOf(row.entry.amountMinor) }
        LedgerDay(day.size, sum(FinanceDirection.OUTFLOW), sum(FinanceDirection.INFLOW))
    }

@Composable
internal fun FinanceMonthLedger(month: YearMonth, rows: List<FinanceLedgerRow>, selected: LocalDate?,
    onMonth: (YearMonth) -> Unit, onDay: (LocalDate?) -> Unit) {
    val colors = LocalLifeOsColors.current
    val currencies = remember(rows) { rows.map { it.currencyCode }.distinct().sorted().ifEmpty { listOf("CNY") } }
    var preferredCurrency by rememberSaveable { mutableStateOf("CNY") }
    val currency = preferredCurrency.takeIf { it in currencies } ?: currencies.first()
    var currencyPicker by remember { mutableStateOf(false) }
    val days = remember(rows, currency) { ledgerDays(rows, currency) }
    fun amount(value: BigInteger) = BigDecimal(value, FinanceMoney.fractionDigits(currency)).toPlainString()
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton({ onMonth(month.minusMonths(1)) }) { Icon(Icons.Outlined.ChevronLeft, "上个月") }
            Text("${month.year} 年 ${month.monthValue} 月", Modifier.weight(1f), style = LifeText.title)
            TextButton({ onMonth(YearMonth.now()); onDay(LocalDate.now()) }) { Text("今天") }
            IconButton({ onMonth(month.plusMonths(1)) }) { Icon(Icons.Outlined.ChevronRight, "下个月") }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("实际收付 · 转账不计收支", Modifier.weight(1f), style = LifeText.caption, color = colors.muted)
            TextButton({ currencyPicker = true }, enabled = currencies.size > 1) { Text(currency) }
        }
        Row { listOf("一", "二", "三", "四", "五", "六", "日").forEach {
            Box(Modifier.weight(1f).height(28.dp), contentAlignment = Alignment.Center) { Text(it, style = LifeText.caption, color = colors.muted) }
        } }
        val leading = month.atDay(1).dayOfWeek.value - 1
        val weeks = (leading + month.lengthOfMonth() + 6) / 7
        repeat(weeks) { week ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                repeat(7) { column ->
                    val number = week * 7 + column - leading + 1
                    val date = number.takeIf { it in 1..month.lengthOfMonth() }?.let(month::atDay)
                    val day = days[date]
                    Column(Modifier.weight(1f).heightIn(min = 66.dp)
                        .background(if (date != null && date == selected) colors.paperTertiary else Color.Transparent, RoundedCornerShape(6.dp))
                        .then(if (date != null) Modifier.clickable { onDay(if (selected == date) null else date) }
                            .semantics { contentDescription = "$date，${day?.count ?: 0} 笔，$currency 支出 ${day?.let { amount(it.spending) } ?: "0"}，收入 ${day?.let { amount(it.income) } ?: "0"}" } else Modifier)
                        .padding(vertical = 5.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        if (date != null) {
                            Text(number.toString(), style = LifeText.body, color = if (date == LocalDate.now()) colors.accentDeep else colors.ink)
                            if (day != null) {
                                if (day.spending.signum() > 0) Text("−${amount(day.spending)}", style = LifeText.caption.copy(fontSize = 9.sp, lineHeight = 12.sp, fontFeatureSettings = "tnum"), color = colors.inkSecondary, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                if (day.income.signum() > 0) Text("+${amount(day.income)}", style = LifeText.caption.copy(fontSize = 9.sp, lineHeight = 12.sp, fontFeatureSettings = "tnum"), color = colors.accentDeep, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                if (day.income.signum() == 0 && day.spending.signum() == 0) Text("·", style = LifeText.caption)
                            }
                        }
                    }
                }
            }
        }
        selected?.let { date ->
            Text("$date · $currency 支出 ${days[date]?.let { amount(it.spending) } ?: "0"} / 收入 ${days[date]?.let { amount(it.income) } ?: "0"}", style = LifeText.caption)
            TextButton({ onDay(null) }) { Text("查看整月流水") }
        }
        if (currencies.size > 1) Text("日历显示 $currency；下方保留各币种流水，不做换算。", style = LifeText.caption, color = colors.muted)
        HorizontalDivider(Modifier.padding(vertical = 12.dp), color = colors.line)
    }
    if (currencyPicker) FinanceChoiceSheet("日历币种", currency, currencies.map { it to it }, { preferredCurrency = it }, { currencyPicker = false })
}
