package com.qq.closie.ui.lifeos.finance

import com.qq.closie.life.ui.theme.BundledText as Text

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
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect

@Composable
fun FinanceRoute(repository: FinanceRepository, onBack: () -> Unit) = FinanceRoute(repository, null, null, onBack)

@Composable
fun FinanceRoute(repository: FinanceRepository, importRepository: FinanceImportRepository?,
    automationRepository: FinanceAutomationRepository?, onBack: () -> Unit) {
    val vm: FinanceViewModel = viewModel(factory = remember(repository) {FinancePresentationFactory(repository)})
    val state by vm.state.collectAsStateWithLifecycle()
    var importOpen by rememberSaveable { mutableStateOf(false) }
    var automationOpen by rememberSaveable { mutableStateOf(false) }
    var relationsOpen by rememberSaveable { mutableStateOf(false) }
    var workspaceOpen by rememberSaveable {mutableStateOf(false)}
    val pending by produceState<Int?>(null, automationRepository) {
        automationRepository?.observe()?.catch { value = null }?.collect { snapshot ->
            value = snapshot.proposals.count { it.status in setOf(FinanceProposalStatus.ACTIVE, FinanceProposalStatus.SNOOZED) }
        }
    }
    com.qq.closie.ui.lifeos.components.SuspendFunctionPageSwipe(importOpen || automationOpen || relationsOpen || workspaceOpen)
    if (importOpen && importRepository != null) FinanceImportScreen(importRepository, state.snapshot.accounts) { importOpen = false }
    else if (automationOpen && automationRepository != null) FinanceAutomationScreen(automationRepository, repository) { automationOpen = false }
    else if (relationsOpen) FinanceRelationsScreen(state, vm) { relationsOpen = false }
    else if(workspaceOpen) FinanceWorkspaceScreen(state,vm) {workspaceOpen=false}
    else FinanceModuleScreen(state, vm, onBack, onRelations = { relationsOpen = true }) {
        if (automationRepository != null) TextButton(onClick = { automationOpen = true }) { Text("自动发现" + (pending?.let { " · $it 笔待确认" } ?: " · 查看状态")) }
        if (importRepository != null) TextButton(onClick = { importOpen = true }) { Text("导入旧账 →") }
        TextButton({workspaceOpen=true}) {Text("事项、购买、查询与隐私 →")}
    }
}

@Composable
internal fun MoneySummary(label: String, totals: Map<String, FinanceTotals>, expense: Boolean, modifier: Modifier = Modifier) {
    val colors = LocalLifeOsColors.current
    Column(modifier) {
        Text(label, style = LifeText.caption, color = colors.muted)
        totals.ifEmpty { mapOf("CNY" to FinanceTotals()) }.forEach { (currency, sum) ->
            Text(FinanceMoney.display(if (expense) sum.expenseMinor else sum.incomeMinor, currency), style = LifeText.number, color = colors.ink)
        }
    }
}

@Composable
internal fun LedgerLine(row: FinanceLedgerRow, role: String? = null, snapshot: FinanceSnapshot? = null, balanceAfter: Long? = null, onClick: () -> Unit) {
    val colors = LocalLifeOsColors.current
    Surface(color = androidx.compose.ui.graphics.Color.Transparent) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Surface(color = androidx.compose.ui.graphics.Color.Transparent, shape = RoundedCornerShape(11.dp)) {
            Box(Modifier.size(34.dp), contentAlignment = Alignment.Center) {
                Icon(if (snapshot != null && row.entry.categoryId != null) FinanceCategoryGlyph.forCategory(snapshot, row.entry.categoryId, row.category.orEmpty()) else FinanceCategoryGlyph.forName(row.category.orEmpty()), null, Modifier.size(20.dp), tint = colors.accentDeep)
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(if (row.transfer != null) "${row.account.name} → ${row.targetAccount?.name}" else row.entry.description.ifBlank { row.category ?: if(row.entry.direction==FinanceDirection.INFLOW) "收入" else "支出" },
                style = LifeText.body, color = colors.ink, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            Text(if (row.transfer != null) "转账 · ${row.entry.description.ifBlank { "账户间转移" }}" else
                listOfNotNull(row.category ?: "未分类", row.account.name).joinToString(" · "),
                style = LifeText.caption, color = colors.muted, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            Text(FinanceTime.input(row.entry.occurredAt).substringAfter(' '), style = LifeText.caption, color = colors.muted)
            if (role != null) Text(role, style = LifeText.caption, color = colors.accentDeep)
        }
        val prefix = if (row.transfer != null) "" else if (row.entry.direction == FinanceDirection.INFLOW) "+" else "−"
        Column(Modifier.widthIn(max = 136.dp).padding(start = 8.dp), horizontalAlignment = Alignment.End) {
            Text(prefix + FinanceMoney.display(row.entry.amountMinor, row.currencyCode), style = com.qq.closie.life.ui.theme.LifeType.MoneyCompact, color = colors.ink,
                textAlign = androidx.compose.ui.text.style.TextAlign.End)
            if (snapshot != null) Text(balanceAfter?.let { "余额 " + FinanceMoney.display(it, row.currencyCode) } ?: "余额未确定", style = LifeText.caption, color = colors.inkSecondary)
        }
    }
    }
}

@Composable
internal fun DimensionSummary(title: String, rows: List<FinanceDimensionTotal>) {
    val colors = LocalLifeOsColors.current
    if (rows.isEmpty()) return
    Text("$title · 当前范围", Modifier.padding(top = 16.dp, bottom = 6.dp), style = LifeText.title, color = colors.ink)
    rows.forEach { row ->
        Text("${row.label}  支出 ${FinanceMoney.display(row.totals.expenseMinor, row.currencyCode)} / 收入 ${FinanceMoney.display(row.totals.incomeMinor, row.currencyCode)}",
            Modifier.padding(vertical = 4.dp), style = LifeText.caption, color = colors.inkSecondary)
    }
    if (title == "标签") Text("同一笔可有多个标签，各标签金额不相加。", style = LifeText.caption, color = colors.muted)
}
