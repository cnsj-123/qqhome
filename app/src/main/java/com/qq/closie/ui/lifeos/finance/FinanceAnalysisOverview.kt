package com.qq.closie.ui.lifeos.finance

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
    var preferredCurrency by rememberSaveable { mutableStateOf<String?>(null) }
    var editingBudget by rememberSaveable { mutableStateOf<String?>(null) }
    var workspace by rememberSaveable { mutableStateOf<String?>(null) }
    var diagnosticId by rememberSaveable { mutableStateOf<String?>(null) }
    val currencies = remember(state.costSummary, state.categories, state.cashSummary) {
        (state.costSummary.keys + state.categories.map { it.currencyCode } + state.cashSummary.keys).distinct().sorted()
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

    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(rangeLabel, Modifier.weight(1f), style = LifeText.caption, color = colors.inkSecondary)
            Row(Modifier.background(colors.paperTertiary, RoundedCornerShape(13.dp)).padding(3.dp)) {
                listOf("分类", "趋势", "预算").forEachIndexed { index, label ->
                    TextButton({ page = index }, contentPadding = PaddingValues(horizontal = 11.dp, vertical = 3.dp),
                        modifier = Modifier.heightIn(min = 40.dp)
                            .background(if (page == index) colors.card else androidx.compose.ui.graphics.Color.Transparent, RoundedCornerShape(10.dp))) {
                        Text(label, style = LifeText.caption, color = if (page == index) colors.ink else colors.inkSecondary)
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            FilterChip(!showIncome, { showIncome = false }, label = { Text("支出") })
            FilterChip(showIncome, { showIncome = true }, label = { Text("收入") })
        }
        Text(if (showIncome) "实际资金流入 · 收付发生日（含退款等到账）" else "个人净支出 · 经济发生日与实际调整日", style = LifeText.caption, color = colors.inkSecondary)
        if (currencies.size > 1) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            currencies.forEach { code -> FilterChip(currency == code, { preferredCurrency = code }, label = { Text(code) }) }
        }
        when (page) {
            0 -> {
                val rows = if (showIncome) FinanceProjection.parentCategoryTotals(state.ledger, state.snapshot.categories)
                    .filter { it.currencyCode == currency && it.totals.incomeMinor != 0L }
                    .map { it.copy(totals = FinanceTotals(expenseMinor = it.totals.incomeMinor)) }
                    else state.categories.filter { it.currencyCode == currency }
                val total = currency?.let { code -> if (showIncome) state.cashSummary[code]?.let {
                    PersonalCostTotal(it.confirmed.incomeMinor, it.unknownDateCount)
                } else state.costSummary[code] }
                AnalysisCategoryDistribution(rows, total, currency, state, showIncome) { label, code -> onCategory(label, code, showIncome) }
                relevantBudgets.firstOrNull { it.budget.currencyCode == currency }?.let { progress ->
                    AnalysisBudgetLine(progress) { editingBudget = progress.budget.id }
                }
                if (relevantBudgets.none { it.budget.currencyCode == currency }) {
                    TextButton({ workspace = "budgets" }) { Text("设置可选预算", style = LifeText.caption) }
                }
            }
            1 -> {
                if (showIncome) {
                    FinanceTrend(state.ledger.filter { it.entry.id !in state.unknownCashDateIds }, onRange)
                    if (state.unknownCashDateCount > 0) Text("另有 ${state.unknownCashDateCount} 笔收付日期待确定，未放入月份趋势。", style = LifeText.caption)
                } else {
                    FinancePeriodSummary(state.costSummary, state.previousCosts)
                    FinanceCostTrend(state.costRows, onRange)
                }
            }
            2 -> {
                Text("预算按各自区间、分类和币种计算", style = LifeText.caption, color = colors.inkSecondary)
                if (relevantBudgets.isEmpty()) Text("这个范围还没有预算。", style = LifeText.body, color = colors.ink)
                relevantBudgets.forEach { progress ->
                    key(progress.budget.id) { AnalysisBudgetLine(progress) { editingBudget = progress.budget.id } }
                }
                TextButton({ workspace = "budgets" }) { Text("建立预算与查看全部") }
            }
        }
        HorizontalDivider(color = colors.line)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("可核查线索", Modifier.weight(1f), style = LifeText.caption, color = colors.inkSecondary)
            TextButton({ workspace = "budgets" }) { Text(if (diagnostics.isEmpty()) "查看规则" else "全部 " + diagnostics.size + " 项", style = LifeText.caption) }
        }
        diagnostics.firstOrNull()?.let { diagnostic ->
            TextButton({ diagnosticId = diagnostic.id }, contentPadding = PaddingValues(0.dp)) {
                Text(diagnostic.message, style = LifeText.caption, color = colors.inkSecondary)
            }
        } ?: Text("当前没有符合规则的线索。", style = LifeText.caption, color = colors.inkSecondary)
        TextButton({ workspace = "queries" }) { Text("保存筛选视图与查询专题", style = LifeText.caption) }
        FinancePeriodStrip(state.cashSummary, rangeLabel)
        Text("上方资金收支按真实收付日期；转账不计入收支。", style = LifeText.caption, color = colors.inkSecondary)
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
    val complete = total?.complete == true && rows.none { it.unknownCount > 0 }
    val showRing = complete && positiveTotal > 0 && rows.none { it.totals.expenseMinor < 0 }
    val swatches = listOf(colors.accent, colors.soft, colors.warm, colors.secondary, colors.inkSecondary)
    if (currency == null || total == null) {
        Text(if (income) "这个范围还没有实际流入。" else "这个范围还没有个人成本。", style = LifeText.body, color = colors.ink)
        return
    }
    if (showRing) Box(Modifier.fillMaxWidth().height(222.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(212.dp).semantics {
            contentDescription = currency + "个人成本分类构成，明细提供准确金额及账单入口"
        }) {
            val stroke = 40.dp.toPx()
            val diameter = size.minDimension - stroke
            var angle = -90f
            ranked.filter { it.totals.expenseMinor > 0 }.forEachIndexed { index, row ->
                val sweep = (row.totals.expenseMinor / positiveTotal * 360).toFloat()
                drawArc(swatches[index % swatches.size], angle, sweep, false,
                    Offset(stroke / 2, stroke / 2), Size(diameter, diameter), style = Stroke(stroke))
                angle += sweep
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(if (income) "实际流入" else "个人成本", style = LifeText.caption, color = colors.inkSecondary)
            Text(FinanceMoney.display(total.confirmedMinor, currency), style = LifeText.body, color = colors.ink)
        }
    } else {
        Text("已确认 " + FinanceMoney.display(total.confirmedMinor, currency), style = LifeText.number, color = colors.ink)
        if (!complete) Text("另有 " + total.unknownCount + if (income) " 笔收付日期待确定" else " 项承担待确认", style = LifeText.caption, color = colors.inkSecondary)
        if (rows.any { it.totals.expenseMinor < 0 }) Text("含本期退款或返利冲减，分类保留正负金额。", style = LifeText.caption, color = colors.inkSecondary)
    }
    ranked.forEachIndexed { index, row ->
        val root = state.snapshot.categories.firstOrNull { it.parentId == null && it.name == row.label }
        val icon = state.snapshot.v16?.categoryIconRows.orEmpty().firstOrNull {
            it.categoryId == root?.id && it.voidedAt == null
        }?.iconKey ?: FinanceCategoryVectors.labels.entries.firstOrNull { it.value == row.label }?.key ?: "other"
        Column(Modifier.fillMaxWidth().clickable { onCategory(row.label, currency) }.padding(vertical = 9.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(FinanceCategoryVectors.get(icon), null, Modifier.size(20.dp), tint = swatches[index % swatches.size])
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(row.label, style = LifeText.body, color = colors.ink)
                    if (showRing && row.totals.expenseMinor > 0) Box(
                        Modifier.fillMaxWidth().height(4.dp).background(colors.paperTertiary, RoundedCornerShape(2.dp))
                    ) {
                        Box(Modifier.fillMaxWidth((row.totals.expenseMinor / positiveTotal).toFloat().coerceIn(0f, 1f))
                            .fillMaxHeight().background(swatches[index % swatches.size], RoundedCornerShape(2.dp)))
                    }
                    if (row.unknownCount > 0) Text("另有 " + row.unknownCount + " 项待确认", style = LifeText.caption, color = colors.inkSecondary)
                }
                Text(FinanceMoney.display(row.totals.expenseMinor, currency), style = LifeText.body, color = colors.ink)
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
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(budget.name, Modifier.weight(1f), style = LifeText.caption, color = colors.inkSecondary)
            Text(FinanceMoney.display(progress.used.confirmedMinor, budget.currencyCode) + " / " +
                FinanceMoney.display(budget.limitMinor, budget.currencyCode), style = LifeText.caption, color = colors.inkSecondary)
        }
        if (progress.used.complete && progress.used.confirmedMinor >= 0 && budget.limitMinor > 0) {
            val portion = (progress.used.confirmedMinor.toDouble() / budget.limitMinor).toFloat().coerceIn(0f, 1f)
            Box(Modifier.fillMaxWidth().height(6.dp).background(colors.paperTertiary, RoundedCornerShape(3.dp))) {
                Box(Modifier.fillMaxWidth(portion).fillMaxHeight().background(colors.warm, RoundedCornerShape(3.dp)))
            }
        }
        Text(first + "—" + last + " · " + if (progress.used.complete) {
            "剩余 " + FinanceMoney.display(progress.remainingMinor, budget.currencyCode)
        } else {
            "已确认小计，另有 " + progress.used.unknownCount + " 项承担待确定"
        }, style = LifeText.caption, color = colors.inkSecondary)
        if (progress.used.confirmedMinor < 0) Text(progress.explanation, style = LifeText.caption, color = colors.inkSecondary)
    }
}
