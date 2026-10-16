package com.qq.closie.ui.lifeos.finance

import com.qq.closie.life.ui.theme.BundledText as Text

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.launch
import androidx.compose.foundation.lazy.LazyColumn
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

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun FinanceModuleScreen(state: FinanceUiState, vm: FinanceViewModel, onBack: () -> Unit, onRelations: () -> Unit = {},
    secondaryActions: @Composable () -> Unit = {}) {
    val colors = LocalLifeOsColors.current
    val pager = rememberPagerState { FinanceView.entries.size }
    // Pager pages are lazy. Their scrolling state belongs to the module, not a mounted page.
    val listStates = listOf(rememberLazyListState(), rememberLazyListState(),
        rememberLazyListState(), rememberLazyListState())
    var collapseProgress by rememberSaveable { mutableFloatStateOf(0f) }
    val entryDrafts = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    val scope = rememberCoroutineScope()
    val reducedMotion = com.qq.closie.ui.lifeos.components.reducedLifeMotion()
    var collapseAnimation by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    fun setCollapsed(collapsed: Boolean) {
        collapseAnimation?.cancel()
        val target = if (collapsed) 1f else 0f
        if (reducedMotion) collapseProgress = target
        else collapseAnimation = scope.launch {
            androidx.compose.animation.core.animate(collapseProgress, target, animationSpec = androidx.compose.animation.core.tween(180)) { value, _ ->
                collapseProgress = value
            }
        }
    }
    val view = FinanceView.entries[pager.currentPage]
    fun selectView(target: FinanceView) { scope.launch { if (reducedMotion) pager.scrollToPage(target.ordinal) else pager.animateScrollToPage(target.ordinal) } }
    var accountQuery by rememberSaveable { mutableStateOf("") }
    var collapsedAccounts by rememberSaveable { mutableStateOf(listOf<String>()) }
    var detailAccount by rememberSaveable { mutableStateOf<String?>(null) }
    var entryId by rememberSaveable { mutableStateOf<String?>(null) }
    var editing by rememberSaveable { mutableStateOf(false) }
    var accountId by rememberSaveable { mutableStateOf<String?>(null) }
    var accountEditing by rememberSaveable { mutableStateOf(false) }
    var calendarMode by rememberSaveable { mutableStateOf(true) }
    // Independent of Insights/list range filters, and restored with the module's UI state.
    var calendarMonth by rememberSaveable { mutableStateOf(state.filter.range.startInclusive?.let {
        YearMonth.from(Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault())).toString()
    } ?: YearMonth.now().toString()) }
    var selectedDay by rememberSaveable { mutableStateOf<String?>(null) }
    var dates by rememberSaveable { mutableStateOf(false) }
    var monthPicker by rememberSaveable { mutableStateOf(false) }
    var queryMenu by remember { mutableStateOf(false) }
    var filterDimension by remember { mutableStateOf<FinanceFilterDimension?>(null) }
    val month = YearMonth.parse(calendarMonth)
    val monthlyRange = state.filter.range == FinanceDateRange.month(month.atDay(1))
    val rangeLabel = when {
        monthlyRange -> "${month.year} 年 ${month.monthValue} 月"
        state.filter.period == FinancePeriod.ALL -> "全部日期"
        state.filter.period == FinancePeriod.YEAR -> "${LocalDate.now().year} 年"
        else -> state.filter.customLabel.ifBlank { "所选日期" }
    }
    val monthLabel = when {
        monthlyRange -> "${month.year}年\n${month.monthValue}月"
        state.filter.period == FinancePeriod.ALL -> "全部\n日期"
        state.filter.period == FinancePeriod.YEAR -> "${LocalDate.now().year}年\n全年"
        else -> "日期\n范围"
    }
    fun selectMonth(value: YearMonth) {
        calendarMonth = value.toString(); selectedDay = null
        vm.ensureCalendarMonth(value)
    }
    var chartLabel by rememberSaveable { mutableStateOf<String?>(null) }
    var chartCurrency by rememberSaveable { mutableStateOf("CNY") }
    var reviewAdjustments by rememberSaveable { mutableStateOf(false) }
    var previousDetail by rememberSaveable { mutableStateOf<String?>(detailAccount) }
    var previousChart by rememberSaveable { mutableStateOf<String?>(chartLabel) }
    LaunchedEffect(detailAccount, chartLabel) {
        if (detailAccount != previousDetail || chartLabel != previousChart) {
            listStates[pager.currentPage].scrollToItem(0)
            previousDetail = detailAccount; previousChart = chartLabel
        }
    }
    // Animated tab selection may pass across Transactions without entering it.
    // Apply its calendar range only once that destination actually settles.
    LaunchedEffect(pager.settledPage, calendarMode, calendarMonth) {
        if (FinanceView.entries[pager.settledPage] == FinanceView.TRANSACTIONS && calendarMode)
            vm.ensureCalendarMonth(YearMonth.parse(calendarMonth))
    }
    LaunchedEffect(calendarMonth) {
        selectedDay?.let { day ->
            if (YearMonth.from(LocalDate.parse(day)).toString() != calendarMonth) selectedDay = null
        }
    }
    com.qq.closie.ui.lifeos.components.SuspendFunctionPageSwipe(editing || accountEditing || dates || monthPicker || queryMenu || filterDimension != null || detailAccount != null || chartLabel != null)
    val allRows = state.allRows
    val groupedDays = remember(state.ledger) { state.ledger.groupBy { it.localDay() } }
    val roles = remember(state.snapshot.v4) { state.snapshot.v4?.activeLinks.orEmpty().groupBy { it.entryId }.mapValues { (_, links) ->
        links.map { it.role }.filter { it in setOf(FinanceFlowRole.REFUND, FinanceFlowRole.REIMBURSEMENT, FinanceFlowRole.SETTLEMENT) }.distinct().joinToString(" · ") { it.label }.ifBlank { null }
    } }
    fun back() { if (chartLabel != null) chartLabel = null else if (detailAccount != null) detailAccount = null else onBack() }
    BackHandler((detailAccount != null || chartLabel != null) && !editing && !accountEditing) { back() }
    Scaffold(containerColor = colors.paper, topBar = {
        if (detailAccount == null && chartLabel == null) FinanceToolbar(
            monthLabel, if (view == FinanceView.ACCOUNTS) accountQuery else state.filter.query,
            if (view == FinanceView.ACCOUNTS) "搜索账户" else "搜索账目",
            ::back, { monthPicker = true }, { if (view == FinanceView.ACCOUNTS) accountQuery = it else vm.search(it) },
            { entryId = null; editing = true }, !state.busy && state.snapshot.accounts.any { it.archivedAt == null }) {
            Box {
                val active = state.filter.accountId != null || state.filter.categoryId != null || state.filter.tagId != null || state.filter.nature != null
                IconButton({ queryMenu = true }) { Icon(Icons.Outlined.MoreHoriz, if (active) "筛选与日期，已筛选" else "筛选与日期",
                    tint = if (active) colors.accentDeep else colors.ink) }
                DropdownMenu(queryMenu, { queryMenu = false }) {
                    DropdownMenuItem(text = { Text("日期 · $rangeLabel") }, onClick = { queryMenu = false; monthPicker = true })
                    if (view != FinanceView.ACCOUNTS) {
                        DropdownMenuItem(text = { Text("选择日期范围") }, onClick = {
                            queryMenu = false; dates = true
                        })
                        FinancePeriod.entries.filter { it != FinancePeriod.CUSTOM }.forEach { period ->
                            DropdownMenuItem(text = { Text(period.label) }, onClick = {
                                queryMenu = false
                                // Overview/Insights ranges do not replace the saved Transactions month/day.
                                if (view == FinanceView.TRANSACTIONS) {
                                    selectedDay = null
                                    calendarMode = period == FinancePeriod.MONTH
                                    if (calendarMode) selectMonth(YearMonth.now()) else vm.selectPeriod(period)
                                } else vm.selectPeriod(period)
                            })
                        }
                        if (view == FinanceView.TRANSACTIONS) DropdownMenuItem(
                            text = { Text(if (calendarMode) "切换到列表与范围" else "切换到月历") },
                            onClick = { queryMenu = false; calendarMode = !calendarMode; selectedDay = null
                                if (calendarMode) selectMonth(month) })
                        HorizontalDivider()
                        FinanceFilterDimension.entries.forEach { dimension ->
                            val selected = when (dimension) {
                                FinanceFilterDimension.ACCOUNT -> state.snapshot.accounts.find { it.id == state.filter.accountId }?.name
                                FinanceFilterDimension.CATEGORY -> FinanceProjection.categoryPath(state.snapshot.categories, state.filter.categoryId)
                                FinanceFilterDimension.TAG -> state.snapshot.tags.find { it.id == state.filter.tagId }?.name
                                FinanceFilterDimension.NATURE -> state.filter.nature?.label
                            }
                            DropdownMenuItem(text = { Text("${dimension.label} · ${selected ?: "全部"}") },
                                onClick = { queryMenu = false; filterDimension = dimension })
                        }
                    }
                    DropdownMenuItem(text = { Text("清除搜索") }, onClick = {
                        queryMenu = false; if (view == FinanceView.ACCOUNTS) accountQuery = "" else vm.search("")
                    })
                }
            }
        } else Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
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
                NavigationBarItem(selected = view == target, onClick = { detailAccount = null; chartLabel = null; selectView(target) },
                    icon = { FinanceNavGlyph(target) }, label = { Text(target.label, style = LifeText.caption) },
                    colors = NavigationBarItemDefaults.colors(indicatorColor = colors.paperTertiary,
                        selectedIconColor = colors.accentDeep, selectedTextColor = colors.accentDeep))
            }
        }
    }) { padding ->
        HorizontalPager(pager, Modifier.padding(padding).fillMaxSize(),
            userScrollEnabled = !editing && !accountEditing && !dates && !monthPicker && !queryMenu && filterDimension == null && detailAccount == null && chartLabel == null) { page ->
        val view = FinanceView.entries[page]
        val listState = listStates[page]
        val initialDistance = with(LocalDensity.current) { 240.dp.toPx() }
        var collapseDistance by remember { mutableFloatStateOf(initialDistance) }
        val calendarVisible = view == FinanceView.TRANSACTIONS && calendarMode && detailAccount == null && chartLabel == null
        val nested = remember(listState, calendarVisible, collapseDistance) { object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (!calendarVisible || source != NestedScrollSource.UserInput) return Offset.Zero
                val atTop = listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0
                if (available.y < 0 || atTop) {
                    collapseAnimation?.cancel()
                    val before = collapseProgress
                    collapseProgress = (collapseProgress - available.y / collapseDistance).coerceIn(0f, 1f)
                    return Offset(0f, (before - collapseProgress) * collapseDistance)
                }
                return Offset.Zero
            }
        } }
        Column(Modifier.fillMaxSize().nestedScroll(nested)) {
            if (calendarVisible) FinanceMonthLedger(YearMonth.parse(calendarMonth), state.calendarRows,
                selectedDay?.let(LocalDate::parse), { month ->
                    selectMonth(month)
                }, { day -> selectedDay = day?.toString(); if (day == null) setCollapsed(false); scope.launch { listState.scrollToItem(0) } },
                collapseProgress = collapseProgress, onCollapse = ::setCollapsed,
                onCollapseDistance = { collapseDistance = it.coerceAtLeast(1f) })
        LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag(if (page == pager.currentPage) "finance-ledger" else "finance-ledger-$page"),
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
                val categoryTree = FinanceCategoryTree(state.snapshot.categories)
                val matching = state.personalRows.filter { FinanceProjection.parentCategoryLabel(it, categoryTree) == chartLabel && it.currencyCode == chartCurrency }
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
                        Text(FinanceMoney.display(state.balances[account.id] ?: 0, account.currencyCode), style = LifeText.number)
                        TextButton({ accountId = account.id; accountEditing = true }) { Text("编辑账户") }
                    }
                    items(allRows.filter { it.account.id == account.id || it.targetAccount?.id == account.id }, key = { it.id }) {
                        LedgerLine(it) { entryId = it.id; editing = true }
                    }
                }
            } else when (view) {
                FinanceView.OVERVIEW -> {
                    item { FinanceMonthCard(state, rangeLabel) { selectedDay = null; calendarMode = monthlyRange; selectView(FinanceView.TRANSACTIONS) } }
                    item { FinanceBalanceSummary(state) }
                    item { FinanceFundsSummary(state.snapshot, onRelations) }
                    item {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("钱的位置", Modifier.weight(1f), style = LifeText.title)
                            TextButton({ selectView(FinanceView.ACCOUNTS) }) { Text("全部账户") }
                        }
                        FinanceAccountStrip(state) { detailAccount = it }
                        if (state.snapshot.accounts.none { it.archivedAt == null }) OutlinedButton({ accountId = null; accountEditing = true }) { Text("建立第一个账户") }
                    }
                    item { HorizontalDivider(color = colors.line); Column(Modifier.padding(vertical = 8.dp)) { secondaryActions() }; HorizontalDivider(color = colors.line) }
                    item { Text("最近流水", style = LifeText.title) }
                    if (state.ledger.isEmpty()) item { Text("所选日期和条件下还没有流水。", style = LifeText.body, color = colors.muted) }
                    items(state.ledger.take(6), key = { it.id }) { LedgerLine(it) { entryId = it.id; editing = true } }
                }
                FinanceView.ACCOUNTS -> {
                    item { FinanceBalanceSummary(state) }
                    item { FilledTonalButton({ accountId = null; accountEditing = true }) { Text("＋ 建立账户") } }
                    val accounts = state.snapshot.accounts.filter {
                        accountQuery.isBlank() || (it.name + " " + it.kind.label + " " + it.currencyCode).contains(accountQuery, true)
                    }
                    val groups = accounts.groupBy { (if (it.archivedAt != null) "已归档 · " else "") + it.kind.label }
                    groups.forEach { (label, group) ->
                        val expanded = accountQuery.isNotBlank() || label !in collapsedAccounts
                        item(key = "account-group-$label") {
                            TextButton({ collapsedAccounts = if (label in collapsedAccounts) collapsedAccounts - label else collapsedAccounts + label },
                                modifier = Modifier.fillMaxWidth()) {
                                Text("$label · ${group.size}", Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Start)
                                Text(if (expanded) "收起" else "展开")
                            }
                        }
                        if (expanded) items(group, key = { it.id }) { account ->
                            FinanceAccountCard(account, state.balances[account.id] ?: 0, account.kind.ordinal,
                                Modifier.fillMaxWidth(), flat = true) { detailAccount = account.id }
                        }
                    }
                    if (accounts.isEmpty()) item { Text("没有匹配的账户", style = LifeText.caption) }
                }
                FinanceView.TRANSACTIONS, FinanceView.INSIGHTS -> {
                    if (view == FinanceView.INSIGHTS) {
                        item { FinancePeriodSummary(state.selected, state.previous) }
                        item { FinanceTrend(state.personalRows) { start, end -> calendarMonth = YearMonth.from(LocalDate.parse(start)).toString(); vm.customRange(start, end); selectedDay = null; calendarMode = false; selectView(FinanceView.TRANSACTIONS) } }
                        item { FinanceCategoryChart(state.categories, state.previousCategories) { label, currency -> chartLabel = label; chartCurrency = currency } }
                        item { FinanceSection {
                            Text("账户真实流量", style = LifeText.title)
                            val real = FinancePersonalProjection.realTotals(state.ledger, state.filter.accountId)
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                MoneySummary("流出", real, true, Modifier.weight(1f)); MoneySummary("流入", real, false, Modifier.weight(1f))
                            }
                            Text("转账只改变账户余额；真实流量与个人收支分别展示。", style = LifeText.caption)
                        } }
                    } else {
                        val shownDays = groupedDays.filterKeys { !calendarMode || selectedDay == null || it.toString() == selectedDay }
                        val heading = if (calendarMode && selectedDay != null) "$selectedDay 流水" else "流水"
                        item { Text("$heading · ${shownDays.values.sumOf { it.size }} 笔", style = LifeText.title) }
                        shownDays.forEach { (date, rows) ->
                                item(key = "date-$date") { Text(date.toString(), style = LifeText.caption, color = colors.muted) }
                                items(rows, key = { it.id }) { LedgerLine(it, roles[it.entry.id]) { entryId = it.id; editing = true } }
                            }
                        if (shownDays.isEmpty()) item { Text("这一天或范围还没有流水。", style = LifeText.body) }
                    }
                }
            }
        }
        }
        }
    }
    if (editing && !state.loading) {
        val draftKey = entryId ?: "new-entry"
        entryDrafts.SaveableStateProvider(draftKey) {
            FinanceEntrySheet(state, allRows.find { it.id == entryId }, vm,
                onSaved = { editing = false; entryDrafts.removeState(draftKey) }) { editing = false }
        }
    }
    if (accountEditing) FinanceAccountSheet(state, state.snapshot.accounts.find { it.id == accountId }, vm) { accountEditing = false }
    if (monthPicker) FinanceMonthPicker(month, { selectMonth(it); monthPicker = false }, { monthPicker = false })
    filterDimension?.let { dimension ->
        val choices = when (dimension) {
            FinanceFilterDimension.ACCOUNT -> state.snapshot.accounts.map { it.id to it.name }
            FinanceFilterDimension.CATEGORY -> state.snapshot.categories.map { it.id to (FinanceProjection.categoryPath(state.snapshot.categories, it.id) ?: it.name) }
            FinanceFilterDimension.TAG -> state.snapshot.tags.map { it.id to it.name }
            FinanceFilterDimension.NATURE -> FinanceNature.entries.map { it.name to it.label }
        }
        val selected = when (dimension) {
            FinanceFilterDimension.ACCOUNT -> state.filter.accountId
            FinanceFilterDimension.CATEGORY -> state.filter.categoryId
            FinanceFilterDimension.TAG -> state.filter.tagId
            FinanceFilterDimension.NATURE -> state.filter.nature?.name
        }
        fun select(value: String?) { when (dimension) {
            FinanceFilterDimension.ACCOUNT -> vm.filterAccount(value)
            FinanceFilterDimension.CATEGORY -> vm.filterCategory(value)
            FinanceFilterDimension.TAG -> vm.filterTag(value)
            FinanceFilterDimension.NATURE -> vm.filterNature(value?.let(FinanceNature::valueOf))
        } }
        val common = if (dimension == FinanceFilterDimension.CATEGORY) state.snapshot.entries
            .filter { it.voidedAt == null }.mapNotNull { it.categoryId }.groupingBy { it }.eachCount()
            .entries.sortedByDescending { it.value }.take(6).map { it.key } else emptyList()
        FinanceChoiceSheet(dimension.label, selected, choices, { select(it) }, { filterDimension = null }, { select(null) }, common)
    }
    if (dates) FinanceDateSheet(state.error, { start, end ->
        if (vm.customRange(start, end)) {
            calendarMonth = YearMonth.from(LocalDate.parse(start)).toString()
            selectedDay = null; calendarMode = false
            true
        } else false
    }, { dates = false },
        initial = state.filter.range.startInclusive?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate() } ?: LocalDate.now(),
        initialEnd = state.filter.range.endExclusive?.let { Instant.ofEpochMilli(it - 1).atZone(ZoneId.systemDefault()).toLocalDate() } ?: LocalDate.now(),
        transactionDays = remember(allRows) { allRows.map { it.localDay() }.toSet() })
}
