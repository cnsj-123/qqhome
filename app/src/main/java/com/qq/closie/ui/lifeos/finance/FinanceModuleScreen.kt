package com.qq.closie.ui.lifeos.finance

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.qq.closie.life.finance.*
import com.qq.closie.ui.lifeos.theme.*
import java.time.Instant
import java.time.ZoneId

/** Module-local destinations, deliberately independent from the Life OS NavHost. */
enum class FinanceView(val label: String) { OVERVIEW("总览"), TRANSACTIONS("流水"), ACCOUNTS("账户"), INSIGHTS("分析") }

@Composable
internal fun FinanceModuleScreen(state: FinanceUiState, vm: FinanceViewModel, onBack: () -> Unit,
    secondaryActions: @Composable () -> Unit = {}) {
    val colors = LocalLifeOsColors.current
    var viewName by rememberSaveable { mutableStateOf(FinanceView.OVERVIEW.name) }
    val view = FinanceView.valueOf(viewName)
    var detailAccount by rememberSaveable { mutableStateOf<String?>(null) }
    var entryId by rememberSaveable { mutableStateOf<String?>(null) }
    var editing by rememberSaveable { mutableStateOf(false) }
    var accountId by rememberSaveable { mutableStateOf<String?>(null) }
    var accountEditing by rememberSaveable { mutableStateOf(false) }
    var dates by rememberSaveable { mutableStateOf(false) }
    val allRows = remember(state.snapshot) { FinanceProjection.ledger(state.snapshot) }
    fun back() { if (detailAccount != null) detailAccount = null else onBack() }
    BackHandler(detailAccount != null && !editing && !accountEditing) { detailAccount = null }
    Scaffold(containerColor = colors.paper, topBar = {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = ::back) { Text("返回") }
            Text(detailAccount?.let { id -> state.snapshot.accounts.find { it.id == id }?.name } ?: "账目",
                Modifier.weight(1f), style = LifeText.title)
            TextButton(onClick = { entryId = null; editing = true },
                enabled = !state.busy && state.snapshot.accounts.any { it.archivedAt == null },
                modifier = Modifier.testTag("finance-add")) { Text("＋记一笔") }
        }
    }, bottomBar = {
        // This bar belongs to Finance only. The Life OS root has no bottom navigation.
        Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            FinanceView.entries.forEach { target ->
                FilterChip(view == target, onClick = { detailAccount = null; viewName = target.name },
                    label = { Text(target.label) })
            }
        }
    }) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize().testTag("finance-ledger"),
            contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (state.loading) item { Text("正在打开账本……", style = LifeText.body) }
            state.error?.let { message -> item { Text(message, color = colors.accentDeep); TextButton(vm::clearError) { Text("知道了") } } }
            if (detailAccount != null) {
                val account = state.snapshot.accounts.find { it.id == detailAccount }
                if (account != null) {
                    item {
                        Text(FinanceMoney.display(state.balances[account.id] ?: 0, account.currencyCode), style = LifeText.display)
                        TextButton({ accountId = account.id; accountEditing = true }) { Text("编辑账户") }
                    }
                    items(allRows.filter { it.account.id == account.id || it.targetAccount?.id == account.id }, key = { it.id }) {
                        LedgerLine(it) { entryId = it.id; editing = true }
                    }
                }
            } else when (view) {
                FinanceView.OVERVIEW -> {
                    item {
                        Text("这个月", style = LifeText.title)
                        Row {
                            MoneySummary("个人支出", state.month, true, Modifier.weight(1f))
                            MoneySummary("个人收入", state.month, false, Modifier.weight(1f))
                        }
                    }
                    item { secondaryActions() }
                    item {
                        Text("账户摘要", style = LifeText.title)
                        state.snapshot.accounts.filter { it.archivedAt == null }.forEach { account ->
                            TextButton({ detailAccount = account.id }) {
                                Text(account.name + "  " + FinanceMoney.display(state.balances[account.id] ?: 0, account.currencyCode))
                            }
                        }
                        if (state.snapshot.accounts.isEmpty()) TextButton({ accountId = null; accountEditing = true }) { Text("建立第一个账户") }
                    }
                    item { Text("最近流水", style = LifeText.title) }
                    items(allRows.take(6), key = { it.id }) { LedgerLine(it) { entryId = it.id; editing = true } }
                }
                FinanceView.ACCOUNTS -> {
                    item { TextButton({ accountId = null; accountEditing = true }) { Text("建立账户") } }
                    listOf(false, true).forEach { archived ->
                        item { Text(if (archived) "已归档" else "正在使用", style = LifeText.title) }
                        items(state.snapshot.accounts.filter { (it.archivedAt != null) == archived }, key = { it.id }) { account ->
                            OutlinedButton({ detailAccount = account.id }, Modifier.fillMaxWidth()) {
                                Column(Modifier.fillMaxWidth()) {
                                    Text(account.name + " · " + account.kind.label)
                                    Text(FinanceMoney.display(state.balances[account.id] ?: 0, account.currencyCode))
                                }
                            }
                        }
                    }
                }
                FinanceView.TRANSACTIONS, FinanceView.INSIGHTS -> {
                    item {
                        OutlinedTextField(state.filter.query, vm::search, Modifier.fillMaxWidth(),
                            label = { Text("事由、分类、标签或账户") }, singleLine = true)
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(FinancePeriod.entries) { period ->
                                FilterChip(state.filter.period == period, {
                                    if (period == FinancePeriod.CUSTOM) dates = true else vm.selectPeriod(period)
                                }, label = { Text(period.label) })
                            }
                        }
                        if (state.filter.customLabel.isNotEmpty()) Text(state.filter.customLabel, style = LifeText.caption)
                        FinanceFilters(state, vm)
                    }
                    if (view == FinanceView.INSIGHTS) item {
                        Text("个人收支", style = LifeText.title)
                        MoneySummary("支出", state.selected, true)
                        MoneySummary("收入", state.selected, false)
                        DimensionSummary("分类", state.categories)
                        DimensionSummary("标签", state.tags)
                        DimensionSummary("账户", FinanceProjection.accountTotals(state.ledger))
                        Text("转账仅改变账户余额，不计入个人收支。", style = LifeText.caption)
                    } else {
                        item { Text("流水 · ${state.ledger.size} 笔", style = LifeText.title) }
                        state.ledger.groupBy { Instant.ofEpochMilli(it.entry.occurredAt).atZone(ZoneId.systemDefault()).toLocalDate() }
                            .forEach { (date, rows) ->
                                item(key = "date-$date") { Text(date.toString(), style = LifeText.caption, color = colors.muted) }
                                items(rows, key = { it.id }) { LedgerLine(it) { entryId = it.id; editing = true } }
                            }
                        if (state.ledger.isEmpty()) item { Text("这个范围还没有流水。", style = LifeText.body) }
                    }
                }
            }
        }
    }
    if (editing && !state.loading) FinanceEntrySheet(state, allRows.find { it.id == entryId }, vm) { editing = false }
    if (accountEditing) FinanceAccountSheet(state, state.snapshot.accounts.find { it.id == accountId }, vm) { accountEditing = false }
    if (dates) FinanceDateSheet(state.error, vm::customRange) { dates = false }
}

@Composable
private fun FinanceFilters(state: FinanceUiState, vm: FinanceViewModel) {
    ChoiceFilter("账户", state.filter.accountId, state.snapshot.accounts.map { it.id to it.name }, vm::filterAccount)
    ChoiceFilter("分类", state.filter.categoryId, state.snapshot.categories.map { it.id to it.name }, vm::filterCategory)
    ChoiceFilter("标签", state.filter.tagId, state.snapshot.tags.map { it.id to it.name }, vm::filterTag)
}

@Composable
internal fun ChoiceFilter(label: String, selected: String?, choices: List<Pair<String, String>>, onSelect: (String?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton({ expanded = true }) { Text("$label · " + (choices.find { it.first == selected }?.second ?: "全部")) }
        DropdownMenu(expanded, { expanded = false }) {
            DropdownMenuItem(text = { Text("全部") }, onClick = { onSelect(null); expanded = false })
            choices.forEach { (id, name) ->
                DropdownMenuItem(text = { Text(name) }, onClick = { onSelect(id); expanded = false })
            }
        }
    }
}
