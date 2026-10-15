package com.qq.closie.ui.lifeos.finance

import com.qq.closie.life.ui.theme.BundledText as Text

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
    initialRelationship: FinanceRelationshipDraft? = null, initialKind: FinanceEditorKind = FinanceEditorKind.EXPENSE, onSaved: (() -> Unit)? = null, onClose: () -> Unit) {
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
    var relationship by rememberSaveable(row?.id, stateSaver = FinanceRelationshipDraft.Saver) { mutableStateOf(initialRelationship ?: FinanceRelationshipDraft.from(state.snapshot, row)) }
    var tags by rememberSaveable(row?.id) { mutableStateOf(row?.tags.orEmpty().joinToString("，")) }
    var confirmVoid by remember { mutableStateOf(false) }
    var allocationOpen by remember { mutableStateOf(false) }
    var pickingCategory by remember { mutableStateOf(false) }
    var moreFields by rememberSaveable { mutableStateOf(false) }
    var pickingDate by remember { mutableStateOf(false) }
    val multiple = state.snapshot.v4?.activeLinks.orEmpty().count { it.entryId == row?.entry?.id } > 1
    val recentAccounts = remember(state.snapshot.entries) { state.snapshot.entries.filter { it.voidedAt == null }.sortedByDescending { it.updatedAt }.map { it.accountId }.distinct().take(4) }
    val accounts = state.snapshot.accounts.filter { it.archivedAt == null || it.id == row?.account?.id || it.id == row?.targetAccount?.id }
    FinanceReceipt(if (row == null) "记一笔 · 日常收据" else "修改这笔记录", state.busy, state.error, onClose, actions = {
        Button(onClick = { vm.saveRecord(row, FinanceEntryDraft(kind, amount, accountId, targetId, occurredAt, description, category, tags, preservedOccurredAt, subcategory, relationship, selectedCategoryId), onSaved ?: onClose) },
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
        OutlinedTextField(amount, { amount = it }, Modifier.fillMaxWidth(), textStyle = LifeText.number, label = { Text("金额 · $currency") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true, enabled = !state.busy)
        AccountChoice(if (kind == FinanceEditorKind.TRANSFER) "转出账户" else "账户", accounts, accountId, !state.busy, recentAccounts) { accountId = it; if (targetId == it) targetId = accounts.firstOrNull { a -> a.id != it && a.archivedAt == null }?.id.orEmpty() }
        if (kind == FinanceEditorKind.TRANSFER) {
            AccountChoice("转入账户", accounts.filter { it.id != accountId }, targetId, !state.busy, recentAccounts) { targetId = it }
            if (accounts.count { it.archivedAt == null } < 2 && row == null) Text("转账需要两个不同的真实账户。", style = LifeText.caption, color = colors.muted)
        }
        TextButton({ pickingDate = !pickingDate }, enabled = !state.busy) { Text("时间 · $occurredAt") }
        if (pickingDate) OutlinedTextField(occurredAt, { occurredAt = it }, Modifier.fillMaxWidth(),
            label = { Text("年-月-日 时:分") }, singleLine = true, enabled = !state.busy, textStyle = com.qq.closie.life.ui.theme.LifeType.MonoNumber)
        OutlinedTextField(description, { description = it }, Modifier.fillMaxWidth(),
            label = { Text(if (kind == FinanceEditorKind.TRANSFER) "备注（可留空）" else "具体事由 · 买了什么 / 为了什么") }, enabled = !state.busy, textStyle = com.qq.closie.life.ui.theme.LifeType.inputFor(description))
        if (kind != FinanceEditorKind.TRANSFER) {
            TextButton({ pickingCategory = !pickingCategory }, enabled = !state.busy) {
                Text("分类 · " + (FinanceProjection.categoryPath(state.snapshot.categories, selectedCategoryId) ?: category.ifBlank { "选择分类" }))
            }
            if (pickingCategory) FinanceCategoryFields(state.snapshot, selectedCategoryId,
                { selectedCategoryId = it; pickingCategory = false }, state.busy, vm::createCategory)
            TextButton({ moreFields = !moreFields }, enabled = !state.busy) { Text(if (moreFields) "收起标签与新分类" else "标签与新分类") }
            if (moreFields) {
                OutlinedTextField(tags, { tags = it }, Modifier.fillMaxWidth(), label = { Text("标签 · 逗号分隔") }, enabled = !state.busy, textStyle = com.qq.closie.life.ui.theme.LifeType.inputFor(tags))
                androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(state.snapshot.tags.size) { index ->
                        val tag = state.snapshot.tags[index]
                        SuggestionChip({ tags = (tags.split('，', ',').map(String::trim).filter(String::isNotBlank) + tag.name).distinct().joinToString("，") },
                            enabled = !state.busy, label = { Text(tag.name) })
                    }
                }
                OutlinedTextField(category, { category = it; selectedCategoryId = null }, Modifier.fillMaxWidth(), label = { Text("新主分类（可留空）") }, enabled = !state.busy, textStyle = com.qq.closie.life.ui.theme.LifeType.inputFor(category))
                OutlinedTextField(subcategory, { subcategory = it; selectedCategoryId = null }, Modifier.fillMaxWidth(), label = { Text("新子分类（可留空）") }, enabled = !state.busy, textStyle = com.qq.closie.life.ui.theme.LifeType.inputFor(subcategory))
            }
        }
        if (kind != FinanceEditorKind.TRANSFER && !multiple) FinanceRelationshipFields(state.snapshot, relationship, !state.busy) { relationship = it }
        if (row != null && row.transfer == null) TextButton({ allocationOpen = !allocationOpen }, enabled = !state.busy) {
            Text(if (multiple) "查看与修改多个事件分配" else "分配到多个事件")
        }
        if (allocationOpen && row != null) FinanceAllocationFields(state, row, vm) { allocationOpen = false }
        if (row != null) {
            Text("记录时间 ${FinanceTime.input(row.entry.recordedAt)}", style = LifeText.caption, color = colors.muted)
            TextButton({ confirmVoid = true }, enabled = !state.busy) { Text("作废这笔记录") }
        }
    }
    if (confirmVoid && row != null) AlertDialog(onDismissRequest = { if (!state.busy) confirmVoid = false },
        title = { Text(if (row.transfer != null) "作废整笔转账？" else "作废这笔记录？") },
        text = { Text("记录仍会保留，余额和统计将不再计入它。${if (row.transfer != null) "转出、转入流水会同时作废。" else ""}") },
        confirmButton = { TextButton(onClick = { vm.voidRecord(row) { confirmVoid = false; (onSaved ?: onClose)() } }, enabled = !state.busy) { Text("确认作废") } },
        dismissButton = { TextButton(onClick = { confirmVoid = false }, enabled = !state.busy) { Text("取消") } })
}
