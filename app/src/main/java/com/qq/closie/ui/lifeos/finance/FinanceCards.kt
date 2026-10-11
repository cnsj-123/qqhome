package com.qq.closie.ui.lifeos.finance

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qq.closie.life.finance.*
import com.qq.closie.ui.lifeos.theme.*
import java.math.BigDecimal
import java.math.BigInteger

@Composable
internal fun FinancePanel(modifier: Modifier = Modifier, color: Color = LocalLifeOsColors.current.card,
    content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier.fillMaxWidth(), color = color, shape = RoundedCornerShape(22.dp)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable
internal fun FinanceSection(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        content()
        HorizontalDivider(Modifier.padding(top = 10.dp), color = LocalLifeOsColors.current.line)
    }
}

@Composable
internal fun FinanceMonthCard(state: FinanceUiState, onMonth: () -> Unit) {
    val colors = LocalLifeOsColors.current
    FinancePanel(color = colors.paperTertiary) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("这个月", Modifier.weight(1f), style = LifeText.title)
            TextButton(onMonth) { Text("查看流水 →") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            MoneySummary("个人支出", state.month, true, Modifier.weight(1f))
            MoneySummary("个人收入", state.month, false, Modifier.weight(1f))
        }
        Text("转账不计收支 · 退款与报销按归属计算", style = LifeText.caption, color = colors.muted)
    }
}

@Composable
internal fun FinanceBalanceSummary(state: FinanceUiState) {
    val accounts = state.snapshot.accounts.filter { it.archivedAt == null }
    val sums = remember(accounts, state.balances) { accounts.groupBy { it.currencyCode }.mapValues { (_, group) ->
        group.fold(BigInteger.ZERO) { sum, a -> sum + BigInteger.valueOf(state.balances[a.id] ?: 0) }
    } }
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("账户余额合计", style = LifeText.caption, color = LocalLifeOsColors.current.muted)
        sums.forEach { (currency, total) ->
            Text((if (currency == "CNY") "¥" else "$currency ") + BigDecimal(total, FinanceMoney.fractionDigits(currency)).toPlainString(), style = LifeText.number)
        }
        Text(if (accounts.isEmpty()) "建立账户，把钱的位置记清楚。" else "${accounts.size} 个在用账户 · 按币种分别汇总", style = LifeText.caption)
    }
}

@Composable
internal fun FinanceAccountStrip(state: FinanceUiState, open: (String) -> Unit) {
    val active = remember(state.snapshot.accounts) { state.snapshot.accounts.filter { it.archivedAt == null } }
    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        itemsIndexed(active, key = { _, a -> a.id }) { index, account ->
            FinanceAccountCard(account, state.balances[account.id] ?: 0, index, Modifier.width(208.dp)) { open(account.id) }
        }
    }
}

@Composable
internal fun FinanceAccountCard(account: FinanceAccountEntity, balance: Long, index: Int,
    modifier: Modifier = Modifier, flat: Boolean = false, onClick: () -> Unit) {
    val colors = LocalLifeOsColors.current
    val swatch = listOf(colors.secondary, colors.soft, colors.warm)[index % 3]
    Surface(onClick = onClick, modifier = modifier, color = if (flat) Color.Transparent else colors.card,
        shape = if (flat) RectangleShape else RoundedCornerShape(18.dp)) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(3.dp).height(48.dp).background(swatch, RoundedCornerShape(2.dp)))
            Column(Modifier.weight(1f).padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(account.name, style = LifeText.body)
                Text(FinanceMoney.display(balance, account.currencyCode), style = LifeText.number.copy(fontSize = 18.sp))
                Text(account.kind.label + if (account.archivedAt != null) " · 已归档" else "", style = LifeText.caption, color = colors.muted)
            }
        }
    }
}
