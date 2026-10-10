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
fun FinanceRoute(repository: FinanceRepository, importRepository: FinanceImportRepository? = null, onBack: () -> Unit) {
    val vm: FinanceViewModel = viewModel(factory = PresentationFactory(FinanceViewModel::class.java) { FinanceViewModel(repository) })
    val state by vm.state.collectAsStateWithLifecycle()
    var importOpen by rememberSaveable { mutableStateOf(false) }
    if (importOpen && importRepository != null) FinanceImportScreen(importRepository, state.snapshot.accounts) { importOpen = false }
    else FinanceModuleScreen(state, vm, onBack) {
        if (importRepository != null) TextButton(onClick = { importOpen = true }) { Text("导入旧账") }
    }
}

@Composable
internal fun MoneySummary(label: String, totals: Map<String, FinanceTotals>, expense: Boolean, modifier: Modifier = Modifier) {
    val colors = LocalLifeOsColors.current
    Column(modifier) {
        Text(label, style = LifeText.caption, color = colors.muted)
        totals.ifEmpty { mapOf("CNY" to FinanceTotals()) }.forEach { (currency, sum) ->
            Text(FinanceMoney.display(if (expense) sum.expenseMinor else sum.incomeMinor, currency), style = LifeText.title, color = colors.ink)
        }
    }
}

@Composable
internal fun LedgerLine(row: FinanceLedgerRow, onClick: () -> Unit) {
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
