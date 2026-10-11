package com.qq.closie.ui.lifeos.finance

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.foundation.lazy.rememberLazyListState
import java.time.LocalDate
import java.time.YearMonth
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
    var calendarMode by rememberSaveable { mutableStateOf(true) }
    var selectedDay by rememberSaveable { mutableStateOf<String?>(null) }
    var dates by rememberSaveable { mutableStateOf(false) }
    var chartLabel by rememberSaveable { mutableStateOf<String?>(null) }
    var chartCurrency by rememberSaveable { mutableStateOf("CNY") }
    var reviewAdjustments by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()
    LaunchedEffect(viewName, calendarMode) {
        if (view == FinanceView.TRANSACTIONS && calendarMode) vm.ensureCalendarMonth()
    }
    LaunchedEffect(state.filter.range, calendarMode) {
        selectedDay?.let { day ->
            val time = LocalDate.parse(day).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            if (calendarMode && !state.filter.range.contains(time)) selectedDay = null
        }
    }
    LaunchedEffect(viewName, detailAccount, chartLabel) { listState.scrollToItem(0) }
    com.qq.closie.ui.lifeos.components.SuspendFunctionPageSwipe(editing || accountEditing || dates || detailAccount != null || chartLabel != null)
    val allRows = state.allRows
    val groupedDays = remember(state.ledger) { state.ledger.groupBy { it.localDay() } }
    val roles = remember(state.snapshot.v4) { state.snapshot.v4?.activeLinks.orEmpty().groupBy { it.entryId }.mapValues { (_, links) ->
        links.map { it.role }.filter { it in setOf(FinanceFlowRole.REFUND, FinanceFlowRole.REIMBURSEMENT, FinanceFlowRole.SETTLEMENT) }.distinct().joinToString(" · ") { it.label }.ifBlank { null }
    } }
    fun back() { if (chartLabel != null) chartLabel = null else if (detailAccount != null) detailAccount = null else onBack() }
    BackHandler((detailAccount != null || chartLabel != null) && !editing && !accountEditing) { back() }
    Scaffold(containerColor = colors.paper, topBar = {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = ::back) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回") }
            Text(chartLabel ?: detailAccount?.let { id -> state.snapshot.accounts.find { it.id == id }?.name } ?: "账目",
                Modifier.weight(1f), style = LifeText.title)
            TextButton(onClick = { entryId = null; editing = true },
                enabled = !state.busy && state.snapshot.accounts.any { it.archivedAt == null },
                modifier = Modifier.testTag("finance-add")) { Text("＋记一笔") }
        }
    }, bottomBar = {
        // This bar belongs to Finance only. The Life OS root has no bottom navigation.
        NavigationBar(containerColor = colors.card, tonalElevation = 0.dp, windowInsets = WindowInsets(0)) {
            FinanceView.entries.forEach { target ->
                NavigationBarItem(selected = view == target, onClick = { detailAccount = null; chartLabel = null; viewName = target.name; if (target == FinanceView.TRANSACTIONS && calendarMode) { vm.selectPeriod(FinancePeriod.MONTH); selectedDay = null } },
                    icon = { FinanceNavGlyph(target) }, label = { Text(target.label, style = LifeText.caption) },
                    colors = NavigationBarItemDefaults.colors(indicatorColor = colors.paperTertiary,
                        selectedIconColor = colors.accentDeep, selectedTextColor = colors.accentDeep))
            }
        }
    }) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize().testTag("finance-ledger"),
            state = listState, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (state.loading) item { Text("正在打开账本……", style = LifeText.body) }
            state.error?.let { message -> item { Text(message, color = colors.accentDeep); TextButton(vm::clearError) { Text("知道了") } } }
            if (detailAccount == null && chartLabel == null && view in setOf(FinanceView.OVERVIEW, FinanceView.INSIGHTS) && state.unassignedAdjustments.isNotEmpty()) {
                item {
                    FinancePanel(color = colors.warmSurface) {
                        Text("${state.unassignedAdjustments.size} 笔到账待整理", style = LifeText.body)
                        Text("真实到账已保留；整理归属后才能冲减个人成本。", style = LifeText.caption)
                        TextButton({ reviewAdjustments = !reviewAdjustments }) { Text(if (reviewAdjustments) "收起" else "查看并整理") }
                    }
                }
                if (reviewAdjustments) items(state.unassignedAdjustments, key = { "unassigned-${it.linkId}" }) { adjustment ->
                    Column {
                        Text(adjustment.role.label + " · " + FinanceMoney.display(adjustment.amountMinor, adjustment.row.currencyCode))
                        Text(adjustment.issue.label + " · " + adjustment.row.entry.description, style = LifeText.caption)
                        TextButton({ entryId = adjustment.row.entry.id; editing = true }) { Text("查看并整理流水") }
                    }
                }
            }
            if (chartLabel != null) {
                val matching = state.personalRows.filter { (it.category ?: "未分类") == chartLabel && it.currencyCode == chartCurrency }
                val ids = matching.map { it.entry.id }.toSet()
                item { FinancePanel {
                    Text("个人收支归属 · $chartLabel", style = LifeText.title)
                    MoneySummary("净支出", FinanceProjection.totals(matching), true)
                    Text("下方显示相关真实流水；退款、报销金额保留原值。", style = LifeText.caption)
                } }
                items(allRows.filter { it.entry.id in ids }, key = { it.id }) { LedgerLine(it) { entryId = it.id; editing = true } }
            } else if (detailAccount != null) {
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
                    item { FinanceMonthCard(state) { vm.selectPeriod(FinancePeriod.MONTH); selectedDay = null; calendarMode = true; viewName = FinanceView.TRANSACTIONS.name } }
                    item { FinanceBalanceSummary(state) }
                    item {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("钱的位置", Modifier.weight(1f), style = LifeText.title)
                            TextButton({ viewName = FinanceView.ACCOUNTS.name }) { Text("全部账户") }
                        }
                        FinanceAccountStrip(state) { detailAccount = it }
                        if (state.snapshot.accounts.none { it.archivedAt == null }) OutlinedButton({ accountId = null; accountEditing = true }) { Text("建立第一个账户") }
                    }
                    item { HorizontalDivider(color = colors.line); Column(Modifier.padding(vertical = 8.dp)) { secondaryActions() }; HorizontalDivider(color = colors.line) }
                    item { Text("最近流水", style = LifeText.title) }
                    if (allRows.isEmpty()) item { Text("记下第一笔，让日常收支慢慢清晰。", style = LifeText.body, color = colors.muted) }
                    items(allRows.take(6), key = { it.id }) { LedgerLine(it) { entryId = it.id; editing = true } }
                }
                FinanceView.ACCOUNTS -> {
                    item { FinanceBalanceSummary(state) }
                    item { FilledTonalButton({ accountId = null; accountEditing = true }) { Text("＋ 建立账户") } }
                    listOf(false, true).forEach { archived ->
                        item { Text(if (archived) "已归档" else "正在使用", style = LifeText.title) }
                        items(state.snapshot.accounts.filter { (it.archivedAt != null) == archived }, key = { it.id }) { account ->
                            FinanceAccountCard(account, state.balances[account.id] ?: 0,
                                state.snapshot.accounts.indexOf(account), Modifier.fillMaxWidth(), flat = true) { detailAccount = account.id }
                        }
                    }
                }
                FinanceView.TRANSACTIONS, FinanceView.INSIGHTS -> {
                    item {
                        val month = state.filter.range.startInclusive?.let { YearMonth.from(Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault())) } ?: YearMonth.now()
                        if (view == FinanceView.TRANSACTIONS) Row {
                            FilterChip(calendarMode, { calendarMode = true; selectedDay = null; vm.selectPeriod(FinancePeriod.MONTH) }, label = { Text("月历") })
                            Spacer(Modifier.width(8.dp))
                            FilterChip(!calendarMode, { calendarMode = false; selectedDay = null }, label = { Text("列表与范围") })
                        }
                        if (view == FinanceView.TRANSACTIONS && calendarMode) FinanceMonthLedger(month, state.ledger,
                            selectedDay?.let(LocalDate::parse), { m -> selectedDay = null; vm.customRange(m.atDay(1).toString(), m.atEndOfMonth().toString()) },
                            { selectedDay = it?.toString() })
                        else TextButton({ dates = true }) { Text(state.filter.customLabel.ifBlank { "选择日期范围" }) }
                        OutlinedTextField(state.filter.query, vm::search, Modifier.fillMaxWidth(),
                            label = { Text("搜索事由、分类、标签或账户") }, singleLine = true, shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp), leadingIcon = { Icon(Icons.Outlined.Search, null) })
                        if (view != FinanceView.TRANSACTIONS || !calendarMode) LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(FinancePeriod.entries) { period ->
                                FilterChip(state.filter.period == period, {
                                    if (period == FinancePeriod.CUSTOM) dates = true else vm.selectPeriod(period)
                                }, label = { Text(period.label) })
                            }
                        }
                        if (state.filter.customLabel.isNotEmpty()) Text(state.filter.customLabel, style = LifeText.caption)
                        FinanceFilters(state, vm)
                    }
                    if (view == FinanceView.INSIGHTS) {
                        item { FinancePanel { Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            MoneySummary("个人支出", state.selected, true, Modifier.weight(1f))
                            MoneySummary("个人收入", state.selected, false, Modifier.weight(1f))
                        } } }
                        item { FinanceTrend(state.personalRows) { start, end -> vm.customRange(start, end); selectedDay = null; calendarMode = true; viewName = FinanceView.TRANSACTIONS.name } }
                        item { FinanceCategoryChart(state.categories) { label, currency -> chartLabel = label; chartCurrency = currency } }
                        item { FinanceSection {
                            Text("账户真实流量", style = LifeText.title)
                            val real = FinancePersonalProjection.realTotals(state.ledger, state.filter.accountId)
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                MoneySummary("流出", real, true, Modifier.weight(1f)); MoneySummary("流入", real, false, Modifier.weight(1f))
                            }
                            Text("转账只改变账户余额；真实流量与个人收支分别展示。", style = LifeText.caption)
                        } }
                    } else {
                        item { Text("流水 · ${state.ledger.size} 笔", style = LifeText.title) }
                        groupedDays.filterKeys { !calendarMode || selectedDay == null || it.toString() == selectedDay }.forEach { (date, rows) ->
                                item(key = "date-$date") { Text(date.toString(), style = LifeText.caption, color = colors.muted) }
                                items(rows, key = { it.id }) { LedgerLine(it, roles[it.entry.id]) { entryId = it.id; editing = true } }
                            }
                        if (state.ledger.isEmpty() || (calendarMode && selectedDay != null && groupedDays.keys.none { it.toString() == selectedDay })) item { Text("这一天或范围还没有流水。", style = LifeText.body) }
                    }
                }
            }
        }
    }
    if (editing && !state.loading) FinanceEntrySheet(state, allRows.find { it.id == entryId }, vm) { editing = false }
    if (accountEditing) FinanceAccountSheet(state, state.snapshot.accounts.find { it.id == accountId }, vm) { accountEditing = false }
    if (dates) FinanceDateSheet(state.error, vm::customRange, { dates = false },
        initial = state.filter.range.startInclusive?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate() } ?: LocalDate.now(),
        initialEnd = state.filter.range.endExclusive?.let { Instant.ofEpochMilli(it - 1).atZone(ZoneId.systemDefault()).toLocalDate() } ?: LocalDate.now(),
        transactionDays = remember(allRows) { allRows.map { it.localDay() }.toSet() })
}

@Composable
private fun FinanceFilters(state: FinanceUiState, vm: FinanceViewModel) {
    val categoryChoices = remember(state.snapshot.categories) { state.snapshot.categories.map {
        it.id to (FinanceProjection.categoryPath(state.snapshot.categories, it.id) ?: it.name)
    } }
    val common = remember(state.snapshot.entries) { state.snapshot.entries.filter { it.voidedAt == null }
        .mapNotNull { it.categoryId }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(6).map { it.key } }
    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        item { ChoiceFilter("账户", state.filter.accountId, state.snapshot.accounts.map { it.id to it.name }, onSelect = vm::filterAccount) }
        item { ChoiceFilter("分类", state.filter.categoryId, categoryChoices, common, vm::filterCategory) }
        item { ChoiceFilter("标签", state.filter.tagId, state.snapshot.tags.map { it.id to it.name }, onSelect = vm::filterTag) }
        item { ChoiceFilter("性质", state.filter.nature?.name, FinanceNature.entries.map { it.name to it.label }) { vm.filterNature(it?.let(FinanceNature::valueOf)) } }
    }
}

@Composable
internal fun ChoiceFilter(label: String, selected: String?, choices: List<Pair<String, String>>,
    common: List<String> = emptyList(), onSelect: (String?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    InputChip(selected != null, { expanded = true }, label = { Text(label + " · " + (choices.find { it.first == selected }?.second ?: "全部"), maxLines = 1) })
    if (expanded) FinanceChoiceSheet(label, selected, choices, { onSelect(it) }, { expanded = false }, { onSelect(null) }, common)
}
