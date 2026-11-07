package com.qq.closie.ui.lifeos.finance

import androidx.compose.foundation.Canvas
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.AnnotatedString
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.roundToInt
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qq.closie.life.finance.*
import com.qq.closie.life.ui.theme.BundledText as Text
import com.qq.closie.ui.lifeos.components.SuspendFunctionPageSwipe
import com.qq.closie.ui.lifeos.theme.*
import java.math.BigInteger
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Compact analysis over the existing projections. Every drill-down opens the original facts. */
@Composable
internal fun FinanceAnalysisOverview(
    state: FinanceUiState,
    vm: FinanceViewModel,
    rangeLabel: String,
    onCategory: (String, String, Boolean) -> Unit,
    onRange: (String, String) -> Unit
) {
    val colors = LocalLifeOsColors.current
    var page by rememberSaveable { mutableIntStateOf(0) }
    var showIncome by rememberSaveable { mutableStateOf(false) }
    var pageMenu by remember {mutableStateOf(false)}
    var preferredCurrency by rememberSaveable { mutableStateOf<String?>(null) }
    var editingBudget by rememberSaveable { mutableStateOf<String?>(null) }
    var workspace by rememberSaveable { mutableStateOf<String?>(null) }
    var diagnosticId by rememberSaveable { mutableStateOf<String?>(null) }
    val currencies = remember(state.costSummary, state.categories, state.cashSummary,state.undatedCostRows) {
        (state.costSummary.keys + state.categories.map { it.currencyCode } + state.cashSummary.keys + state.undatedCostRows.map {it.currencyCode}).distinct().sorted()
    }
    val currency = preferredCurrency?.takeIf { it in currencies } ?: currencies.firstOrNull()
    val now = remember(state.snapshot) { System.currentTimeMillis() }
    val diagnostics = remember(state.snapshot, now) { FinanceInsightsProjection.diagnostics(state.snapshot, now) }
    val budgets = remember(state.snapshot) { FinanceInsightsProjection.budgets(state.snapshot) }
    val relevantBudgets = remember(budgets, state.filter.range) {
        val range = state.filter.range
        budgets.filter { (range.endExclusive == null || it.budget.startAt < range.endExclusive) &&
            (range.startInclusive == null || it.budget.endExclusive > range.startInclusive) }
            .sortedByDescending { it.budget.startAt }
    }
    SuspendFunctionPageSwipe(editingBudget != null || workspace != null || diagnosticId != null)

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth().height(40.dp),verticalAlignment=Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                TextButton({pageMenu=true},contentPadding=PaddingValues(0.dp)) {Text(listOf("分类构成","收支趋势","预算")[page],style=LedgerText.caption,color=colors.inkSecondary)}
                DropdownMenu(pageMenu,{pageMenu=false}) {
                    listOf("分类构成","收支趋势","预算").forEachIndexed {index,label->DropdownMenuItem({Text(label)},{page=index;pageMenu=false})}
                    DropdownMenuItem({Text("保存视图与查询专题")},{workspace="queries";pageMenu=false})
                }
            }
            Row(Modifier.height(32.dp).background(colors.paperTertiary,RoundedCornerShape(13.dp)).padding(3.dp)) {
                listOf(false to "支出",true to "收入").forEach {(income,label)->
                    Box(Modifier.width(46.dp).fillMaxHeight().background(if(showIncome==income) colors.card else androidx.compose.ui.graphics.Color.Transparent,RoundedCornerShape(10.dp))
                        .clickable {showIncome=income},contentAlignment=Alignment.Center) {Text(label,style=LedgerText.caption,color=colors.ink)}
                }
            }
        }
        if(!showIncome && state.undatedCostRows.isNotEmpty()) Text("另有 ${state.undatedCostRows.size} 项成本日期待确认，无法归期；以下为已知期间小计。",style=LifeText.caption,color=colors.inkSecondary)
        if (currencies.size > 1) LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(currencies, key = { it }) { code -> FilterChip(currency == code, { preferredCurrency = code }, label = { Text(code) }) }
        }
        when (page) {
            0 -> {
                val rows = if (showIncome) FinanceProjection.parentCategoryTotals(state.ledger, state.snapshot.categories)
                    .filter { it.currencyCode == currency && it.totals.incomeMinor != 0L }
                    .map { it.copy(totals = FinanceTotals(expenseMinor = it.totals.incomeMinor)) }
                    else state.categories.filter { it.currencyCode == currency }
                val total = currency?.let { code -> if (showIncome) state.cashSummary[code]?.let {
                    val bounded=state.filter.range.startInclusive!=null || state.filter.range.endExclusive!=null
                    val unknown=if(bounded) state.calendarRows.count {row->row.currencyCode==code && row.entry.direction==FinanceDirection.INFLOW && row.entry.id in state.unknownCashDateIds} else 0
                    PersonalCostTotal(it.confirmed.incomeMinor, unknown)
                } else state.costSummary[code] }
                AnalysisCategoryDistribution(rows, total, currency, state, showIncome) { label, code -> onCategory(label, code, showIncome) }
                relevantBudgets.firstOrNull { it.budget.currencyCode == currency }?.let { progress ->
                    AnalysisBudgetLine(progress) { editingBudget = progress.budget.id }
                }
                if (relevantBudgets.none { it.budget.currencyCode == currency }) {
                    TextButton({ workspace = "budgets" }) { Text("设置可选预算", style = LedgerText.caption) }
                }
            }
            1 -> {
                if (showIncome) {
                    FinanceTrend(state.ledger.filter { it.entry.id !in state.unknownCashDateIds }, onRange)
                    val missingDates=maxOf(state.unknownCashDateCount,state.ledger.count {it.entry.id in state.unknownCashDateIds})
                    if (missingDates > 0) Text("另有 $missingDates 笔收付日期待确定，未放入月份趋势。", style = LifeText.caption)
                } else {
                    FinancePeriodSummary(state.costSummary, state.previousCosts,
                        dateUnknown=state.undatedCostRows.groupingBy {it.currencyCode}.eachCount(),
                        previousDateUnknown=state.previousUndatedCostRows.groupingBy {it.currencyCode}.eachCount())
                    FinanceCostTrend(state.costRows, state.unknownCashDateIds, onRange)
                }
            }
            2 -> {
                Text("预算按各自区间、分类和币种计算", style = LifeText.caption, color = colors.inkSecondary)
                if (relevantBudgets.isEmpty()) Text("这个范围还没有预算。", style = LedgerText.body, color = colors.ink)
                relevantBudgets.forEach { progress ->
                    key(progress.budget.id) { AnalysisBudgetLine(progress) { editingBudget = progress.budget.id } }
                }
                TextButton({ workspace = "budgets" }) { Text("建立预算与查看全部") }
            }
        }
        diagnostics.firstOrNull()?.let { diagnostic ->
            LedgerDashedRule(Modifier.padding(top=12.dp))
            TextButton({diagnosticId=diagnostic.id},contentPadding=PaddingValues(0.dp)) {
                Text("•  异常线索 · "+diagnostic.message,style=LedgerText.caption,color=colors.inkSecondary,maxLines=2)
            }
            LedgerDashedRule()
        }

    }
    editingBudget?.let { id ->
        state.snapshot.v16?.budgetRows.orEmpty().singleOrNull { it.id == id && it.voidedAt == null }?.let { budget ->
            key(id) { FinanceBudgetEditor(state, vm, budget) { editingBudget = null } }
        }
    }
    diagnostics.firstOrNull { it.id == diagnosticId }?.let { diagnostic ->
        key(diagnostic.id) { FinanceDiagnosticViewer(state, vm, diagnostic) { diagnosticId = null } }
    }
    workspace?.let { kind ->
        key(kind) {
            FinanceReceipt(if (kind == "queries") "查询与保存视图" else "预算与专题", state.busy, state.error,
                onClose = { workspace = null }) {
                if (kind == "queries") FinanceQueryWorkbench(state, vm) else FinanceInsightsWorkbench(state, vm)
            }
        }
    }
}

@Composable
private fun AnalysisCategoryDistribution(
    rows: List<FinanceDimensionTotal>,
    total: PersonalCostTotal?,
    currency: String?,
    state: FinanceUiState,
    income: Boolean,
    onCategory: (String, String) -> Unit
) {
    val colors = LocalLifeOsColors.current
    val ranked = remember(rows) { rows.sortedByDescending { it.totals.expenseMinor } }
    val positiveTotal = ranked.fold(BigInteger.ZERO) { sum, row ->
        sum + row.totals.expenseMinor.coerceAtLeast(0).toBigInteger()
    }.toDouble()
    val dateUnknown=if(income) total?.unknownCount ?: 0 else state.undatedCostRows.count {it.currencyCode==currency}
    val complete = total?.complete == true && rows.none { it.unknownCount > 0 } && dateUnknown==0
    val incomeRows=remember(state.ledger,currency) {state.ledger.filter {
        it.currencyCode==currency && it.transfer==null && it.entry.direction==FinanceDirection.INFLOW &&
            it.entry.statPolicy!=FinanceStatPolicy.EXCLUDE
    }}
    val costRows=remember(state.costRows,currency) {state.costRows.filter {it.currencyCode==currency}}
    val includesReductions=rows.any {it.totals.expenseMinor<0} || if(income) incomeRows.any {it.entry.amountMinor<0}
        else costRows.any {it.adjustment || (it.amountMinor ?: 0L)<0L}
    val showRing = complete && positiveTotal > 0 && !includesReductions
    val categoryCounts=remember(showRing,income,incomeRows,costRows,state.snapshot.categories) {
        if(!showRing) emptyMap() else {
            val tree=FinanceCategoryTree(state.snapshot.categories)
            if(income) incomeRows.groupingBy {FinanceProjection.parentCategoryLabel(it,tree)}.eachCount()
            else costRows.groupBy {tree.root(it.categoryId)?.name ?: "未分类"}.mapValues {(_,group)->
                group.distinctBy {it.eventId?.let {id->"event:$id"} ?: it.entryId?.let {id->"cash:$id"} ?: it.id}.size
            }
        }
    }
    val swatches = listOf(colors.accent, colors.soft, colors.warm, colors.secondary, colors.inkSecondary)
    if (currency == null || total == null) {
        Text(if(dateUnknown>0) "此币种目前只有日期待确认的记录。" else if (income) "这个范围还没有实际流入。" else "这个范围还没有个人成本。", style = LifeText.body, color = colors.ink)
        return
    }
    val textMeasurer=rememberTextMeasurer()
    val reduced=com.qq.closie.ui.lifeos.components.reducedLifeMotion()
    var revealed by remember(currency) {mutableStateOf(false)}
    LaunchedEffect(currency) {revealed=true}
    val growth by animateFloatAsState(if(revealed) 1f else 0f,tween(if(reduced) 0 else 500),label="category-sectors")
    val labelLayouts=remember(ranked,positiveTotal,textMeasurer) {ranked.filter {it.totals.expenseMinor>0}.map {row->
        textMeasurer.measure(AnnotatedString(row.label+(row.totals.expenseMinor/positiveTotal*100).roundToInt()+"%"),LedgerText.caption.copy(fontSize=10.sp,lineHeight=13.sp))
    }}
    if (showRing) Box(Modifier.fillMaxWidth().height(218.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxWidth().height(218.dp).semantics {
            contentDescription = currency + (if (income) "实际流入" else "个人成本") + "分类构成，明细提供准确金额及账单入口"
        }) {
            val stroke = 44.dp.toPx()
            val diameter = 166.dp.toPx() - stroke
            val center=Offset(size.width/2,size.height/2)
            var angle = -90f
            ranked.filter { it.totals.expenseMinor > 0 }.forEachIndexed { index, row ->
                val sweep = (row.totals.expenseMinor / positiveTotal * 360).toFloat()
                val sectorProgress=ledgerSectorProgress(growth,index)
                drawArc(swatches[index % swatches.size], angle, sweep*sectorProgress, false,
                    center-Offset(diameter/2,diameter/2), Size(diameter,diameter), style=Stroke(stroke))
                if(growth>.95f) {
                    val rad=(angle+sweep/2)*Math.PI.toFloat()/180f
                    val direction=if(cos(rad)>=0) 1f else -1f
                    val start=center+Offset(cos(rad),sin(rad))*83.dp.toPx()
                    val elbow=center+Offset(cos(rad),sin(rad))*104.dp.toPx()
                    val end=elbow+Offset(direction*12.dp.toPx(),0f)
                    drawLine(colors.inkSecondary,start,elbow,.5.dp.toPx());drawLine(colors.inkSecondary,elbow,end,.5.dp.toPx())
                    val layout=labelLayouts[index]
                    drawText(layout,color=colors.ink,topLeft=Offset(if(direction>0) end.x+3.dp.toPx() else end.x-layout.size.width-3.dp.toPx(),end.y-layout.size.height/2))
                }
                angle += sweep
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(if (income) "实际流入" else "个人成本", style = LifeText.caption, color = colors.inkSecondary)
            LedgerOdometer(FinanceMoney.display(total.confirmedMinor, currency), LedgerText.rowAmount.copy(fontSize=18.sp), colors.ink)
        }
    } else {
        Text("已确认 " + FinanceMoney.display(total.confirmedMinor, currency), style = LifeText.number, color = colors.ink)
        if (income && dateUnknown>0) Text("另有 $dateUnknown 笔收付日期待确定",style=LifeText.caption,color=colors.inkSecondary)
        if (!income && total.unknownCount>0) Text("另有 ${total.unknownCount} 项承担待确认",style=LifeText.caption,color=colors.inkSecondary)
        if (!income && dateUnknown>0) Text("另有 $dateUnknown 项成本日期待确认，未计算完整期间构成。",style=LifeText.caption,color=colors.inkSecondary)
        if (includesReductions) Text("含本期退款或返利冲减，分类保留正负金额。", style = LifeText.caption, color = colors.inkSecondary)
    }
    ranked.forEachIndexed { index, row ->
        val root = state.snapshot.categories.firstOrNull { it.parentId == null && it.name == row.label }
        val icon = state.snapshot.v16?.categoryIconRows.orEmpty().firstOrNull {
            it.categoryId == root?.id && it.voidedAt == null
        }?.iconKey ?: FinanceCategoryVectors.labels.entries.firstOrNull { it.value == row.label }?.key ?: "other"
        Column(Modifier.fillMaxWidth().clickable { onCategory(row.label, currency) }.padding(vertical = 7.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(FinanceCategoryVectors.get(icon), null, Modifier.size(20.dp), tint = swatches[index % swatches.size])
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Row(horizontalArrangement=Arrangement.spacedBy(6.dp),verticalAlignment=Alignment.CenterVertically) {
                        Text(row.label, style = LedgerText.body, color = colors.ink)
                        if(showRing && row.totals.expenseMinor>0) categoryCounts[row.label]?.let {count->
                            val proportion=(row.totals.expenseMinor/positiveTotal*100).roundToInt()
                            Text("$proportion%  共 $count ${if(income) "笔" else "项"}",style=LedgerText.meta.copy(letterSpacing=.2.sp),color=colors.inkSecondary)
                        }
                    }
                    if (showRing && row.totals.expenseMinor > 0) Box(
                        Modifier.fillMaxWidth().height(4.dp).background(colors.paperTertiary, RoundedCornerShape(2.dp))
                    ) {
                        Box(Modifier.fillMaxWidth((row.totals.expenseMinor / positiveTotal).toFloat().coerceIn(0f, 1f))
                            .fillMaxHeight().background(swatches[index % swatches.size], RoundedCornerShape(2.dp)))
                    }
                    if (row.unknownCount > 0) Text("另有 " + row.unknownCount + " 项待确认", style = LifeText.caption, color = colors.inkSecondary)
                }
                Text(FinanceMoney.display(row.totals.expenseMinor, currency), style = LedgerText.rowAmount, color = colors.ink)
            }
        }
    }
}

@Composable
private fun AnalysisBudgetLine(progress: FinanceBudgetProgress, onClick: () -> Unit) {
    val colors = LocalLifeOsColors.current
    val budget = progress.budget
    val zone = ZoneId.systemDefault()
    val dateFormat = DateTimeFormatter.ofPattern("M月d日")
    val first = Instant.ofEpochMilli(budget.startAt).atZone(zone).format(dateFormat)
    val last = Instant.ofEpochMilli(budget.endExclusive - 1).atZone(zone).format(dateFormat)
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(budget.name, Modifier.weight(1f), style = LifeText.caption, color = colors.inkSecondary)
            Text(FinanceMoney.display(progress.used.confirmedMinor, budget.currencyCode) + " / " +
                FinanceMoney.display(budget.limitMinor, budget.currencyCode), style = LifeText.caption, color = colors.inkSecondary)
        }
        if (progress.used.complete && progress.unknownDateCount==0 && progress.used.confirmedMinor >= 0 && budget.limitMinor > 0) {
            val portion = (progress.used.confirmedMinor.toDouble() / budget.limitMinor).toFloat().coerceIn(0f, 1f)
            Box(Modifier.fillMaxWidth().height(6.dp).background(colors.paperTertiary, RoundedCornerShape(3.dp))) {
                Box(Modifier.fillMaxWidth(portion).fillMaxHeight().background(colors.warm, RoundedCornerShape(3.dp)))
            }
        }
        Text(first + "—" + last + " · " + if (progress.used.complete && progress.unknownDateCount==0) {
            "剩余 " + FinanceMoney.display(progress.remainingMinor, budget.currencyCode)
        } else {
            "已知小计，期间完整性待核对"
        }, style = LifeText.caption, color = colors.inkSecondary)
        if(progress.used.unknownCount>0) Text("另有 ${progress.used.unknownCount} 项承担待确定",style=LifeText.caption,color=colors.inkSecondary)
        if(progress.unknownDateCount>0) Text("另有 ${progress.unknownDateCount} 项成本日期待确认，不给出完整比例或剩余额结论。",style=LifeText.caption,color=colors.inkSecondary)
        if (progress.used.confirmedMinor < 0) Text(progress.explanation, style = LifeText.caption, color = colors.inkSecondary)
    }
}
