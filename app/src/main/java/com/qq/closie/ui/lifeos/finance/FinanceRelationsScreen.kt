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
import androidx.compose.ui.unit.sp
import androidx.compose.ui.Alignment
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import com.qq.closie.ui.lifeos.theme.LocalLifeOsColors
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
    val colors = LocalLifeOsColors.current
    Scaffold(containerColor = colors.paper, topBar = {
        FinanceAuxTopBar("往来", onBack) {
            TextButton({ natureName = null; status = 2 }, contentPadding = PaddingValues(0.dp)) { Text("全部", style = LedgerText.caption) }
        }
    }) { padding -> Column(Modifier.padding(padding).fillMaxSize().padding(horizontal = 32.dp)) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 6.dp)) {
            item { LedgerFilterChip(nature == null && status == 0, { natureName = null; status = 0 }, label = { Text("进行中", style = LedgerText.caption) }) }
            item { LedgerFilterChip(nature == null && status == 1, { natureName = null; status = 1 }, label = { Text("已结清", style = LedgerText.caption) }) }
            items(listOf(FinanceNature.REIMBURSABLE, FinanceNature.PROXY_PURCHASE, FinanceNature.SPLIT)) { target ->
                LedgerFilterChip(nature == target, { natureName = target.name; status = 2 }, label = {
                    Text(if (target == FinanceNature.PROXY_PURCHASE) "代付" else target.label, style = LedgerText.caption)
                })
            }
        }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(18.dp), contentPadding = PaddingValues(top = 12.dp, bottom = 32.dp)) {
            if (selected.isEmpty()) item { Text("这里还没有${statusLabels[status]}。记账时选择业务性质，到账时关联到同一事项。", style = LedgerText.body, color = colors.inkSecondary) }
            items(selected, key = { it.event.id }) { matter ->
                Column(Modifier.fillMaxWidth()) {
                    val isExpanded = expanded == matter.event.id
                    val received = exactSum(matter.returns.values)
                    val target = matter.pendingMinor?.takeIf { !matter.pendingIsUpperBound }?.let { exactSum(listOf(received, it)) }
                    val date = java.time.Instant.ofEpochMilli(matter.event.occurredAt).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
                    Column(Modifier.fillMaxWidth().clickable { expanded = if (isExpanded) null else matter.event.id }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(matter.event.description.ifBlank { matter.event.nature.label } + " · ${date.monthValue}月${date.dayOfMonth}日",
                            Modifier.weight(1f), style = LedgerText.caption, color = colors.inkSecondary)
                        Surface(color = Color.Transparent, shape = RoundedCornerShape(5.dp), border = BorderStroke(1.dp, colors.secondary.copy(alpha = .45f))) {
                            Text(when { matter.completed -> "已结清"; matter.pendingIsUpperBound -> "待核对"; matter.event.nature == FinanceNature.REIMBURSABLE -> "报销中"; else -> "待结算" },
                                Modifier.padding(horizontal = 5.dp, vertical = 1.dp), style = LedgerText.caption.copy(fontSize = 10.sp, lineHeight = 12.sp), color = colors.secondary)
                        }
                    }
                    fun money(value: Long) = FinanceMoney.display(value, matter.event.currencyCode)
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            LedgerOdometer((if (matter.event.nature == FinanceNature.PROXY_PURCHASE) "当前差额 " else "已回款 ") +
                                money(if (matter.event.nature == FinanceNature.PROXY_PURCHASE) matter.differenceMinor else received),
                                style = LedgerText.title.copy(fontSize = 19.sp, letterSpacing = .5.sp))
                            if (target != null && matter.event.nature != FinanceNature.PROXY_PURCHASE)
                                Text("当前预计回款 ${money(target)}", style = LedgerText.caption, color = colors.inkSecondary)
                        }
                        Text("${matter.rows.size} 笔流水", style = LedgerText.caption, color = colors.inkSecondary)
                    }
                    if (target != null && target > 0 && matter.event.nature != FinanceNature.PROXY_PURCHASE) {
                        val fraction = (received.toDouble() / target).toFloat().coerceIn(0f, 1f)
                        Box(Modifier.fillMaxWidth().height(4.dp).background(colors.paperTertiary, RoundedCornerShape(2.dp))) {
                            Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(colors.accentDeep, RoundedCornerShape(2.dp)))
                        }
                    }
                    Text("${if (matter.pendingIsUpperBound) "待回款上限" else "待回款"} ${matter.pendingMinor?.let(::money) ?: "预计金额待完善"} · ${matter.accounts.joinToString(" · ")}",
                        Modifier.padding(top = 8.dp), style = LedgerText.caption, color = colors.inkSecondary)
                    }
                    if (isExpanded) {
                        fun money(value: Long) = FinanceMoney.display(value, matter.event.currencyCode)
                        Text("${if (matter.event.nature == FinanceNature.REIMBURSABLE) "原始垫付" else "实际付款"} ${money(matter.paidMinor)}", style = LedgerText.body)
                        if (matter.feeMinor != 0L) Text("手续费 ${money(matter.feeMinor)}", style = LedgerText.caption)
                        matter.returns.filterValues { it != 0L }.forEach { (role, amount) -> Text("${role.label} ${money(amount)}", style = LedgerText.caption) }
                        if (matter.pendingIsUpperBound) Text("尚未确认个人承担额，暂按全部垫付显示上限；这不计入个人收支。", style = LedgerText.caption)
                        Text("当前差额 ${if (matter.differenceMinor > 0) "+" else ""}${money(matter.differenceMinor)}", style = LedgerText.caption)
                        TextButton({ returnEvent = matter.event.id }, enabled = !state.busy) { Text("记录实际回款") }
                        matter.rows.forEach { row -> LedgerLine(row) { editId = row.id } }
                    }
                }
            }
        }
    } }
    editId?.let { id -> FinanceCanonicalRecordSheet(state, vm, id) { editId = null } }
    returnEvent?.let { id -> matters.find { it.event.id == id }?.let { matter ->
        FinanceEntrySheet(state, null, vm, initialRelationship = FinanceRelationshipDraft(
            nature = matter.event.nature, role = if (matter.event.nature == FinanceNature.REIMBURSABLE)
                FinanceFlowRole.REIMBURSEMENT else FinanceFlowRole.SETTLEMENT, eventId = id,
            eventUpdatedAt = matter.event.updatedAt), initialKind = FinanceEditorKind.INCOME) { returnEvent = null }
    } }
}
