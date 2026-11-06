package com.qq.closie.ui.lifeos.finance

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import com.qq.closie.life.finance.*
import com.qq.closie.life.ui.theme.BundledText as Text
import com.qq.closie.life.ui.theme.LifeType
import com.qq.closie.ui.lifeos.theme.*

/** Read-only canonical projection. This composable has no repository or command reference. */
@Composable
internal fun FinanceRecordDetails(row: FinanceLedgerRow?, snapshot: FinanceSnapshot,
    onEdit: (FinanceLedgerRow) -> Unit, origin:com.qq.closie.ui.lifeos.components.LifeSurfaceOrigin?=null,
    voidAction: (@Composable (FinanceLedgerRow) -> Unit)? = null,
    onOpenEvent: ((String)->Unit)? = null, onOpenEvidence: ((List<FinanceMediaLinkEntity>)->Unit)? = null,
    onClose: () -> Unit) {
    val colors = LocalLifeOsColors.current
    var expandedEvent by remember(row?.id) { mutableStateOf<String?>(null) }
    FinanceReceipt("账单详情", onClose = onClose, origin=origin, formal = true,
        formalStatus = when { row == null -> "记录不可用"; row.entry.voidedAt != null -> "正式账单 · 已作废"; else -> "正式账单 · 已确认" }, actions = {
        if (row != null) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Button({ onEdit(row) }, Modifier.weight(1f).heightIn(min = 42.dp).semantics { contentDescription = "编辑账单" },
                    shape = RoundedCornerShape(12.dp), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)) {
                    Text("编辑", style = LedgerText.body.copy(fontSize = 14.sp, lineHeight = 17.sp))
                }
                voidAction?.invoke(row)
            }
        }
    }) {
        if (row == null) {
            Text("这条记录已失效或不可用", style = LifeText.title, color = colors.ink)
            Text("请关闭后刷新账簿。", style = LedgerText.body, color = colors.inkSecondary)
        } else {
            val source = snapshot.v4?.proposals.orEmpty().firstOrNull { it.canonicalId == row.id || it.canonicalId == row.entry.id }
            val links = snapshot.v4?.activeLinks.orEmpty().filter { it.entryId == row.entry.id }
            val events = snapshot.v4?.events.orEmpty().filter { event -> event.voidedAt == null && links.any { it.eventId == event.id } }
            val timing = snapshot.v16?.entryTimingRows.orEmpty().firstOrNull { it.voidedAt == null && it.entryId == row.entry.id }
            val cashTime = row.entry.occurredAt.takeUnless { timing?.cashPrecision == FinanceTimePrecision.UNKNOWN }
            val economicTime = FinanceProjection.economicDates(snapshot)[row.entry.id]
            val ambiguousEvent = events.size > 1 && links.all { it.role in setOf(FinanceFlowRole.PAYMENT, FinanceFlowRole.FEE) }
            val cashLabel = cashTime?.let(FinanceTime::input)?.let {
                if (timing?.cashPrecision == FinanceTimePrecision.DATE) it.substringBefore(' ') else it
            } ?: "未知"
            val credentials = financeRecordEvidence(snapshot,row)
            val sign = when {
                row.transfer != null -> ""
                row.entry.direction == FinanceDirection.INFLOW -> "+"
                else -> "−"
            }
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(sign + FinanceMoney.display(row.entry.amountMinor, row.currencyCode),
                    style = LedgerText.body.copy(fontSize = 32.sp, lineHeight = 38.sp, letterSpacing = .5.sp), color = colors.ink)
                Text(listOfNotNull(row.category, row.entry.description.takeIf(String::isNotBlank)).joinToString(" · "),
                    Modifier.padding(top = 3.dp), style = LedgerText.body.copy(fontSize = 12.sp, lineHeight = 20.sp), color = colors.inkSecondary)
            }
            HorizontalDivider(Modifier.padding(top = 15.dp), color = colors.line)
            ReceiptField("账户", row.account.name + (row.targetAccount?.let { " → " + it.name } ?: ""), height = 42.dp, divider = true)
            ReceiptField("发生时间", economicTime?.let(FinanceTime::input) ?: if (ambiguousEvent) "多项关联，请查看事项" else cashLabel, height = 42.dp)
            ReceiptField(if (row.entry.direction == FinanceDirection.INFLOW) "收款时间" else "支付时间",
                cashLabel, height = 32.dp, secondary = true)
            ReceiptField("标签", row.tags.joinToString("、").ifBlank { "无" })
            if(credentials.isNotEmpty() && onOpenEvidence!=null) Box(Modifier.fillMaxWidth()
                .clickable(role=androidx.compose.ui.semantics.Role.Button) {onOpenEvidence(credentials)}
                .semantics {contentDescription="查看账单凭据"}) {
                ReceiptField("凭据", credentials.joinToString("、") { it.label.ifBlank { "附件" } })
            } else ReceiptField("凭据", credentials.joinToString("、") { it.label.ifBlank { "附件" } }.ifBlank { "无" })
            if (events.isEmpty()) ReceiptField("关联事项", "无")
            events.forEach { event ->
                TextButton({ if(onOpenEvent!=null) onOpenEvent(event.id) else expandedEvent = if (expandedEvent == event.id) null else event.id }) {
                    Text("关联事项 · " + event.description.ifBlank { event.nature.label })
                }
                if (expandedEvent == event.id) {
                    val total = FinanceCostProjection.eventTotal(snapshot, event.id)
                    ReceiptField("本人承担", FinanceMoney.display(total.confirmedMinor, event.currencyCode) +
                        if (total.complete) "" else " · 另有待确认")
                    ReceiptField("关联方式", links.filter { it.eventId == event.id }.joinToString("、") { it.role.label })
                }
            }
            ReceiptField("来源", source?.discoverySourceLabel() ?: if (row.entry.importBatchId != null) "文件导入" else "未记录", height = 42.dp, divider = true)
        }
    }
}

@Composable
private fun ReceiptField(label: String, value: String, height: Dp = 34.dp, divider: Boolean = false, secondary: Boolean = false) {
    val colors = LocalLifeOsColors.current
    Column {
        Row(Modifier.fillMaxWidth().heightIn(min = height), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f), style = LedgerText.body.copy(fontSize = if (secondary) 12.sp else 13.sp, lineHeight = 16.sp), color = colors.inkSecondary)
            Text(value, Modifier.weight(2f), style = LedgerText.body.copy(fontSize = if (secondary) 12.sp else 13.sp, lineHeight = 16.sp),
                color = if (secondary) colors.inkSecondary else colors.ink, textAlign = androidx.compose.ui.text.style.TextAlign.End)
        }
        if (divider) HorizontalDivider(color = colors.line)
    }
}

@Composable
internal fun FinanceRecordVoidAction(row: FinanceLedgerRow, state: FinanceUiState, vm: FinanceViewModel, onDone: () -> Unit) {
    var preview by remember(row.id, row.entry.revision) { mutableStateOf<FinanceChangePreview?>(null) }
    TextButton({ vm.previewVoid(entryId = row.entry.id) { preview = it } }, enabled = !state.busy) { Text("作废（可恢复）") }
    preview?.let { impact ->
        AlertDialog(
            onDismissRequest = { if (!state.busy) preview = null },
            title = { Text("确认作废") },
            text = {
                Column {
                    FinanceChangeImpact(impact)
                    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton({ vm.confirmVoid(impact) { preview = null; onDone() } }, enabled = !state.busy) { Text("确认作废") }
            },
            dismissButton = { TextButton({ preview = null }, enabled = !state.busy) { Text("取消") } }
        )
    }
}
