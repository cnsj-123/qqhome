package com.qq.closie.ui.lifeos.finance

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.qq.closie.life.finance.*
import com.qq.closie.ui.lifeos.theme.*
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.math.BigInteger

internal fun FinanceLedgerRow.localDay(): LocalDate = Instant.ofEpochMilli(entry.occurredAt).atZone(ZoneId.systemDefault()).toLocalDate()
private data class TrendBucket(val month: YearMonth, val expense: Long, val income: Long)

/** Canvas only paints ratios; exact amounts and attribution come from the existing projection. */
@Composable
internal fun FinanceTrend(rows: List<FinanceLedgerRow>, onRange: (String, String) -> Unit) {
    val colors = LocalLifeOsColors.current
    val series = remember(rows) {
        rows.groupBy { it.currencyCode }.mapValues { (currency, values) ->
            val grouped = values.groupBy { YearMonth.from(it.localDay()) }
            val end = grouped.keys.maxOrNull() ?: YearMonth.now()
            val start = maxOf(grouped.keys.minOrNull() ?: end, end.minusMonths(11))
            generateSequence(start) { it.plusMonths(1).takeUnless { m -> m > end } }.map { month ->
                val total = FinanceProjection.totals(grouped[month].orEmpty())[currency] ?: FinanceTotals()
                TrendBucket(month, total.expenseMinor, total.incomeMinor)
            }.toList()
        }
    }
    FinanceSection {
        Text("收支的节奏", style = LifeText.title)
        Text("支出 / 收入 · 最近最多 12 个月 · 点按月份查看", style = LifeText.caption, color = colors.muted)
        if (series.isEmpty()) Text("有了记录，这里会呈现你的收支变化。", style = LifeText.body)
        series.forEach { (currency, buckets) ->
            Text("$currency · ${buckets.first().month} — ${buckets.last().month}", style = LifeText.caption)
            val maximum = buckets.maxOf { maxOf(kotlin.math.abs(it.expense.toDouble()), kotlin.math.abs(it.income.toDouble())) }.coerceAtLeast(1.0)
            val hasNegative = buckets.any { it.expense < 0 || it.income < 0 }
            Row(Modifier.fillMaxWidth().height(128.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                buckets.forEach { bucket ->
                    Column(Modifier.weight(1f).fillMaxHeight().semantics {
                        contentDescription = "${bucket.month}，支出 ${FinanceMoney.display(bucket.expense, currency)}，收入 ${FinanceMoney.display(bucket.income, currency)}"
                    }.clickable { onRange(bucket.month.atDay(1).toString(), bucket.month.atEndOfMonth().toString()) },
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        Canvas(Modifier.fillMaxWidth().weight(1f)) {
                            val baseline = size.height * if (hasNegative) .5f else .95f
                            drawLine(colors.line, Offset(0f, baseline), Offset(size.width, baseline), 1.dp.toPx())
                            listOf(bucket.expense to colors.accent, bucket.income to colors.secondary).forEachIndexed { index, (value, color) ->
                                val height = (kotlin.math.abs(value.toDouble()) / maximum * size.height * if (hasNegative) .45 else .9).toFloat()
                                drawRoundRect(color, Offset(index * size.width / 2, if (value >= 0) baseline - height else baseline),
                                    Size(size.width * .38f, height), androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()))
                            }
                        }
                        Text("${bucket.month.monthValue}", style = LifeText.caption)
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("● 支出", color = colors.accentDeep, style = LifeText.caption)
                Text("○ 收入", style = LifeText.caption)
            }
            if (hasNegative) Text("零线下方表示净退款或收入冲减，上下使用相同比例。", style = LifeText.caption, color = colors.muted)
        }
    }
}

@Composable
internal fun FinanceCategoryChart(rows: List<FinanceDimensionTotal>, onCategory: (String, String) -> Unit) {
    val colors = LocalLifeOsColors.current
    val byCurrency = remember(rows) { rows.groupBy { it.currencyCode } }
    var allCurrency by remember { mutableStateOf<String?>(null) }
    FinanceSection {
        Text("钱花在哪里", style = LifeText.title)
        if (rows.isEmpty()) Text("这个范围还没有分类收支。", style = LifeText.body)
        byCurrency.forEach { (currency, values) ->
            val ranked = values.sortedByDescending { it.totals.expenseMinor }
            val positive = ranked.filter { it.totals.expenseMinor > 0 }
            val total = positive.fold(BigInteger.ZERO) { sum, v -> sum + BigInteger.valueOf(v.totals.expenseMinor) }.toDouble()
            val swatches = listOf(colors.accent, colors.secondary, colors.soft, colors.warm, colors.accentDeep)
            Text("$currency · 个人净支出", style = LifeText.caption, color = colors.muted)
            if (total > 0) {
                Canvas(Modifier.fillMaxWidth().height(130.dp).semantics { contentDescription = "正向净支出分布；下方分类列表提供金额与查看操作" }) {
                    var start = -90f
                    val diameter = size.height - 22.dp.toPx()
                    positive.forEachIndexed { index, row ->
                        val sweep = (row.totals.expenseMinor / total * 360).toFloat()
                        drawArc(swatches[index % swatches.size], start, sweep, false,
                            Offset((size.width - diameter) / 2, 11.dp.toPx()), Size(diameter, diameter), style = Stroke(16.dp.toPx()))
                        start += sweep
                    }
                }
                Text("环图仅含正向净支出；负值为净退款，保留在分类明细中。", style = LifeText.caption, color = colors.muted)
            }
            ranked.take(8).forEachIndexed { index, row ->
                Column(Modifier.fillMaxWidth().clickable { onCategory(row.label, currency) }.padding(vertical = 6.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(row.label, Modifier.weight(1f), style = LifeText.body)
                        Text(FinanceMoney.display(row.totals.expenseMinor, currency), style = LifeText.body.copy(fontFeatureSettings = "tnum"))
                    }
                    if (row.totals.incomeMinor != 0L) Text("收入 ${FinanceMoney.display(row.totals.incomeMinor, currency)}", style = LifeText.caption, color = colors.muted)
                    if (total > 0 && row.totals.expenseMinor > 0) Box(Modifier.padding(top = 7.dp).fillMaxWidth().height(4.dp).background(colors.paperSecondary, RoundedCornerShape(2.dp))) {
                        Box(Modifier.fillMaxWidth((row.totals.expenseMinor / total).toFloat().coerceIn(0f, 1f)).fillMaxHeight().background(swatches[index % swatches.size], RoundedCornerShape(2.dp)))
                    }
                }
            }
            if (ranked.size > 8) TextButton({ allCurrency = currency }) { Text("查看全部 ${ranked.size} 个分类") }
        }
    }
    allCurrency?.let { currency ->
        FinanceChoiceSheet("分类明细", null, rows.filter { it.currencyCode == currency }.map { it.label to it.label },
            onSelect = { onCategory(it, currency) }, onClose = { allCurrency = null })
    }
}
