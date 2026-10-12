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
internal fun FinanceEntrySheet(state: FinanceUiState, row: FinanceLedgerRow?, vm: FinanceViewModel,
    initialRelationship: FinanceRelationshipDraft? = null, initialKind: FinanceEditorKind = FinanceEditorKind.EXPENSE, onClose: () -> Unit) {
    val colors = LocalLifeOsColors.current
    val active = state.snapshot.accounts.filter { it.archivedAt == null }
    var kindName by rememberSaveable(row?.id) { mutableStateOf(when {
        row?.transfer != null -> FinanceEditorKind.TRANSFER.name
        row?.entry?.direction == FinanceDirection.INFLOW -> FinanceEditorKind.INCOME.name
        else -> initialKind.name
    }) }
    val kind = FinanceEditorKind.valueOf(kindName)
    var amount by rememberSaveable(row?.id) { mutableStateOf(row?.let { FinanceMoney.input(it.entry.amountMinor, it.currencyCode) }.orEmpty()) }
    var accountId by rememberSaveable(row?.id) { mutableStateOf(row?.account?.id ?: active.firstOrNull()?.id.orEmpty()) }
    var targetId by rememberSaveable(row?.id) { mutableStateOf(row?.targetAccount?.id ?: active.firstOrNull { it.id != accountId }?.id.orEmpty()) }
    val preservedOccurredAt = rememberSaveable(row?.id) { row?.entry?.occurredAt ?: System.currentTimeMillis() }
    var occurredAt by rememberSaveable(row?.id) { mutableStateOf(FinanceTime.input(preservedOccurredAt)) }
    var description by rememberSaveable(row?.id) { mutableStateOf(row?.entry?.description.orEmpty()) }
    val leaf = state.snapshot.categories.find { it.id == row?.entry?.categoryId }
    val parent = state.snapshot.categories.find { it.id == leaf?.parentId }
    var selectedCategoryId by rememberSaveable(row?.id) { mutableStateOf(row?.entry?.categoryId) }
    var category by rememberSaveable(row?.id) { mutableStateOf(parent?.name ?: leaf?.name.orEmpty()) }
    var subcategory by rememberSaveable(row?.id) { mutableStateOf(if (parent == null) "" else leaf?.name.orEmpty()) }
    var relationship by remember(row?.id) { mutableStateOf(initialRelationship ?: FinanceRelationshipDraft.from(state.snapshot, row)) }
    var tags by rememberSaveable(row?.id) { mutableStateOf(row?.tags.orEmpty().joinToString("，")) }
    var confirmVoid by remember { mutableStateOf(false) }
    var allocationOpen by remember { mutableStateOf(false) }
    var pickingCategory by remember { mutableStateOf(false) }
    var pickingTag by remember { mutableStateOf(false) }
    var pickingDate by remember { mutableStateOf(false) }
    val multiple = state.snapshot.v4?.activeLinks.orEmpty().count { it.entryId == row?.entry?.id } > 1
    val accounts = state.snapshot.accounts.filter { it.archivedAt == null || it.id == row?.account?.id || it.id == row?.targetAccount?.id }
    FinanceReceipt(if (row == null) "记一笔 · 日常收据" else "修改这笔记录", state.busy, state.error, onClose, actions = {
        Button(onClick = { vm.saveRecord(row, FinanceEntryDraft(kind, amount, accountId, targetId, occurredAt, description, category, tags, preservedOccurredAt, subcategory, relationship, selectedCategoryId), onClose) },
            enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text(if (state.busy) "正在保存……" else "保存记录") }
    }) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FinanceEditorKind.entries.forEach { tab ->
                val editable = row == null || if (row.transfer != null) tab == FinanceEditorKind.TRANSFER else tab != FinanceEditorKind.TRANSFER
                FilterChip(kind == tab, onClick = { kindName = tab.name; if (row == null) relationship = relationship.copy(role = if (tab == FinanceEditorKind.INCOME) FinanceFlowRole.OTHER else FinanceFlowRole.PAYMENT); vm.clearError() }, enabled = editable && !state.busy,
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
        TextButton({ pickingDate = true }, enabled = !state.busy) { Text("从日历选择日期") }
        OutlinedTextField(description, { description = it }, Modifier.fillMaxWidth(),
            label = { Text(if (kind == FinanceEditorKind.TRANSFER) "备注（可留空）" else "具体事由 · 买了什么 / 为了什么") }, enabled = !state.busy)
        if (kind != FinanceEditorKind.TRANSFER) {
            Row {
                TextButton({ pickingCategory = true }, enabled = !state.busy) { Text("选择已有分类") }
                TextButton({ pickingTag = true }, enabled = !state.busy) { Text("添加已有标签") }
            }
            if (selectedCategoryId != null) {
                Text(FinanceProjection.categoryPath(state.snapshot.categories, selectedCategoryId) ?: "分类已不存在，请重新选择")
                TextButton({ selectedCategoryId = null; category = ""; subcategory = "" }, enabled = !state.busy) { Text("改为自定义分类") }
            } else {
                OutlinedTextField(category, { category = it }, Modifier.fillMaxWidth(), label = { Text("新主分类（可留空）") }, singleLine = true, enabled = !state.busy)
                OutlinedTextField(subcategory, { subcategory = it }, Modifier.fillMaxWidth(), label = { Text("新子分类（可留空）") }, enabled = !state.busy)
            }
            OutlinedTextField(tags, { tags = it }, Modifier.fillMaxWidth(), label = { Text("标签 · 用逗号分隔，可自定义") }, enabled = !state.busy)
        }
        if (kind != FinanceEditorKind.TRANSFER && !multiple) FinanceRelationshipFields(state.snapshot, relationship, !state.busy) { relationship = it }
        if (row != null && row.transfer == null) TextButton(onClick = { allocationOpen = true }, enabled = !state.busy) {
            Text(if (multiple) "查看与修改多个事件分配" else "分配到多个事件")
        }
        if (row != null) {
            Text("记录时间 ${FinanceTime.input(row.entry.recordedAt)}", style = LifeText.caption, color = colors.muted)
            TextButton(onClick = { confirmVoid = true }, enabled = !state.busy) { Text("作废这笔记录") }
        }
    }
    if (pickingCategory) FinanceCategoryPicker(state.snapshot, selectedCategoryId,
        onSelect = { selectedCategoryId = it }, onClose = { pickingCategory = false },
        busy = state.busy, error = state.error, onCreateChild = vm::createCategory)
    if (pickingTag) FinanceChoiceSheet("标签", null, state.snapshot.tags.map { it.id to it.name },
        onSelect = { id -> state.snapshot.tags.find { it.id == id }?.let { selected ->
            tags = (tags.split(Regex("[,，\\n]")).map(String::trim).filter(String::isNotBlank) + selected.name).distinct().joinToString("，")
        } }, onClose = { pickingTag = false })
    if (pickingDate) FinanceDateSheet(null, { start, _ -> occurredAt = start + " " + occurredAt.substringAfter(' ', "12:00"); true },
        { pickingDate = false }, initial = runCatching { java.time.LocalDate.parse(occurredAt.substringBefore(' ')) }.getOrDefault(java.time.LocalDate.now()), singleDayOnly = true)
    if (allocationOpen && row != null) FinanceAllocationSheet(state, row, vm) { allocationOpen = false; onClose() }
    if (confirmVoid && row != null) AlertDialog(onDismissRequest = { if (!state.busy) confirmVoid = false },
        title = { Text(if (row.transfer != null) "作废整笔转账？" else "作废这笔记录？") },
        text = { Text("记录仍会保留，余额和统计将不再计入它。${if (row.transfer != null) "转出、转入流水会同时作废。" else ""}") },
        confirmButton = { TextButton(onClick = { vm.voidRecord(row) { confirmVoid = false; onClose() } }, enabled = !state.busy) { Text("确认作废") } },
        dismissButton = { TextButton(onClick = { confirmVoid = false }, enabled = !state.busy) { Text("取消") } })
}
