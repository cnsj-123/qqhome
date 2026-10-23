package com.qq.closie.ui.lifeos.finance

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.qq.closie.life.finance.*
import com.qq.closie.life.ui.theme.BundledText as Text
import com.qq.closie.ui.lifeos.theme.*
import java.math.BigInteger
import java.math.BigDecimal

@Composable
internal fun FinancePeriodStrip(current: Map<String, FinanceTotals>) {
    val colors = LocalLifeOsColors.current
    current.toSortedMap().forEach { (currency, total) ->
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("收入" to total.incomeMinor.toBigInteger(), "支出" to total.expenseMinor.toBigInteger(),
                "结余" to (total.incomeMinor.toBigInteger() - total.expenseMinor.toBigInteger())).forEach { (label, value) ->
                Column(Modifier.weight(1f)) {
                    Text("$label · $currency", style = LifeText.caption, color = colors.inkSecondary)
                    Text(BigDecimal(value, FinanceMoney.fractionDigits(currency)).toPlainString(),
                        style = LifeText.body, color = colors.ink)
                }
            }
        }
    }
    if (current.isEmpty()) Text("本期间暂无收支", style = LifeText.caption, color = colors.inkSecondary)
}
