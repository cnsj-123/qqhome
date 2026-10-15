package com.qq.closie.ui.lifeos.finance

import com.qq.closie.life.ui.theme.BundledText as Text

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.clearAndSetSemantics
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
    onMonth: (YearMonth) -> Unit, onDay: (LocalDate?) -> Unit,
    collapseProgress: Float = 0f, onCollapse: (Boolean) -> Unit = {}, onCollapseDistance: (Float) -> Unit = {}) {
    val progress = collapseProgress.coerceIn(0f, 1f)
    val colors = LocalLifeOsColors.current
    val currencies = remember(rows) { rows.map { it.currencyCode }.distinct().sorted().ifEmpty { listOf("CNY") } }
    var preferredCurrency by rememberSaveable { mutableStateOf("CNY") }
    val currency = preferredCurrency.takeIf { it in currencies } ?: currencies.first()
    var currencyPicker by remember { mutableStateOf(false) }
    val days = remember(rows, currency) { ledgerDays(rows, currency) }
    fun amount(value: BigInteger) = BigDecimal(value, FinanceMoney.fractionDigits(currency)).toPlainString()
    var weekAnchor by rememberSaveable { mutableStateOf((selected ?: LocalDate.now().takeIf { YearMonth.from(it) == month } ?: month.atDay(1)).toString()) }
    val anchor = LocalDate.parse(weekAnchor).takeIf { YearMonth.from(it) == month }
        ?: selected?.takeIf { YearMonth.from(it) == month } ?: month.atDay(1)
    LaunchedEffect(selected) { selected?.let { weekAnchor = it.toString() } }
    Column(Modifier.fillMaxWidth().calendarMonthSwipe { delta ->
        if (progress >= .5f) {
            val next = anchor.plusWeeks(delta.toLong())
            weekAnchor = next.toString()
            onMonth(YearMonth.from(next))
        } else {
            val next = month.plusMonths(delta.toLong())
            weekAnchor = next.atDay(1).toString()
            onMonth(next)
        }
    }
        .scrollable(rememberScrollableState { 0f }, Orientation.Vertical)
        .padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.weight(1f))
            TextButton({ weekAnchor = LocalDate.now().toString(); onMonth(YearMonth.now()); onDay(LocalDate.now()) }) { Text("今天") }
            TextButton({ onCollapse(progress < .5f) }) { Text(if (progress >= .5f) "展开" else "收起") }
            if (selected != null || progress > 0f) TextButton({ onDay(null); onCollapse(false) }) { Text("整月") }
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
        val focus = anchor
        val selectedWeek = (leading + focus.dayOfMonth - 1) / 7
        val rowHeights = remember(month) { mutableStateMapOf<Int, Int>() }
        LaunchedEffect(rowHeights.values.toList(), selectedWeek) {
            if (rowHeights.size == weeks) onCollapseDistance(rowHeights.filterKeys { it != selectedWeek }.values.sum().toFloat())
        }
        (0 until weeks).forEach { week ->
            val fraction = if (week == selectedWeek) 1f else 1f - progress
            // Measure full content (including large font sizes), then continuously crop its height.
            // No threshold and no animation lag behind the finger.
            Row(Modifier.fillMaxWidth().clipToBounds().graphicsLayer { alpha = fraction }
                .layout { measurable, constraints ->
                    val placeable = measurable.measure(constraints)
                    if (rowHeights[week] != placeable.height) rowHeights[week] = placeable.height
                    val height = (placeable.height * fraction).toInt()
                    layout(placeable.width, height) { placeable.placeRelative(0, (height - placeable.height) / 2) }
                }.then(if (fraction == 0f) Modifier.clearAndSetSemantics {} else Modifier),
                horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                repeat(7) { column ->
                    val number = week * 7 + column - leading + 1
                    val inMonth = number in 1..month.lengthOfMonth()
                    val date = if (inMonth || (week == selectedWeek && progress >= .5f)) month.atDay(1).plusDays(number.toLong() - 1) else null
                    val day = days[date]
                    Column(Modifier.weight(1f).heightIn(min = 48.dp)
                        .background(if (date != null && date == selected) colors.paperTertiary else Color.Transparent, RoundedCornerShape(6.dp))
                        .then(if (date != null && fraction > 0f) Modifier.clickable {
                            val next = if (date == selected) null else date
                            if (next != null && YearMonth.from(next) != month) onMonth(YearMonth.from(next))
                            onDay(next)
                        }
                            .semantics { contentDescription = "$date，${day?.count ?: 0} 笔，$currency 支出 ${day?.let { amount(it.spending) } ?: "0"}，收入 ${day?.let { amount(it.income) } ?: "0"}" } else Modifier)
                        .padding(vertical = 2.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        if (date != null) {
                            Text(date.dayOfMonth.toString(), style = LifeText.body, color = if (date == LocalDate.now()) colors.accentDeep else if (inMonth) colors.ink else colors.muted)
                            if (day != null) {
                                if (day.spending.signum() > 0) Text("−${amount(day.spending)}", style = com.qq.closie.life.ui.theme.LifeType.MoneyCalendar, color = colors.inkSecondary, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                if (day.income.signum() > 0) Text("+${amount(day.income)}", style = com.qq.closie.life.ui.theme.LifeType.MoneyCalendar, color = colors.accentDeep, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                if (day.income.signum() == 0 && day.spending.signum() == 0) Text("·", style = LifeText.caption)
                            }
                        }
                    }
                }
            }
        }
        selected?.let { date ->
            Text("$date · $currency 支出 ${days[date]?.let { amount(it.spending) } ?: "0"} / 收入 ${days[date]?.let { amount(it.income) } ?: "0"}", style = LifeText.caption)
        }
        if (currencies.size > 1) Text("日历显示 $currency；下方保留各币种流水，不做换算。", style = LifeText.caption, color = colors.muted)
        HorizontalDivider(Modifier.padding(vertical = 4.dp), color = colors.line)
    }
    if (currencyPicker) FinanceChoiceSheet("日历币种", currency, currencies.map { it to it }, { preferredCurrency = it }, { currencyPicker = false })
}
