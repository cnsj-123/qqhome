package com.qq.closie.ui.lifeos.finance

import com.qq.closie.life.ui.theme.BundledText as Text

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
internal fun FinanceMonthCard(state: FinanceUiState, rangeLabel: String, onMonth: () -> Unit) {
    val colors = LocalLifeOsColors.current
    FinancePanel(color = colors.paperTertiary) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(rangeLabel, Modifier.weight(1f), style = LifeText.title)
            TextButton(onMonth) { Text("查看流水 →") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            MoneySummary("个人支出", state.selected, true, Modifier.weight(1f))
            MoneySummary("个人收入", state.selected, false, Modifier.weight(1f))
        }
        Text("转账不计收支 · 退款与报销按归属计算", style = LifeText.caption, color = colors.muted)
    }
}

@Composable
internal fun FinanceBalanceSummary(state: FinanceUiState) {
    val accounts = state.snapshot.accounts.filter { it.archivedAt == null }
    val sums = remember(state.snapshot) { FinanceAccountProjection.netByCurrency(state.snapshot) }
    val policies = state.snapshot.v16?.accountPolicyRows.orEmpty().filter {it.voidedAt==null}.associateBy {it.accountId}
    val included = accounts.filter {policies[it.id]?.includedInNet!=false}
    val pendingSigns=included.count {FinanceAccountProjection.liabilitySignPending(state.snapshot,it)}
    var scopeOpen by androidx.compose.runtime.saveable.rememberSaveable {mutableStateOf(false)}
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("纳入账户净额", style = LifeText.caption, color = LocalLifeOsColors.current.muted)
        sums.forEach { (currency, total) ->
            Text(FinanceMoney.display(total,currency), style = LifeText.number)
        }
        Text(if (accounts.isEmpty()) "建立账户，把钱的位置记清楚。" else "纳入 ${included.size} 个在用账户 · 排除 ${accounts.size-included.size} 个 · 按币种分别汇总", style = LifeText.caption)
        if(accounts.isNotEmpty() && included.isEmpty()) Text("当前没有纳入账户，无法显示范围内净额。",style=LifeText.caption)
        if(pendingSigns>0) Text("其中 $pendingSigns 个信用账户的正数期初含义待确认；总数暂沿旧账簿正号，请在账户中确认口径。",style=LifeText.caption)
        TextButton({scopeOpen=!scopeOpen}) {Text(if(scopeOpen) "收起范围与核对状态" else "展开范围与核对状态")}
        if(scopeOpen) {
            Text("仅汇总所纳入账户；已归档账户、未记录账户及未落到账户的往来不在本数内。核对状态使用旧账簿口径和所选观察点，不保证之后余额完整。",style=LifeText.caption)
            FinancePagedItems(accounts,"net-scope",{it.id}) { account ->
                val status=FinanceAccountStatusProjection.status(state.snapshot,account.id)
                val includedHere=policies[account.id]?.includedInNet!=false
                Text(account.name+" · "+(if(includedHere) FinanceMoney.display(FinanceAccountProjection.netContribution(state.snapshot,account),account.currencyCode) else "未纳入")+" · "+status.trust.label,style=LifeText.body)
                status.conclusion?.observation?.let {Text("核对观察时间 · "+it.timeLabel(),style=LifeText.caption)}
                Text(status.explanation,style=LifeText.caption)
                if(FinanceAccountProjection.liabilitySignPending(state.snapshot,account)) Text("期初符号待确认，当前仅沿用旧账簿口径。",style=LifeText.caption)
            }
        }
    }
}

@Composable
internal fun FinanceAccountStrip(state: FinanceUiState, open: (String) -> Unit) {
    val active = remember(state.snapshot.accounts) { state.snapshot.accounts.filter { it.archivedAt == null } }
    Column {
        active.take(4).forEachIndexed { index, account ->
            val converted=state.snapshot.v16?.accountPolicyRows.orEmpty().any {it.accountId==account.id && it.voidedAt==null && it.liabilityConvention==FinanceLiabilityConvention.POSITIVE_DEBT}
            FinanceAccountCard(account, state.balances[account.id] ?: 0, index, Modifier.fillMaxWidth(), flat = true,
                netMinor=if(converted) FinanceAccountProjection.netContribution(state.snapshot,account) else null,
                signPending=FinanceAccountProjection.liabilitySignPending(state.snapshot,account)) { open(account.id) }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun FinanceAccountCard(account: FinanceAccountEntity, balance: Long, index: Int,
    modifier: Modifier = Modifier, flat: Boolean = false, netMinor:Long?=null, signPending:Boolean=false, compact:Boolean=false,onLongClick: (() -> Unit)? = null,onClick: () -> Unit) {
    val colors = LocalLifeOsColors.current
    val swatch = listOf(colors.secondary, colors.soft, colors.warm)[index % 3]
    Surface(modifier = modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick), color = if (flat) Color.Transparent else colors.card,
        shape = if (flat) RectangleShape else RoundedCornerShape(18.dp)) {
        if (compact) Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(account.kind.label + if (account.archivedAt != null) " · 已归档" else "", style = LifeText.caption, color = colors.inkSecondary)
            Text(account.name, style = LifeText.body, color = colors.ink)
            Text(FinanceMoney.display(balance, account.currencyCode), style = com.qq.closie.life.ui.theme.LifeType.MoneyCompact, color = colors.ink)
            netMinor?.let { Text("净额贡献 " + FinanceMoney.display(it, account.currencyCode), style = LifeText.caption) }
            if (signPending) Text("期初符号待确认", style = LifeText.caption, color = colors.inkSecondary)
        } else
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(3.dp).height(30.dp).background(swatch, RoundedCornerShape(2.dp)))
            Column(Modifier.weight(1f).padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(account.name, style = LifeText.body)
                Text(account.kind.label + if (account.archivedAt != null) " · 已归档" else "", style = LifeText.caption, color = colors.muted)
                if(signPending) Text("期初符号待确认",style=LifeText.caption,color=colors.muted)
            }
            Column(horizontalAlignment=Alignment.End) {
                Text(FinanceMoney.display(netMinor ?: balance, account.currencyCode), style = com.qq.closie.life.ui.theme.LifeType.MoneyCompact,
                    modifier = Modifier.widthIn(max = 160.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End)
                if(netMinor!=null) {
                    Text("净额贡献",style=LifeText.caption)
                    Text("旧口径 "+FinanceMoney.display(balance,account.currencyCode),style=LifeText.caption)
                }
            }
        }
    }
}
