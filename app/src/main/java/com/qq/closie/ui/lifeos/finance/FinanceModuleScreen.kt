package com.qq.closie.ui.lifeos.finance

import com.qq.closie.life.ui.theme.BundledText as Text

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
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
    onFinanceSettings: (() -> Unit)? = null,
    secondaryActions: @Composable () -> Unit = {}) {
    val colors = LocalLifeOsColors.current
    val requestedFilter by vm.filterInput.collectAsState()
    val rowOrigins=remember {mutableMapOf<String,com.qq.closie.ui.lifeos.components.LifeSurfaceOrigin>()}
    var ledgerViewport by remember {mutableStateOf(androidx.compose.ui.geometry.Rect.Zero)}
    var printingRecordId by remember {mutableStateOf<String?>(null)}
    var landingRecordId by remember {mutableStateOf<String?>(null)}
    val landingFlash=remember {androidx.compose.animation.core.Animatable(0f)}
    var recordOrigin by remember {mutableStateOf<com.qq.closie.ui.lifeos.components.LifeSurfaceOrigin?>(null)}
    val gesturePolicy=com.qq.closie.ui.lifeos.components.LocalFunctionPagePolicy.current
    val drawerDrag: ((Float, Boolean?) -> Unit)? = gesturePolicy?.let { policy ->
        { distance, settled -> policy.financeDrawerDrag?.invoke(distance, settled) }
    }
    val pager = rememberPagerState { FinanceView.entries.size }
    // Pager pages are lazy. Their scrolling state belongs to the module, not a mounted page.
    val listStates = listOf(rememberLazyListState(), rememberLazyListState(),rememberLazyListState())
    var collapseProgress by rememberSaveable { mutableFloatStateOf(.5f) }
    val entryDrafts = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    val scope = rememberCoroutineScope()
    val reducedMotion = com.qq.closie.ui.lifeos.components.reducedLifeMotion()
    LaunchedEffect(landingRecordId) {
        if(landingRecordId!=null) {
            landingFlash.snapTo(if(reducedMotion) 0f else 1f)
            landingFlash.animateTo(0f,androidx.compose.animation.core.tween(400))
            landingRecordId=null
        }
    }
    var collapseAnimation by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    fun setCalendarStop(stop:FinanceCalendarStop, velocity:Float = 0f) {
        collapseAnimation?.cancel()
        val target = stop.progress
        if (reducedMotion) collapseProgress = target
        else collapseAnimation = scope.launch {
            androidx.compose.animation.core.animate(collapseProgress, target, initialVelocity = velocity, animationSpec = LedgerMotion.SpringSpatial) { value, _ ->
                collapseProgress = value
            }
        }
    }
    fun setCollapsed(collapsed:Boolean) = setCalendarStop(if(collapsed) FinanceCalendarStop.WEEK else FinanceCalendarStop.MONTH)
    val view = FinanceView.entries[pager.currentPage]
    LaunchedEffect(pager.settledPage) { vm.activateSpace(FinanceView.entries[pager.settledPage]) }
    fun selectView(target: FinanceView) { scope.launch { if (reducedMotion) pager.scrollToPage(target.ordinal) else pager.animateScrollToPage(target.ordinal) } }
    var accountQuery by rememberSaveable { mutableStateOf("") }
    var accountSearchOpen by rememberSaveable { mutableStateOf(false) }
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
    var monthPicker by rememberSaveable { mutableStateOf(false) }
    var queryMenu by rememberSaveable { mutableStateOf(false) }
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
    var chartIncome by rememberSaveable { mutableStateOf(false) }
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
    com.qq.closie.ui.lifeos.components.SuspendFunctionPageSwipe(editing || viewing || accountEditing || dates || monthPicker || queryMenu || detailAccount != null || chartLabel != null)
    val allRows = state.allRows
    val balancesAfter = remember(state.snapshot) { state.snapshot.accounts.flatMap { FinanceAccountProjection.register(state.snapshot, it.id) }.associate { it.entry.id to it.balanceMinor } }
    val unknownCashDateIds = state.unknownCashDateIds
    val datedRows = remember(state.calendarRows, state.economicDates, unknownCashDateIds) {
        state.calendarRows.filter { it.entry.id !in unknownCashDateIds || it.entry.id in state.economicDates }
    }
    val groupedDays = remember(datedRows, state.economicDates, state.filter.range) {
        datedRows.filter { state.filter.range.contains(state.economicDates[it.entry.id] ?: it.entry.occurredAt) }
            .groupBy { it.localDay(state.economicDates) }
    }
    val undatedRows = remember(state.calendarRows, state.economicDates, unknownCashDateIds) {
        state.calendarRows.filter { it.entry.id in unknownCashDateIds && it.entry.id !in state.economicDates }
    }
    val noncashDays = remember(state.costRows, state.snapshot.v4, datedRows, state.economicDates) {
        unrepresentedEconomicCosts(state.costRows, state.snapshot, datedRows, state.economicDates)
            .groupBy { Instant.ofEpochMilli(it.occurredAt).atZone(ZoneId.systemDefault()).toLocalDate() }
    }
    val roles = remember(state.snapshot.v4) { state.snapshot.v4?.activeLinks.orEmpty().groupBy { it.entryId }.mapValues { (_, links) ->
        links.map { it.role }.filter { it in setOf(FinanceFlowRole.REFUND, FinanceFlowRole.REIMBURSEMENT, FinanceFlowRole.SETTLEMENT) }.distinct().joinToString(" · ") { it.label }.ifBlank { null }
    } }
    val pendingRelations = remember(state.snapshot) {
        FinanceRelationsProjection.matters(state.snapshot).count { !it.completed }
    }
    fun back() { if (chartLabel != null) chartLabel = null else if (detailAccount != null) detailAccount = null else onBack() }
    BackHandler((detailAccount != null || chartLabel != null) && !editing && !viewing && !accountEditing) { back() }
    Scaffold(containerColor = colors.paper, topBar = {
        if (detailAccount == null && chartLabel == null) {
            val actions: @Composable RowScope.() -> Unit = {
                IconButton({ if (view == FinanceView.ACCOUNTS) accountSearchOpen = true else queryMenu = true }, Modifier.size(32.dp)) {
                    Icon(Icons.Outlined.FactCheck, if(view == FinanceView.ACCOUNTS) "搜索账户" else "筛选与日期", Modifier.size(19.dp), tint = colors.accentDeep)
                }
                if (onFinanceSettings != null) IconButton(onFinanceSettings, Modifier.size(32.dp)) {
                    Icon(Icons.Outlined.Settings, "账目专项设置", Modifier.size(19.dp), tint = colors.inkSecondary)
                }
            }
            if (view == FinanceView.INSIGHTS) Row(Modifier.fillMaxWidth().statusBarsPadding().padding(top = 14.dp, start = 32.dp, end = 32.dp).height(48.dp),
                verticalAlignment = Alignment.CenterVertically) {
                LedgerFilterChip(true, { monthPicker = true }, Modifier.testTag("finance-date-control")) { Text(if(monthlyRange) month.year.toString()+"."+month.monthValue.toString().padStart(2,'0') else rangeLabel, style = LedgerText.caption) }
                Spacer(Modifier.width(8.dp))
                LedgerFilterChip(false, { queryMenu = true }) { Text(state.filter.accountId?.let { id -> state.snapshot.accounts.find {it.id==id}?.name } ?: "全部账户", style = LedgerText.caption) }
                Spacer(Modifier.width(8.dp))
                LedgerFilterChip(false, { queryMenu = true }, Modifier.weight(1f)) { Text(state.filter.categoryId?.let {id->state.snapshot.categories.find {it.id==id}?.name} ?: "全部分类", style = LedgerText.caption, maxLines=1) }
                actions()
            } else FinanceNetHeader(state, view == FinanceView.ACCOUNTS, requestedFilter.query, vm::search,
                onAccounts = { selectView(FinanceView.ACCOUNTS) },
                onNewAccount = { accountId = null; accountEditing = true }, actions = actions)
        } else Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = ::back) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回") }
            Text(chartLabel ?: detailAccount?.let { id -> state.snapshot.accounts.find { it.id == id }?.name } ?: "账目",
                Modifier.weight(1f), style = LifeText.title)
            TextButton(onClick = { recordDestination = FinanceRecordDestination.Creating(java.util.UUID.randomUUID().toString()) },
                enabled = !state.busy && state.snapshot.accounts.any { it.archivedAt == null },
                modifier = Modifier.testTag("finance-add")) { Text("＋记一笔") }
        }
    }, bottomBar = {
        FinanceBottomNavigation(view,
            canAdd = !state.busy && state.snapshot.accounts.any {it.archivedAt==null},
            showAdd = detailAccount==null && chartLabel==null,
            onSelect = { detailAccount=null; chartLabel=null; selectView(it) },
            onAdd = {recordDestination=FinanceRecordDestination.Creating(java.util.UUID.randomUUID().toString())},
            drawerDrag = drawerDrag, landingEvent = landingRecordId)
    }) { padding ->
        HorizontalPager(pager, Modifier.padding(padding).fillMaxSize(),
            userScrollEnabled = !editing && !viewing && !accountEditing && !dates && !monthPicker && !queryMenu && detailAccount == null && chartLabel == null) { page ->
        val view = FinanceView.entries[page]
        val listState = listStates[page]
        val density = LocalDensity.current
        val initialDistance = with(density) { 240.dp.toPx() }
        var collapseDistance by remember { mutableFloatStateOf(initialDistance) }
        var calendarDragged by remember { mutableStateOf(false) }
        val calendarVisible = view == FinanceView.TRANSACTIONS && calendarMode && detailAccount == null && chartLabel == null
        val nested = remember(listState, calendarVisible, collapseDistance, density) { object : NestedScrollConnection {
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
                setCalendarStop(financeCalendarRelease(collapseProgress, available.y), -available.y / collapseDistance)
                collapseAnimation?.join()
                return Velocity(0f,available.y)
            }
        } }
        Column(Modifier.fillMaxSize().nestedScroll(nested)) {
            Box(Modifier.fillMaxWidth().height(2.dp)) {
                if(requestedFilter!=state.filter) LinearProgressIndicator(Modifier.fillMaxWidth(),color=colors.accent)
            }
            if(view==FinanceView.TRANSACTIONS && detailAccount==null && chartLabel==null) {
                Column(Modifier.padding(horizontal=32.dp), verticalArrangement=Arrangement.spacedBy(14.dp)) {
                    FinancePeriodStrip(state.cashSummary, rangeLabel)
                    if (pendingRelations > 0 || onFinanceSettings == null) {
                        Column(Modifier.fillMaxWidth()) {
                            LedgerDashedRule()
                            Row(Modifier.fillMaxWidth().heightIn(min=40.dp).clickable(onClick=onRelations),verticalAlignment=Alignment.CenterVertically) {
                                Text("•", color=colors.warm, style=LedgerText.caption)
                                Text(if(pendingRelations>0) "  $pendingRelations 项往来待核对" else "往来", style=LedgerText.caption,color=colors.inkSecondary)
                            }
                            LedgerDashedRule()
                            if(onFinanceSettings==null) secondaryActions()
                        }
                    }
                }
                if(state.cashSummary.isEmpty() && pendingRelations==0) Spacer(Modifier.height(8.dp))
                FinanceCalendarHeading(month, FinanceCalendarStop.entries.minBy { kotlin.math.abs(it.progress-collapseProgress) },
                    {monthPicker=true}, {setCalendarStop(it)})
            }
            if (calendarVisible) Box(Modifier.clipToBounds().layout { measurable,constraints ->
                val content=measurable.measure(constraints)
                val fraction=1f-((collapseProgress-.5f)*2f).coerceIn(0f,1f)
                layout(content.width,(content.height*fraction).toInt()) { content.placeRelative(0,0) }
            }) { FinanceMonthLedger(YearMonth.parse(calendarMonth), datedRows,
                selectedDay?.let(LocalDate::parse), { month ->
                    selectMonth(month)
                }, { day -> selectedDay = day?.toString(); if (day == null) setCollapsed(false); scope.launch { listState.scrollToItem(0) } },
                collapseProgress = (collapseProgress*2f).coerceIn(0f,1f), onCollapse = ::setCollapsed,
                onCollapseDistance = { collapseDistance = (it*2f).coerceAtLeast(1f) }, economicDates = state.economicDates) }
        LazyColumn(Modifier.weight(1f).fillMaxWidth().onGloballyPositioned {
            if(view==FinanceView.TRANSACTIONS) ledgerViewport=it.boundsInWindow()
        }
            .lifeHorizontalSwipe(enabled=view==FinanceView.TRANSACTIONS && page==pager.currentPage &&
                !editing && !viewing && !accountEditing && detailAccount==null && chartLabel==null,
                onRightDrag = drawerDrag)
            .testTag(if (page == pager.currentPage) "finance-ledger" else "finance-ledger-$page"),
            state = listState, contentPadding = PaddingValues(horizontal = 32.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
                        TextButton({ recordOrigin=null;recordDestination = FinanceRecordDestination.Viewing(adjustment.row.id) }) { Text("查看并整理流水") }
                    }
                }
            }
            if (chartLabel != null) {
                val categoryTree = FinanceCategoryTree(state.snapshot.categories)
                if (chartIncome) {
                    val matching = state.ledger.filter { it.transfer == null && it.entry.direction == FinanceDirection.INFLOW &&
                        it.entry.statPolicy == FinanceStatPolicy.INCLUDE && it.currencyCode == chartCurrency &&
                        FinanceProjection.parentCategoryLabel(it, categoryTree) == chartLabel }
                    item { FinancePanel {
                        Text("实际资金流入 · $chartLabel", style = LifeText.title)
                        Text(FinanceMoney.display(exactSum(matching.map { it.entry.amountMinor }), chartCurrency))
                    } }
                    items(matching, key = { it.id }) { row ->
                        LedgerLine(row, roles[row.entry.id], state.snapshot, balancesAfter[row.entry.id],
                            showCashTime = row.entry.id !in unknownCashDateIds) {
                            recordOrigin = null; recordDestination = FinanceRecordDestination.Viewing(row.id)
                        }
                    }
                } else {
                val matching = state.costRows.filter { (categoryTree.root(it.categoryId)?.name ?: "未分类") == chartLabel && it.currencyCode == chartCurrency }
                item { FinancePanel {
                    Text("个人成本构成 · $chartLabel", style = LifeText.title)
                    Text(FinanceMoney.display(FinanceCostProjection.total(matching).confirmedMinor,chartCurrency))
                    if(!FinanceCostProjection.total(matching).complete) Text("另有承担待确认，未按零计算。")
                } }
                items(matching,key={it.id}) { cost ->
                    FinanceCostLine(cost,state.snapshot) { id -> recordOrigin=null;recordDestination = FinanceRecordDestination.Viewing(id) }
                }
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
                            TextButton({ recordOrigin=null;recordDestination = FinanceRecordDestination.Viewing(state.snapshot.transfers.firstOrNull { it.outflowEntryId==register.entry.id || it.inflowEntryId==register.entry.id }?.id ?: register.entry.id) }) { Text("查看记录") }
                        }
                    }
                }
            } else when (view) {
                FinanceView.ACCOUNTS -> {
                    val accounts = state.snapshot.accounts.filter {
                        accountQuery.isBlank() || (it.name + " " + it.kind.label + " " + it.currencyCode).contains(accountQuery, true)
                    }
                    val groups = accounts.groupBy {
                        when {
                            it.archivedAt != null -> "已归档"
                            it.kind == FinanceAccountKind.CREDIT -> "负债"
                            else -> "资产"
                        }
                    }
                    listOf("资产", "负债", "已归档").forEach { label ->
                        val group = groups[label].orEmpty()
                        if (group.isEmpty()) return@forEach
                        val expanded = accountQuery.isNotBlank() || (label in collapsedAccounts) == (label == "已归档")
                        item(key = "account-group-$label") {
                            TextButton({ collapsedAccounts = if (label in collapsedAccounts) collapsedAccounts - label else collapsedAccounts + label },
                                modifier = Modifier.fillMaxWidth()) {
                                Text(label, Modifier.weight(1f), style=LedgerText.caption, textAlign = androidx.compose.ui.text.style.TextAlign.Start)
                                Text(if(label=="已归档") (if(expanded) "收起" else "展开")+"（${group.size}）" else "${group.size} 个",style=LedgerText.caption)
                            }
                        }
                        if (expanded) items(group.chunked(2), key = { it.joinToString { account -> account.id } }) { pair ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
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
                    if (accounts.isEmpty()) item {
                        Column(Modifier.fillMaxWidth().padding(top=70.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(18.dp)) {
                            Icon(painterResource(com.qq.closie.R.drawable.lifeos_finance_ledger_empty_cat),null,Modifier.size(110.dp,96.dp),tint=colors.inkSecondary)
                            Text(if(accountQuery.isBlank()) "还没有账户" else "没有匹配的账户",style=LedgerText.title)
                            Text("从常用的一个账户开始，把日子慢慢记下来。",style=LedgerText.caption)
                            Button({accountId=null;accountEditing=true},Modifier.widthIn(max=259.dp).fillMaxWidth().height(42.dp),shape=RoundedCornerShape(10.dp)) {Text("新建账户",style=LedgerText.body)}
                        }
                    }
                }
                FinanceView.TRANSACTIONS, FinanceView.INSIGHTS -> {
                    if (view == FinanceView.INSIGHTS) {
                        item {
                            FinanceAnalysisOverview(state, vm, rangeLabel,
                                onCategory = { label, currency, income -> chartLabel = label; chartCurrency = currency; chartIncome = income },
                                onRange = { start, end -> vm.customRange(start, end) })
                        }
                    } else {
                        val shownDays = groupedDays.filterKeys { !calendarMode || selectedDay == null || it.toString() == selectedDay }
                        val selectedCosts = noncashDays.filterKeys { !calendarMode || selectedDay == null || it.toString() == selectedDay }
                        if (calendarMode && selectedDay != null) item {
                            Text("$selectedDay 真实资金流水 · ${shownDays.values.sumOf { it.size }} 笔", style = LifeText.caption)
                        }
                        (shownDays.keys + selectedCosts.keys).sortedDescending().forEach { date ->
                                item(key = "date-$date") {
                                    val label = when (date) {
                                        LocalDate.now() -> "今天 · $date"
                                        LocalDate.now().minusDays(1) -> "昨天 · $date"
                                        else -> date.toString()
                                    }
                                    Text(label, style = LedgerText.caption, color = colors.inkSecondary)
                                }
                                items(shownDays[date].orEmpty(), key = { it.id }) { row ->
                                    DisposableEffect(row.id){onDispose {
                                        rowOrigins.remove(row.id)
                                        if(entryId==row.id)recordOrigin=recordOrigin?.copy(rect=androidx.compose.ui.geometry.Rect.Zero)
                                    }}
                                    Box(Modifier.background(if(landingRecordId==row.id) colors.accent.copy(alpha=.12f*landingFlash.value) else androidx.compose.ui.graphics.Color.Transparent,RoundedCornerShape(8.dp))) {
                                    LedgerLine(row,roles[row.entry.id],state.snapshot,balancesAfter[row.entry.id],
                                        showCashTime = row.entry.id !in unknownCashDateIds,
                                        hidden=(viewing && entryId==row.id && recordOrigin!=null) || printingRecordId==row.id,onPosition={origin->
                                            rowOrigins[row.id]=origin
                                            if(viewing && entryId==row.id && recordOrigin!=null)recordOrigin=origin
                                        }) {
                                        recordOrigin=rowOrigins[row.id];recordDestination=FinanceRecordDestination.Viewing(row.id)
                                    }
                                    }
                                }
                                items(selectedCosts[date].orEmpty(), key = { it.id }) { cost ->
                                    FinanceCostLine(cost, state.snapshot) { id -> recordOrigin = null; recordDestination = FinanceRecordDestination.Viewing(id) }
                                }
                        }
                        if (selectedDay == null && undatedRows.isNotEmpty()) {
                            item { Text("日期待确认", style = LifeText.caption, color = colors.inkSecondary) }
                            items(undatedRows, key = { it.id }) { row ->
                                LedgerLine(row, roles[row.entry.id], state.snapshot, balancesAfter[row.entry.id], showCashTime = false) {
                                    recordOrigin = null
                                    recordDestination = FinanceRecordDestination.Viewing(row.id)
                                }
                            }
                        }
                        if (state.loaded && shownDays.isEmpty() && selectedCosts.isEmpty() && (selectedDay != null || undatedRows.isEmpty())) {
                            item { Column(Modifier.fillMaxWidth().padding(top=72.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(16.dp)) {
                                Icon(painterResource(com.qq.closie.R.drawable.lifeos_finance_ledger_empty_cat),null,Modifier.size(110.dp,96.dp),tint=colors.secondary)
                                Text("快来记一笔账吧",style=LedgerText.body,color=colors.inkSecondary)
                            } }
                        }
                    }
                }
            }
        }
        }
        }
    }
    if (viewing && state.loaded) FinanceRecordDetails(allRows.find { it.id == entryId }, state.snapshot,
        origin=recordOrigin,
        onEdit = { recordDestination = FinanceRecordDestination.Editing(it.id, it.entry.revision) },
        voidAction = { FinanceRecordVoidAction(it, state, vm) { recordDestination = FinanceRecordDestination.Closed } },
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
                    onSaved = { landingRecordId=printingRecordId;printingRecordId=null;recordDestination = FinanceRecordDestination.Closed; entryDrafts.removeState(draftKey) },
                    onTerminalClose = { printingRecordId=null;recordDestination = FinanceRecordDestination.Closed; entryDrafts.removeState(draftKey) },
                    onCommitted = {printingRecordId=it},
                    printDestination = {id->
                        rowOrigins[id]?.takeIf {origin->view==FinanceView.TRANSACTIONS && detailAccount==null && chartLabel==null && ledgerViewport.contains(origin.rect.center)}
                    }
                ) { recordDestination = FinanceRecordDestination.Closed }
            }
        }
    }
    if (accountEditing && state.loaded) FinanceAccountSheet(state, state.snapshot.accounts.find { it.id == accountId }, vm) { accountEditing = false }
    if(accountSearchOpen) AlertDialog(onDismissRequest={accountSearchOpen=false},title={Text("查找账户")},
        text={OutlinedTextField(accountQuery,{accountQuery=it},label={Text("搜索账户")},singleLine=true)},
        confirmButton={TextButton({accountSearchOpen=false}){Text("完成")}})
    if (monthPicker) FinanceMonthPicker(month, { selectMonth(it); monthPicker = false }, { monthPicker = false },
        onRange = { monthPicker = false; dates = true })
    if (queryMenu) FinanceQuerySheet(
        state = state,
        initialFilter = requestedFilter,
        calendarMode = calendarMode,
        calendarMonth = month,
        showCalendarControls = view == FinanceView.TRANSACTIONS,
        onApply = { filter, useCalendar ->
            vm.applyFilter(filter)
            if (view == FinanceView.TRANSACTIONS) {
                calendarMode = useCalendar
                selectedDay = null
                filter.range.startInclusive?.let {
                    calendarMonth = YearMonth.from(Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault())).toString()
                }
            }
            queryMenu = false
        },
        onSaveView = { name, spec, done ->
            val epoch = state.snapshot.v16?.controlRows?.singleOrNull()?.restoreEpoch ?: 0L
            vm.workspaceAction(epoch, done) { repository, token -> repository.saveQuery(token, name, spec) }
        },
        onClose = { queryMenu = false }
    )
    if (dates) FinanceDateSheet(state.error, { start, end ->
        if (vm.customRange(start, end)) {
            if (view == FinanceView.TRANSACTIONS) {
                calendarMonth = YearMonth.from(LocalDate.parse(start)).toString()
                selectedDay = null; calendarMode = false
            }
            true
        } else false
    }, { dates = false },
        initial = state.filter.range.startInclusive?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate() } ?: LocalDate.now(),
        initialEnd = state.filter.range.endExclusive?.let { Instant.ofEpochMilli(it - 1).atZone(ZoneId.systemDefault()).toLocalDate() } ?: LocalDate.now(),
        transactionDays = remember(allRows, state.economicDates, unknownCashDateIds) {
            allRows.filter { it.entry.id !in unknownCashDateIds || it.entry.id in state.economicDates }
                .map { it.localDay(state.economicDates) }.toSet()
        })
}
