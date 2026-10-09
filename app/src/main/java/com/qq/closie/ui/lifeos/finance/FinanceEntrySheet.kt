package com.qq.closie.ui.lifeos.finance

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.qq.closie.life.finance.*
import com.qq.closie.ui.lifeos.theme.*

@Composable
internal fun FinanceEntrySheet(state: FinanceUiState, row: FinanceLedgerRow?, vm: FinanceViewModel, onClose: () -> Unit) {
    val colors = LocalLifeOsColors.current
    val active = state.snapshot.accounts.filter { it.archivedAt == null }
    var kindName by rememberSaveable(row?.id) { mutableStateOf(when {
        row?.transfer != null -> FinanceEditorKind.TRANSFER.name
        row?.entry?.direction == FinanceDirection.INFLOW -> FinanceEditorKind.INCOME.name
        else -> FinanceEditorKind.EXPENSE.name
    }) }
    val kind = FinanceEditorKind.valueOf(kindName)
    var amount by rememberSaveable(row?.id) { mutableStateOf(row?.let { FinanceMoney.input(it.entry.amountMinor, it.currencyCode) }.orEmpty()) }
    var accountId by rememberSaveable(row?.id) { mutableStateOf(row?.account?.id ?: active.firstOrNull()?.id.orEmpty()) }
    var targetId by rememberSaveable(row?.id) { mutableStateOf(row?.targetAccount?.id ?: active.firstOrNull { it.id != accountId }?.id.orEmpty()) }
    val preservedOccurredAt = rememberSaveable(row?.id) { row?.entry?.occurredAt ?: System.currentTimeMillis() }
    var occurredAt by rememberSaveable(row?.id) { mutableStateOf(FinanceTime.input(preservedOccurredAt)) }
    var description by rememberSaveable(row?.id) { mutableStateOf(row?.entry?.description.orEmpty()) }
    var category by rememberSaveable(row?.id) { mutableStateOf(row?.category.orEmpty()) }
    var tags by rememberSaveable(row?.id) { mutableStateOf(row?.tags.orEmpty().joinToString("，")) }
    var confirmVoid by remember { mutableStateOf(false) }
    val accounts = state.snapshot.accounts.filter { it.archivedAt == null || it.id == row?.account?.id || it.id == row?.targetAccount?.id }
    FinanceReceipt(if (row == null) "记一笔 · 日常收据" else "修改这笔记录", state.busy, state.error, onClose) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FinanceEditorKind.entries.forEach { tab ->
                val editable = row == null || if (row.transfer != null) tab == FinanceEditorKind.TRANSFER else tab != FinanceEditorKind.TRANSFER
                FilterChip(kind == tab, onClick = { kindName = tab.name; vm.clearError() }, enabled = editable && !state.busy,
                    label = { Text(tab.label) })
            }
        }
        val currency = accounts.find { it.id == accountId }?.currencyCode ?: "CNY"
        OutlinedTextField(amount, { amount = it }, Modifier.fillMaxWidth(), label = { Text("金额 · $currency") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true, enabled = !state.busy)
        AccountChoice(if (kind == FinanceEditorKind.TRANSFER) "转出账户" else "账户", accounts, accountId, !state.busy) { accountId = it }
        if (kind == FinanceEditorKind.TRANSFER) {
            AccountChoice("转入账户", accounts.filter { it.id != accountId }, targetId, !state.busy) { targetId = it }
            if (accounts.count { it.archivedAt == null } < 2 && row == null) Text("转账需要两个不同的真实账户。", style = LifeText.caption, color = colors.muted)
        }
        OutlinedTextField(occurredAt, { occurredAt = it }, Modifier.fillMaxWidth(), label = { Text("发生时间 · 年-月-日 时:分") },
            singleLine = true, enabled = !state.busy)
        OutlinedTextField(description, { description = it }, Modifier.fillMaxWidth(),
            label = { Text(if (kind == FinanceEditorKind.TRANSFER) "备注（可留空）" else "具体事由 · 买了什么 / 为了什么") }, enabled = !state.busy)
        if (kind != FinanceEditorKind.TRANSFER) {
            OutlinedTextField(category, { category = it }, Modifier.fillMaxWidth(), label = { Text("分类（可自定义）") }, singleLine = true, enabled = !state.busy)
            val suggestions = (state.snapshot.categories.map { it.name } + listOf("餐饮", "交通", "日用", "其他")).distinct().take(6)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                suggestions.take(4).forEach { name -> TextButton(onClick = { category = name }, enabled = !state.busy) { Text(name, style = LifeText.caption) } }
            }
            OutlinedTextField(tags, { tags = it }, Modifier.fillMaxWidth(), label = { Text("标签 · 用逗号分隔，可自定义") }, enabled = !state.busy)
        }
        Button(onClick = { vm.saveRecord(row, FinanceEntryDraft(kind, amount, accountId, targetId, occurredAt, description, category, tags, preservedOccurredAt), onClose) },
            enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text(if (state.busy) "正在保存……" else "保存记录") }
        if (row != null) {
            Text("记录时间 ${FinanceTime.input(row.entry.recordedAt)}", style = LifeText.caption, color = colors.muted)
            TextButton(onClick = { confirmVoid = true }, enabled = !state.busy) { Text("作废这笔记录") }
        }
    }
    if (confirmVoid && row != null) AlertDialog(onDismissRequest = { if (!state.busy) confirmVoid = false },
        title = { Text(if (row.transfer != null) "作废整笔转账？" else "作废这笔记录？") },
        text = { Text("记录仍会保留，余额和统计将不再计入它。${if (row.transfer != null) "转出、转入流水会同时作废。" else ""}") },
        confirmButton = { TextButton(onClick = { vm.voidRecord(row) { confirmVoid = false; onClose() } }, enabled = !state.busy) { Text("确认作废") } },
        dismissButton = { TextButton(onClick = { confirmVoid = false }, enabled = !state.busy) { Text("取消") } })
}
