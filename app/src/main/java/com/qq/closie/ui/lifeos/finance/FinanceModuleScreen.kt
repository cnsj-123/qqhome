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
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.layout
import com.qq.closie.life.finance.*
import com.qq.closie.ui.lifeos.theme.*
import com.qq.closie.ui.lifeos.components.lifeHorizontalSwipe
import java.time.Instant
import java.time.ZoneId

/** Module-local destinations, deliberately independent from the Life OS NavHost. */
enum class FinanceView(val label: String) { TRANSACTIONS("账簿"), ACCOUNTS("账户"), INSIGHTS("分析") }

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun FinanceModuleScreen(state: FinanceUiState, vm: FinanceViewModel, onBack: () -> Unit, onRelations: () -> Unit = {},
    secondaryActions: @Composable () -> Unit = {}) {
    val colors = LocalLifeOsColors.current
    val gesturePolicy=com.qq.closie.ui.lifeos.components.LocalFunctionPagePolicy.current
    val pager = rememberPagerState { FinanceView.entries.size }
    // Pager pages are lazy. Their scrolling state belongs to the module, not a mounted page.
    val listStates = listOf(rememberLazyListState(), rememberLazyListState(),rememberLazyListState())
    var collapseProgress by rememberSaveable { mutableFloatStateOf(0f) }
    val entryDrafts = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    val scope = rememberCoroutineScope()
    val reducedMotion = com.qq.closie.ui.lifeos.components.reducedLifeMotion()
    var collapseAnimation by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    fun setCalendarStop(stop:FinanceCalendarStop) {
        collapseAnimation?.cancel()
        val target = stop.progress
        if (reducedMotion) collapseProgress = target
        else collapseAnimation = scope.launch {
            androidx.compose.animation.core.animate(collapseProgress, target, animationSpec = androidx.compose.animation.core.tween(180)) { value, _ ->
                collapseProgress = value
            }
        }
    }
    fun setCollapsed(collapsed:Boolean) = setCalendarStop(if(collapsed) FinanceCalendarStop.WEEK else FinanceCalendarStop.MONTH)
    val view = FinanceView.entries[pager.currentPage]
    LaunchedEffect(pager.settledPage) { vm.activateSpace(FinanceView.entries[pager.settledPage]) }
    fun selectView(target: FinanceView) { scope.launch { if (reducedMotion) pager.scrollToPage(target.ordinal) else pager.animateScrollToPage(target.ordinal) } }
    var accountQuery by rememberSaveable { mutableStateOf("") }
    var collapsedAccounts by rememberSaveable { mutableStateOf(listOf<String>()) }
    var detailAccount by rememberSaveable { mutableStateOf<String?>(null) }
    var registerFilter by rememberSaveable(detailAccount,stateSaver=FinanceRegisterFilter.Saver) {mutableStateOf(FinanceRegisterFilter())}
    val fullRegister=remember(state.snapshot,detailAccount) {
        detailAccount?.takeIf {id->state.snapshot.accounts.any {it.id==id}}?.let {FinanceAccountProjection.register(state.snapshot,it)}.orEmpty()
    }
    val visibleRegister=remember(fullRegister,registerFilter,state.snapshot) {registerFilter.apply(fullRegister,state.snapshot)}
    var recordDestination by rememberSaveable(stateSaver = FinanceRecordDestination.Saver) {
        mutableStateOf<FinanceRecordDestination>(FinanceRecordDestination.Closed)
    }
    val editing = recordDestination is FinanceRecordDestination.Editing || recordDestination is FinanceRecordDestination.Creating
    val viewing = recordDestination is FinanceRecordDestination.Viewing
    val entryId = when (val destination = recordDestination) {
        is FinanceRecordDestination.Viewing -> destination.id
        is FinanceRecordDestination.Editing -> destination.id
        else -> null
    }
    var accountId by rememberSaveable { mutableStateOf<String?>(null) }
    var accountEditing by rememberSaveable { mutableStateOf(false) }
    var calendarMode by rememberSaveable { mutableStateOf(true) }
    // Independent of Insights/list range filters, and restored with the module's UI state.
    var calendarMonth by rememberSaveable { mutableStateOf(state.filter.range.startInclusive?.let {
        YearMonth.from(Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault())).toString()
    } ?: YearMonth.now().toString()) }
    var selectedDay by rememberSaveable { mutableStateOf<String?>(null) }
    var dates by rememberSaveable { mutableStateOf(false) }
    var amountFilterOpen by rememberSaveable {mutableStateOf(false)}
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
    com.qq.closie.ui.lifeos.components.SuspendFunctionPageSwipe(editing || viewing || accountEditing || dates || amountFilterOpen || monthPicker || queryMenu || filterDimension != null || detailAccount != null || chartLabel != null)
    val allRows = state.allRows
    val balancesAfter = remember(state.snapshot) { state.snapshot.accounts.flatMap { FinanceAccountProjection.register(state.snapshot, it.id) }.associate { it.entry.id to it.balanceMinor } }
    val groupedDays = remember(state.ledger) { state.ledger.groupBy { it.localDay() } }
    val roles = remember(state.snapshot.v4) { state.snapshot.v4?.activeLinks.orEmpty().groupBy { it.entryId }.mapValues { (_, links) ->
        links.map { it.role }.filter { it in setOf(FinanceFlowRole.REFUND, FinanceFlowRole.REIMBURSEMENT, FinanceFlowRole.SETTLEMENT) }.distinct().joinToString(" · ") { it.label }.ifBlank { null }
    } }
    fun back() { if (chartLabel != null) chartLabel = null else if (detailAccount != null) detailAccount = null else onBack() }
    BackHandler((detailAccount != null || chartLabel != null) && !editing && !viewing && !accountEditing) { back() }
    Scaffold(containerColor = colors.paper, topBar = {
        if (detailAccount == null && chartLabel == null) FinanceToolbar(
            monthLabel, if (view == FinanceView.ACCOUNTS) accountQuery else state.filter.query,
            if (view == FinanceView.ACCOUNTS) "搜索账户" else "搜索账目",
            ::back, { monthPicker = true }, { if (view == FinanceView.ACCOUNTS) accountQuery = it else vm.search(it) },
            { recordDestination = FinanceRecordDestination.Creating(java.util.UUID.randomUUID().toString()) }, !state.busy && state.snapshot.accounts.any { it.archivedAt == null }) {
            if (view != FinanceView.ACCOUNTS) Box {
                val active = state.filter.accountId != null || state.filter.categoryId != null || state.filter.tagId != null || state.filter.nature != null || state.filter.minimumMinor!=null || state.filter.maximumMinor!=null || state.filter.currencyCode!=null
                IconButton({ queryMenu = true }) { Icon(Icons.Outlined.FilterList, if (active) "筛选与日期，已筛选" else "筛选与日期",
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
                        DropdownMenuItem(text={Text("金额与币种 · "+(state.filter.currencyCode ?: "不限"))},onClick={queryMenu=false;amountFilterOpen=true})
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
            TextButton(onClick = { recordDestination = FinanceRecordDestination.Creating(java.util.UUID.randomUUID().toString()) },
                enabled = !state.busy && state.snapshot.accounts.any { it.archivedAt == null },
                modifier = Modifier.testTag("finance-add")) { Text("＋记一笔") }
        }
    }, bottomBar = {
        // This bar belongs to Finance only. The Life OS root has no bottom navigation.
        NavigationBar(modifier=Modifier.lifeHorizontalSwipe(onRight={ gesturePolicy?.requestDrawer?.invoke() }),
            containerColor = colors.card, tonalElevation = 0.dp, windowInsets = WindowInsets(0)) {
            FinanceView.entries.forEach { target ->
                NavigationBarItem(selected = view == target, onClick = { detailAccount = null; chartLabel = null; selectView(target) },
                    icon = { FinanceNavGlyph(target) }, label = { Text(target.label, style = LifeText.caption) },
                    colors = NavigationBarItemDefaults.colors(indicatorColor = colors.paperTertiary,
                        selectedIconColor = colors.accentDeep, selectedTextColor = colors.accentDeep))
            }
        }
    }) { padding ->
        HorizontalPager(pager, Modifier.padding(padding).fillMaxSize(),
            userScrollEnabled = !editing && !viewing && !accountEditing && !dates && !monthPicker && !queryMenu && filterDimension == null && detailAccount == null && chartLabel == null) { page ->
        val view = FinanceView.entries[page]
        val listState = listStates[page]
        val initialDistance = with(LocalDensity.current) { 240.dp.toPx() }
        var collapseDistance by remember { mutableFloatStateOf(initialDistance) }
        var calendarDragged by remember { mutableStateOf(false) }
        val calendarVisible = view == FinanceView.TRANSACTIONS && calendarMode && detailAccount == null && chartLabel == null
        val nested = remember(listState, calendarVisible, collapseDistance) { object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (!calendarVisible || source != NestedScrollSource.UserInput) return Offset.Zero
                val atTop = listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0
                if (available.y < 0 || atTop) {
                    collapseAnimation?.cancel()
                    val before = collapseProgress
                    collapseProgress = (collapseProgress - available.y / collapseDistance).coerceIn(0f, 1f)
                    if(before!=collapseProgress) calendarDragged=true
                    return Offset(0f, (before - collapseProgress) * collapseDistance)
                }
                return Offset.Zero
            }
            override suspend fun onPreFling(available:Velocity):Velocity {
                if(!calendarVisible || !calendarDragged) return Velocity.Zero
                calendarDragged=false
                setCalendarStop(financeCalendarRelease(collapseProgress,available.y))
                collapseAnimation?.join()
                return Velocity(0f,available.y)
            }
        } }
        Column(Modifier.fillMaxSize().nestedScroll(nested)) {
            if(view==FinanceView.TRANSACTIONS && detailAccount==null && chartLabel==null) {
                Column(Modifier.padding(horizontal=16.dp),verticalArrangement=Arrangement.spacedBy(2.dp)) {
                    FinancePeriodStrip(state.selected)
                    val net = remember(state.snapshot) { FinanceAccountProjection.netByCurrency(state.snapshot) }
                    TextButton({ selectView(FinanceView.ACCOUNTS) }) {
                        Text("账户净额 · " + net.entries.joinToString(" / ") { (currency, amount) -> FinanceMoney.display(amount, currency) },
                            style = LifeText.caption)
                    }
                    Row { TextButton(onRelations) { Text("往来") }; Column { secondaryActions() } }
                    Row {
                        FinanceCalendarStop.entries.forEach { stop ->
                            TextButton({setCalendarStop(stop)}) { Text(when(stop) {
                                FinanceCalendarStop.MONTH -> "月历"
                                FinanceCalendarStop.WEEK -> "周历"
                                FinanceCalendarStop.COLLAPSED -> "收起"
                            }) }
                        }
                    }
                }
            }
            if (calendarVisible) Box(Modifier.clipToBounds().layout { measurable,constraints ->
                val content=measurable.measure(constraints)
                val fraction=1f-((collapseProgress-.5f)*2f).coerceIn(0f,1f)
                layout(content.width,(content.height*fraction).toInt()) { content.placeRelative(0,0) }
            }) { FinanceMonthLedger(YearMonth.parse(calendarMonth), state.calendarRows,
                selectedDay?.let(LocalDate::parse), { month ->
                    selectMonth(month)
                }, { day -> selectedDay = day?.toString(); if (day == null) setCollapsed(false); scope.launch { listState.scrollToItem(0) } },
                collapseProgress = (collapseProgress*2f).coerceIn(0f,1f), onCollapse = ::setCollapsed,
                onCollapseDistance = { collapseDistance = (it*2f).coerceAtLeast(1f) }) }
        LazyColumn(Modifier.weight(1f).fillMaxWidth()
            .lifeHorizontalSwipe(enabled=view==FinanceView.TRANSACTIONS && page==pager.currentPage &&
                !editing && !viewing && !accountEditing && detailAccount==null && chartLabel==null,
                onRight={ gesturePolicy?.requestDrawer?.invoke() })
            .testTag(if (page == pager.currentPage) "finance-ledger" else "finance-ledger-$page"),
            state = listState, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (state.loading) item { Text("正在打开账本……", style = LifeText.body) }
            state.error?.let { message -> item { Text(message, color = colors.accentDeep); TextButton(vm::clearError) { Text("知道了") } } }
            if (detailAccount == null && chartLabel == null && view in setOf(FinanceView.TRANSACTIONS, FinanceView.INSIGHTS) && state.unassignedAdjustments.isNotEmpty()) {
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
                        TextButton({ recordDestination = FinanceRecordDestination.Viewing(adjustment.row.id) }) { Text("查看并整理流水") }
                    }
                }
            }
            if (chartLabel != null) {
                val categoryTree = FinanceCategoryTree(state.snapshot.categories)
                val matching = state.costRows.filter { (categoryTree.root(it.categoryId)?.name ?: "未分类") == chartLabel && it.currencyCode == chartCurrency }
                item { FinancePanel {
                    Text("个人成本构成 · $chartLabel", style = LifeText.title)
                    Text(FinanceMoney.display(FinanceCostProjection.total(matching).confirmedMinor,chartCurrency))
                    if(!FinanceCostProjection.total(matching).complete) Text("另有承担待确认，未按零计算。")
                } }
                items(matching,key={it.id}) { cost ->
                    FinanceCostLine(cost,state.snapshot) { id -> recordDestination = FinanceRecordDestination.Viewing(id) }
                }
            } else if (detailAccount != null) {
                val account = state.snapshot.accounts.find { it.id == detailAccount }
                if (account != null) {
                    item {
                        Text("旧账簿口径余额",style=LifeText.caption)
                        Text(FinanceMoney.display(state.balances[account.id] ?: 0, account.currencyCode), style = LifeText.number)
                        TextButton({ accountId = account.id; accountEditing = true }) { Text("编辑账户") }
                        FinanceAccountWorkbench(state,account,vm) {first,last->registerFilter=FinanceRegisterFilter(after=first.observedAt,through=last.observedAt,
                            posted=first.basis==FinanceBalanceBasis.KNOWN_POSTED)}
                        FinanceRegisterFilters(registerFilter,fullRegister.size,visibleRegister.size) {registerFilter=it}
                    }
                    items(visibleRegister,key={it.entry.id}) { register ->
                        Column {
                            Text(register.entry.description.ifBlank { "资金流水" }+" · "+FinanceMoney.display(register.entry.amountMinor,account.currencyCode))
                            Text(register.balanceMinor?.let { "逐笔余额 "+FinanceMoney.display(it,account.currencyCode) } ?: "起点以前历史，无可信逐笔余额",style=LifeText.caption)
                            TextButton({ recordDestination = FinanceRecordDestination.Viewing(state.snapshot.transfers.firstOrNull { it.outflowEntryId==register.entry.id || it.inflowEntryId==register.entry.id }?.id ?: register.entry.id) }) { Text("查看记录") }
                        }
                    }
                }
            } else when (view) {
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
                        if (expanded) items(group.chunked(2), key = { it.joinToString { account -> account.id } }) { pair ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                pair.forEach { account ->
                                    FinanceAccountCard(account, state.balances[account.id] ?: 0, account.kind.ordinal,
                                        Modifier.weight(1f), compact = true,
                                        netMinor = if (state.snapshot.v16?.accountPolicyRows.orEmpty().any {
                                            it.accountId == account.id && it.voidedAt == null &&
                                                it.liabilityConvention == FinanceLiabilityConvention.POSITIVE_DEBT
                                        }) FinanceAccountProjection.netContribution(state.snapshot, account) else null,
                                        signPending = FinanceAccountProjection.liabilitySignPending(state.snapshot, account),
                                        onLongClick = { accountId = account.id; accountEditing = true }
                                    ) { detailAccount = account.id }
                                }
                                if (pair.size == 1) Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                    if (accounts.isEmpty()) item { Text("没有匹配的账户", style = LifeText.caption) }
                }
                FinanceView.TRANSACTIONS, FinanceView.INSIGHTS -> {
                    if (view == FinanceView.INSIGHTS) {
                        item { FinancePeriodSummary(state.selected, state.previous) }
                        item { FinanceCostTrend(state.costRows) { start,end -> vm.customRange(start,end); selectedDay=null; calendarMode=false } }
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
                        item { Text("经济事项与个人成本",style=LifeText.title) }
                        val visibleCosts=state.costRows.filter { !calendarMode || selectedDay==null ||
                            Instant.ofEpochMilli(it.occurredAt).atZone(ZoneId.systemDefault()).toLocalDate().toString()==selectedDay }
                        items(visibleCosts,key={it.id}) { cost -> FinanceCostLine(cost,state.snapshot) { id -> recordDestination = FinanceRecordDestination.Viewing(id) } }
                        val shownDays = groupedDays.filterKeys { !calendarMode || selectedDay == null || it.toString() == selectedDay }
                        val heading = if (calendarMode && selectedDay != null) "$selectedDay 真实资金流水" else "真实资金流水"
                        item { Text("$heading · ${shownDays.values.sumOf { it.size }} 笔", style = LifeText.title) }
                        shownDays.forEach { (date, rows) ->
                                item(key = "date-$date") { Text(date.toString(), style = LifeText.caption, color = colors.muted) }
                                items(rows, key = { it.id }) { LedgerLine(it, roles[it.entry.id], state.snapshot, balancesAfter[it.entry.id]) { recordDestination = FinanceRecordDestination.Viewing(it.id) } }
                            }
                        if (shownDays.isEmpty()) item { Text("这一天或范围还没有流水。", style = LifeText.body) }
                    }
                }
            }
        }
        }
        }
    }
    if (viewing && state.loaded) FinanceRecordDetails(allRows.find { it.id == entryId }, state.snapshot,
        onEdit = { recordDestination = FinanceRecordDestination.Editing(it.id, it.entry.revision) },
        onClose = { recordDestination = FinanceRecordDestination.Closed })
    if (editing && state.loaded) {
        val destination = recordDestination
        val row = allRows.find { it.id == entryId }
        val invalid = destination is FinanceRecordDestination.Editing &&
            (row == null || row.entry.revision != destination.revision)
        if (invalid) FinanceReceipt("记录已变化", onClose = { recordDestination = FinanceRecordDestination.Closed }) {
            Text("这条账单已更新或失效，请关闭后重新打开。")
        } else {
            val draftKey = (destination as? FinanceRecordDestination.Creating)?.draftId ?: entryId ?: "new-entry"
            entryDrafts.SaveableStateProvider(draftKey) {
                FinanceEntrySheet(state, row, vm,
                    onSaved = { recordDestination = FinanceRecordDestination.Closed; entryDrafts.removeState(draftKey) },
                    onTerminalClose = { recordDestination = FinanceRecordDestination.Closed; entryDrafts.removeState(draftKey) }
                ) { recordDestination = FinanceRecordDestination.Closed }
            }
        }
    }
    if (accountEditing && state.loaded) FinanceAccountSheet(state, state.snapshot.accounts.find { it.id == accountId }, vm) { accountEditing = false }
    if (monthPicker) FinanceMonthPicker(month, { selectMonth(it); monthPicker = false }, { monthPicker = false },
        onRange = { monthPicker = false; dates = true })
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
    if(amountFilterOpen) FinanceAmountRangeSheet(state.filter,state.snapshot.accounts.map {it.currencyCode},vm::amountRange) {amountFilterOpen=false}
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
