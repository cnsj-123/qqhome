package com.qq.closie.ui.lifeos.finance

import com.qq.closie.life.ui.theme.BundledText as Text

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.qq.closie.life.finance.*
import com.qq.closie.ui.lifeos.theme.LifeText

@Composable
internal fun FinanceFundsSummary(snapshot: FinanceSnapshot, onOpen: () -> Unit) {
    val matters = remember(snapshot) { FinanceRelationsProjection.matters(snapshot) }
    val pending = matters.filter { !it.completed }
    TextButton(onOpen, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text("资金事项 · ${pending.size} 待处理", style = LifeText.body)
            Text("报销 ${pending.count { it.event.nature == FinanceNature.REIMBURSABLE }} · 代付 ${pending.count { it.event.nature == FinanceNature.PROXY_PURCHASE }} · 分账 ${pending.count { it.event.nature == FinanceNature.SPLIT }}", style = LifeText.caption)
        }
        Text("查看 →")
    }
}

@Composable
internal fun FinanceRelationsScreen(state: FinanceUiState, vm: FinanceViewModel, onBack: () -> Unit) {
    var natureName by rememberSaveable { mutableStateOf<String?>(null) }
    var status by rememberSaveable { mutableIntStateOf(0) }
    var expanded by rememberSaveable { mutableStateOf<String?>(null) }
    var editId by rememberSaveable { mutableStateOf<String?>(null) }
    var returnEvent by rememberSaveable { mutableStateOf<String?>(null) }
    val nature = natureName?.let(FinanceNature::valueOf)
    val matters = remember(state.snapshot) { FinanceRelationsProjection.matters(state.snapshot) }
    val selected = matters.filter { (nature == null || it.event.nature == nature) && when (status) {
        0 -> !it.completed; 1 -> it.completed; else -> true
    } }
    val statusLabels = when (nature) {
        FinanceNature.REIMBURSABLE -> listOf("待报销", "已报销", "报销项目")
        FinanceNature.PROXY_PURCHASE -> listOf("待结算", "已结算", "代付项目")
        else -> listOf("进行中", "已完成", "项目")
    }
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        TextButton(onBack) { Text("返回") }
        Text("报销与代付", style = LifeText.title)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            item { FilterChip(nature == null, { natureName = null }, label = { Text("全部") }) }
            items(listOf(FinanceNature.REIMBURSABLE, FinanceNature.PROXY_PURCHASE, FinanceNature.SPLIT)) { target ->
                FilterChip(nature == target, { natureName = target.name }, label = {
                    Text(if (target == FinanceNature.PROXY_PURCHASE) "代付·代拍" else target.label)
                })
            }
        }
        Row { statusLabels.forEachIndexed { index, title ->
            TextButton({ status = index }) { Text(if (status == index) "● $title" else title) }
        } }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (selected.isEmpty()) item { Text("这里还没有${statusLabels[status]}。记账时选择业务性质，到账时关联到同一事项。", style = LifeText.body) }
            items(selected, key = { it.event.id }) { matter ->
                FinancePanel {
                    Row {
                        Text(matter.event.description, Modifier.weight(1f), style = LifeText.body)
                        Text(if (matter.event.nature == FinanceNature.PROXY_PURCHASE) "代付·代拍" else matter.event.nature.label, style = LifeText.caption)
                    }
                    fun money(value: Long) = FinanceMoney.display(value, matter.event.currencyCode)
                    Text("${if (matter.event.nature == FinanceNature.REIMBURSABLE) "原始垫付" else "实际付款"}  ${money(matter.paidMinor)}")
                    if (matter.feeMinor != 0L) Text("手续费  ${money(matter.feeMinor)}")
                    matter.returns.filterValues { it != 0L }.forEach { (role, amount) -> Text("${role.label}  ${money(amount)}") }
                    Text("${if (matter.pendingIsUpperBound) "待回款上限" else "待回款"}  ${matter.pendingMinor?.let(::money) ?: "预计金额待完善"}", style = LifeText.body)
                    if (matter.pendingIsUpperBound) Text("尚未确认个人承担额，暂按全部垫付显示上限；这不计入个人收支。", style = LifeText.caption)
                    Text("当前差额  ${if (matter.differenceMinor > 0) "+" else ""}${money(matter.differenceMinor)}", style = LifeText.caption)
                    Text("${matter.accounts.joinToString(" · ")} · ${matter.rows.size} 笔关联流水", style = LifeText.caption)
                    Row {
                        TextButton({ expanded = if (expanded == matter.event.id) null else matter.event.id }) { Text("关联流水") }
                        TextButton({ returnEvent = matter.event.id }, enabled = !state.busy) { Text("记录实际回款") }
                    }
                    if (expanded == matter.event.id) matter.rows.forEach { row -> LedgerLine(row) { editId = row.id } }
                }
            }
        }
    }
    editId?.let { id -> FinanceCanonicalRecordSheet(state, vm, id) { editId = null } }
    returnEvent?.let { id -> matters.find { it.event.id == id }?.let { matter ->
        FinanceEntrySheet(state, null, vm, initialRelationship = FinanceRelationshipDraft(
            nature = matter.event.nature, role = if (matter.event.nature == FinanceNature.REIMBURSABLE)
                FinanceFlowRole.REIMBURSEMENT else FinanceFlowRole.SETTLEMENT, eventId = id,
            eventUpdatedAt = matter.event.updatedAt), initialKind = FinanceEditorKind.INCOME) { returnEvent = null }
    } }
}
