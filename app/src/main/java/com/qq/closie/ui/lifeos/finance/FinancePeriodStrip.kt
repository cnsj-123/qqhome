package com.qq.closie.ui.lifeos.finance

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.qq.closie.life.finance.*
import com.qq.closie.life.ui.theme.BundledText as Text
import com.qq.closie.ui.lifeos.theme.*
import java.math.BigDecimal

@Composable
internal fun FinancePeriodStrip(current: Map<String, FinanceCashSummary>, rangeLabel: String) {
    if (current.isEmpty()) return
    val colors = LocalLifeOsColors.current
    current.toSortedMap().forEach { (currency, summary) ->
        val total = summary.confirmed
        val rows = listOf(
            Triple("收入", total.incomeMinor.toBigInteger(), colors.accentDeep),
            Triple("支出", total.expenseMinor.toBigInteger(), colors.warm),
            Triple("结余", total.incomeMinor.toBigInteger() - total.expenseMinor.toBigInteger(), colors.muted)
        )
        val maximum = rows.maxOf { it.second.abs().toDouble() }.coerceAtLeast(1.0)
        Column(Modifier.fillMaxWidth().background(colors.card, RoundedCornerShape(24.dp))
            .padding(horizontal = 18.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("$rangeLabel · $currency · 纳入统计的实际收付", style = LifeText.caption, color = colors.inkSecondary)
            rows.forEach { (label, value, tint) ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(label, Modifier.widthIn(min = 32.dp), style = LifeText.caption, color = colors.inkSecondary)
                    Text(BigDecimal(value, FinanceMoney.fractionDigits(currency)).toPlainString(), Modifier.weight(2f),
                        style = LifeText.caption, color = tint)
                    Box(Modifier.weight(4f).height(5.dp).background(colors.paperSecondary, RoundedCornerShape(3.dp))) {
                        Box(Modifier.fillMaxWidth((value.abs().toDouble() / maximum).toFloat()).fillMaxHeight()
                            .background(tint, RoundedCornerShape(3.dp)))
                    }
                }
            }
            if (summary.unknownDateCount > 0) {
                Text("已知收付小计 · 另有 ${summary.unknownDateCount} 笔收付日期待确定",
                    style = LifeText.caption, color = colors.inkSecondary)
            }
        }
    }
}
