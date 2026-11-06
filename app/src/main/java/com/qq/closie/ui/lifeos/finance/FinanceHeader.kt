package com.qq.closie.ui.lifeos.finance

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qq.closie.life.finance.*
import com.qq.closie.life.ui.theme.BundledText as Text
import com.qq.closie.ui.lifeos.theme.LocalLifeOsColors

/** The account label and search share the same top line; no extra generic app bar. */
@Composable
internal fun FinanceNetHeader(state: FinanceUiState, accountsPage: Boolean, query: String,
    onQuery: (String) -> Unit, onAccounts: () -> Unit, onNewAccount: () -> Unit,
    actions: @Composable RowScope.() -> Unit) {
    val colors = LocalLifeOsColors.current
    val net = remember(state.snapshot) { FinanceAccountProjection.netByCurrency(state.snapshot) }
    val policies = state.snapshot.v16?.accountPolicyRows.orEmpty().filter { it.voidedAt == null }.associateBy { it.accountId }
    val included = state.snapshot.accounts.filter { it.archivedAt == null && policies[it.id]?.includedInNet != false }
    val first = net.toSortedMap().entries.firstOrNull()
    Column(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 32.dp)) {
        Box(Modifier.fillMaxWidth().height(if (first == null) 74.dp else 104.dp)) {
            Text(if (accountsPage) "全部账户" else "${included.size}个账户 · ${first?.key?.let { if (it == "CNY") "人民币" else it } ?: "尚无账户"}",
                Modifier.align(Alignment.TopStart).padding(top = 30.dp).clickable(onClick = onAccounts),
                style = LedgerText.meta, color = colors.inkSecondary)
            Box(Modifier.align(Alignment.TopEnd).padding(top = 14.dp).widthIn(max = 184.dp)) {
                if (accountsPage) Row(Modifier.height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f).height(28.dp).testTag("finance-new-account")
                        .border(1.dp, colors.line, RoundedCornerShape(16.dp)).clickable(onClick = onNewAccount),
                        contentAlignment = Alignment.Center) { Text("＋ 新建账户", style = LedgerText.caption, color = colors.inkSecondary) }
                    Spacer(Modifier.width(8.dp)); actions()
                } else FinanceToolbar("", query, "搜索账目", {}, {}, onQuery, actions)
            }
            first?.let { (code, minor) ->
                Row(Modifier.align(Alignment.TopStart).padding(top = 56.dp).clickable(onClick = onAccounts),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(if (code == "CNY") "¥" else code, style = LedgerText.caption.copy(fontSize = 16.sp), color = colors.inkSecondary)
                    Spacer(Modifier.width(12.dp))
                    LedgerOdometer(FinanceMoney.input(minor, code), modifier = Modifier.testTag("finance-net-value"))
                    if (!accountsPage) Text("净额", Modifier.padding(start = 8.dp), style = LedgerText.meta, color = colors.inkSecondary)
                }
            }
        }
        net.toSortedMap().entries.drop(1).forEach { (code, minor) ->
            Text(code + " · " + FinanceMoney.display(minor, code), style = LedgerText.rowAmount, color = colors.ink)
        }
        if (included.any { FinanceAccountProjection.liabilitySignPending(it, policies[it.id]?.liabilityConvention) })
            Text("含期初符号待确认账户 · 暂沿旧口径", style = LedgerText.caption, color = colors.inkSecondary)
    }
}

@Composable
internal fun FinanceCalendarHeading(month: java.time.YearMonth, stop: FinanceCalendarStop,
    onMonth: () -> Unit, onStop: (FinanceCalendarStop) -> Unit) {
    val colors = LocalLifeOsColors.current
    Row(Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 32.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).testTag("finance-date-control").semantics {contentDescription=month.year.toString()+"年 "+month.monthValue+"月"}.clickable(onClick = onMonth),
            verticalAlignment = Alignment.CenterVertically) {
            Text(month.monthValue.toString(), style = LedgerText.title, color = colors.ink)
            Text(month.year.toString(), Modifier.padding(start = 10.dp), style = LedgerText.caption, color = colors.inkSecondary)
        }
        FinanceCalendarStop.entries.forEach { target ->
            Box(Modifier.height(40.dp).width(if (target == FinanceCalendarStop.COLLAPSED) 36.dp else 28.dp)
                .clickable { onStop(target) }, contentAlignment = Alignment.Center) {
                Text(when (target) { FinanceCalendarStop.MONTH -> "月"; FinanceCalendarStop.WEEK -> "周"; else -> "收起" },
                    Modifier.background(if (stop == target) colors.card else androidx.compose.ui.graphics.Color.Transparent,
                        RoundedCornerShape(4.dp)).padding(horizontal = 4.dp, vertical = 5.dp),
                    style = LedgerText.caption, color = if (stop == target) colors.accentDeep else colors.inkSecondary)
            }
        }
    }
}
