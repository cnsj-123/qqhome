package com.qq.closie.ui.lifeos.finance

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.qq.closie.life.finance.*
import com.qq.closie.navigation.modules.PresentationFactory
import com.qq.closie.ui.lifeos.theme.*
import java.time.Instant
import java.time.ZoneId

@Composable
fun FinanceRoute(repository: FinanceRepository, onBack: () -> Unit) {
    val vm: FinanceViewModel = viewModel(factory = PresentationFactory(FinanceViewModel::class.java) { FinanceViewModel(repository) })
    val state by vm.state.collectAsStateWithLifecycle()
    FinanceScreen(state, vm, onBack)
}

@Composable
private fun FinanceScreen(state: FinanceUiState, vm: FinanceViewModel, onBack: () -> Unit) {
    val colors = LocalLifeOsColors.current
    var editorId by rememberSaveable { mutableStateOf<String?>(null) }
    var editorOpen by rememberSaveable { mutableStateOf(false) }
    var accountId by rememberSaveable { mutableStateOf<String?>(null) }
    var accountOpen by rememberSaveable { mutableStateOf(false) }
    var accountsOpen by rememberSaveable { mutableStateOf(false) }
    var datesOpen by rememberSaveable { mutableStateOf(false) }
    val editedRow = state.ledger.find { it.id == editorId }
        ?: FinanceProjection.ledger(state.snapshot).find { it.id == editorId }

    Surface(Modifier.fillMaxSize(), color = colors.paper) {
        LazyColumn(Modifier.testTag("finance-ledger"), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回首页", tint = colors.ink) }
                    Column(Modifier.weight(1f)) {
                        Text("账目", style = LifeText.display, color = colors.ink)
                        Text("把每笔钱的来去，记清楚。", style = LifeText.caption, color = colors.muted)
                    }
                    TextButton(onClick = { accountsOpen = true }, enabled = !state.loading) { Text("账户管理") }
                }
            }
            if (state.loading) item { Text("正在打开账本……", style = LifeText.body, color = colors.muted) }
            state.error?.let { message -> item {
                Column {
                    Text(message, style = LifeText.body, color = colors.accentDeep)
                    Row {
                        TextButton(onClick = vm::clearError) { Text("知道了") }
                        TextButton(onClick = vm::reload) { Text("重新读取") }
                    }
                }
            } }
            item {
                Surface(shape = RoundedCornerShape(20.dp), color = colors.paperSecondary) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Row(Modifier.fillMaxWidth()) {
                            MoneySummary("本月支出", state.month, expense = true, Modifier.weight(1f))
                            MoneySummary("今年支出", state.year, expense = true, Modifier.weight(1f))
                        }
                        HorizontalDivider(color = colors.line)
                        MoneySummary("筛选范围收入", state.selected, expense = false)
                    }
                }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("账户与余额", style = LifeText.title, color = colors.ink, modifier = Modifier.weight(1f))
                    TextButton(onClick = { accountId = null; accountOpen = true }, enabled = !state.loading && !state.busy) { Text("建立账户") }
                }
                val active = state.snapshot.accounts.filter { it.archivedAt == null }
                if (active.isEmpty()) Text("先建立银行卡、微信等真实账户，再开始记账。", style = LifeText.body, color = colors.muted)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(active, key = { it.id }) { account ->
                        Surface(onClick = { accountId = account.id; accountOpen = true },
                            shape = RoundedCornerShape(14.dp), color = colors.card, border = BorderStroke(1.dp, colors.line)) {
                            Column(Modifier.widthIn(min = 150.dp).padding(14.dp)) {
                                Text(account.name, style = LifeText.body, color = colors.ink)
                                Text(account.kind.label, style = LifeText.caption, color = colors.muted)
                                Text(FinanceMoney.display(state.balances[account.id] ?: 0, account.currencyCode), style = LifeText.title, color = colors.accentDeep)
                            }
                        }
                    }
                }
            }
            item {
                FilledTonalButton(onClick = { editorId = null; editorOpen = true },
                    enabled = state.snapshot.accounts.any { it.archivedAt == null } && !state.busy,
                    modifier = Modifier.fillMaxWidth().testTag("finance-add")) {
                    Icon(Icons.Outlined.Add, null); Spacer(Modifier.width(8.dp)); Text("记一笔")
                }
            }
            item {
                OutlinedTextField(state.filter.query, vm::search, Modifier.fillMaxWidth(),
                    placeholder = { Text("搜事由、分类、标签或账户", style = LifeText.caption) },
                    leadingIcon = { Icon(Icons.Outlined.Search, null) }, singleLine = true)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FinancePeriod.entries.forEach { period ->
                        FilterChip(selected = state.filter.period == period, onClick = {
                            if (period == FinancePeriod.CUSTOM) datesOpen = true else vm.selectPeriod(period)
                        }, label = { Text(period.label, style = LifeText.caption) })
                    }
                }
                if (state.filter.customLabel.isNotEmpty()) Text(state.filter.customLabel, style = LifeText.caption, color = colors.muted)
            }
            item {
                Text("流水 · ${state.ledger.size} 笔", style = LifeText.title, color = colors.ink)
                val selected = state.selected.ifEmpty { mapOf("CNY" to FinanceTotals()) }
                selected.forEach { (currency, totals) -> Text(
                    "范围支出 ${FinanceMoney.display(totals.expenseMinor, currency)} · 收入 ${FinanceMoney.display(totals.incomeMinor, currency)}",
                    style = LifeText.caption, color = colors.muted) }
            }
            if (state.ledger.isEmpty() && !state.loading) item {
                Text(if (state.snapshot.entries.isEmpty()) "还没有流水，从一笔真实的记录开始。" else "这个范围没有匹配的有效流水。", style = LifeText.body, color = colors.muted)
            }
            val groups = state.ledger.groupBy { Instant.ofEpochMilli(it.entry.occurredAt).atZone(ZoneId.systemDefault()).toLocalDate() }
            groups.forEach { (date, rows) ->
                item(key = "date-$date") { Text(date.toString(), style = LifeText.caption, color = colors.muted) }
                items(rows, key = { it.id }) { row ->
                    LedgerLine(row, onClick = { editorId = row.id; vm.clearError(); editorOpen = true })
                }
            }
            item {
                DimensionSummary("分类", state.categories)
                DimensionSummary("标签", state.tags)
            }
        }
    }
    if (editorOpen && !state.loading) FinanceEntrySheet(state, editedRow, vm) { editorOpen = false; editorId = null }
    if (accountOpen) FinanceAccountSheet(state, state.snapshot.accounts.find { it.id == accountId }, vm) { accountOpen = false }
    if (accountsOpen) FinanceAccountsSheet(state, onAdd = { accountsOpen = false; accountId = null; accountOpen = true },
        onEdit = { accountsOpen = false; accountId = it; accountOpen = true }, onClose = { accountsOpen = false })
    if (datesOpen) FinanceDateSheet(state.error, vm::customRange) { datesOpen = false }
}

@Composable
private fun MoneySummary(label: String, totals: Map<String, FinanceTotals>, expense: Boolean, modifier: Modifier = Modifier) {
    val colors = LocalLifeOsColors.current
    Column(modifier) {
        Text(label, style = LifeText.caption, color = colors.muted)
        totals.ifEmpty { mapOf("CNY" to FinanceTotals()) }.forEach { (currency, sum) ->
            Text(FinanceMoney.display(if (expense) sum.expenseMinor else sum.incomeMinor, currency), style = LifeText.title, color = colors.ink)
        }
    }
}

@Composable
private fun LedgerLine(row: FinanceLedgerRow, onClick: () -> Unit) {
    val colors = LocalLifeOsColors.current
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(if (row.transfer != null) "${row.account.name} → ${row.targetAccount?.name}" else row.entry.description,
                style = LifeText.body, color = colors.ink)
            Text(if (row.transfer != null) "转账 · ${row.entry.description.ifBlank { "账户间转移" }}" else
                listOfNotNull(row.account.name, row.category).plus(row.tags.map { "#$it" }).joinToString(" · "),
                style = LifeText.caption, color = colors.muted)
        }
        val prefix = if (row.transfer != null) "" else if (row.entry.direction == FinanceDirection.INFLOW) "+" else "−"
        Text(prefix + FinanceMoney.display(row.entry.amountMinor, row.currencyCode), style = LifeText.body, color = colors.accentDeep)
    }
    HorizontalDivider(color = colors.line)
}

@Composable
private fun DimensionSummary(title: String, rows: List<FinanceDimensionTotal>) {
    val colors = LocalLifeOsColors.current
    if (rows.isEmpty()) return
    Text("$title · 当前范围", Modifier.padding(top = 16.dp, bottom = 6.dp), style = LifeText.title, color = colors.ink)
    rows.forEach { row ->
        Text("${row.label}  支出 ${FinanceMoney.display(row.totals.expenseMinor, row.currencyCode)} / 收入 ${FinanceMoney.display(row.totals.incomeMinor, row.currencyCode)}",
            Modifier.padding(vertical = 4.dp), style = LifeText.caption, color = colors.inkSecondary)
    }
    if (title == "标签") Text("同一笔可有多个标签，各标签金额不相加。", style = LifeText.caption, color = colors.muted)
}
