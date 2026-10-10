package com.qq.closie.ui.lifeos.finance

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.qq.closie.life.finance.*
import com.qq.closie.navigation.modules.PresentationFactory
import java.time.Instant
import java.time.ZoneId

@Composable
internal fun FinanceImportScreen(repository: FinanceImportRepository, accounts: List<FinanceAccountEntity>, onBack: () -> Unit) {
    val vm: FinanceImportViewModel = viewModel(factory = PresentationFactory(FinanceImportViewModel::class.java) {
        FinanceImportViewModel(repository)
    })
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current.applicationContext
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { vm.select(context, it) } }
    var confirmation by remember { mutableStateOf<String?>(null) }
    var evidence by remember { mutableStateOf<FinanceImportRowEntity?>(null) }
    BackHandler(enabled = !state.busy, onBack = onBack)
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row {
            TextButton(onClick = onBack, enabled = !state.busy) { Text("返回账目") }
            TextButton(enabled = !state.busy, onClick = {
                picker.launch(arrayOf("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "text/csv", "text/comma-separated-values", "application/csv"))
            }) { Text("选择 XLSX / CSV") }
        }
        Text("旧账导入", style = MaterialTheme.typography.headlineSmall)
        Text("文件在本机解析。先检查账户和记录，确认后才写入账目。", style = MaterialTheme.typography.bodySmall)
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        state.message?.let { Text(it) }
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val preview = state.preview
            if (preview == null) {
                items(state.history, key = { it.id }) { batch ->
                    OutlinedButton(onClick = { vm.open(batch.id) }, enabled = !state.busy) {
                        Text(batch.fileName + " · " + when (batch.status) {
                            FinanceImportStatus.STAGED -> "待检查"
                            FinanceImportStatus.COMMITTED -> "已导入"
                            FinanceImportStatus.UNDONE -> "已撤销"
                        })
                    }
                }
            } else {
                item {
                    Text(preview.batch.fileName)
                    Text("共 ${preview.rows.size} 行 · 待检查 ${preview.rows.count { it.status == FinanceRowStatus.REVIEW }} · 重复候选 ${preview.rows.count { it.status == FinanceRowStatus.DUPLICATE }} · 无效 ${preview.rows.count { it.status == FinanceRowStatus.INVALID }}")
                }
                if (preview.batch.status != FinanceImportStatus.UNDONE) {
                    if (!state.reviewed) {
                        item { Text("为每个旧账户选择已有账户，或批量新建。历史重建从最早流水起累计；当前余额从现在起累计，旧流水只进入历史查询。") }
                        items(state.mappings, key = { it.source }) { mapping ->
                            ImportAccountMapping(mapping, accounts, !state.busy, vm::map)
                        }
                        item { Button(onClick = vm::prepare, enabled = !state.busy) { Text("检查记录与重复项") } }
                    } else {
                        item {
                            TextButton(onClick = { state.mappings.firstOrNull()?.let(vm::map) }, enabled = !state.busy) { Text("修改账户映射") }
                            Row { Checkbox(state.splitTags, vm::tags, enabled = !state.busy); Text("将标签按空格拆开（默认保留原单元格）") }
                            Row { Checkbox(state.fees, vm::fees, enabled = !state.busy); Text("确认手续费为转账金额之外的实际支出") }
                            Text("普通记录默认选中。待检查和重复项需逐条勾选；勾选重复项表示仍要导入。退款／报销附属金额仅保留作依据，不生成到账。")
                        }
                        items(preview.rows, key = { it.id }) { row ->
                            val enabled = !state.busy && row.canonicalId == null && row.kind != null &&
                                row.status !in setOf(FinanceRowStatus.INVALID, FinanceRowStatus.UNDONE)
                            Row {
                                Checkbox(row.id in state.selected, { vm.choose(row.id, it) }, enabled = enabled)
                                Column(Modifier.weight(1f)) {
                                    Text("第 ${row.rowNumber} 行 · ${row.description.ifBlank { "原备注为空" }}")
                                    Text("${row.sourceAccount} · ${row.kind?.name ?: "类型待处理"} · ${row.amountMinor?.let { FinanceMoney.display(it, "CNY") } ?: "金额无效"}",
                                        style = MaterialTheme.typography.bodySmall)
                                    if (row.issue.isNotBlank()) Text(row.issue, style = MaterialTheme.typography.bodySmall)
                                    TextButton(onClick = { evidence = row }) { Text("查看原始记录") }
                                }
                            }
                            HorizontalDivider()
                        }
                    }
                    if (preview.batch.status == FinanceImportStatus.COMMITTED) item {
                        TextButton(enabled = !state.busy, onClick = { confirmation = "undo" }) { Text("撤销此批次") }
                    }
                } else item { Text("此批次已撤销，后来编辑或关联过的记录仍保留。") }
            }
        }
        if (state.reviewed && state.preview?.batch?.status != FinanceImportStatus.UNDONE) Button(
            onClick = { confirmation = "commit" }, enabled = !state.busy && state.selected.isNotEmpty(), modifier = Modifier.fillMaxWidth()
        ) { Text("确认导入 ${state.selected.size} 条") }
    }
    if (confirmation != null) AlertDialog(onDismissRequest = { confirmation = null },
        title = { Text(if (confirmation == "undo") "撤销导入" else "确认这一批账目") },
        text = { Text(if (confirmation == "undo") "只作废本批次创建且未被修改或再次关联的记录。后来编辑的记录会保留，请留意撤销结果。"
            else "将写入 ${state.selected.size} 条记录，并按映射新建所需账户。已勾选的重复候选也会入账。") },
        confirmButton = { TextButton(onClick = {
            if (confirmation == "undo") vm.undo(requireNotNull(state.preview).batch.id) else vm.commit()
            confirmation = null
        }) { Text("确认") } },
        dismissButton = { TextButton(onClick = { confirmation = null }) { Text("继续检查") } })
    evidence?.let { row ->
        FinanceReceipt("原始记录 · 第 ${row.rowNumber} 行", onClose = { evidence = null }) {
            val cells = remember(row.rawPayload) {
                runCatching { com.google.gson.JsonParser.parseString(row.rawPayload).asJsonObject }.getOrNull()
            }
            LegacyLedgerReader.headers.forEach { header -> Text("$header：${cells?.get(header)?.asString.orEmpty()}") }
        }
    }
}

@Composable
private fun ImportAccountMapping(mapping: FinanceAccountMapping, accounts: List<FinanceAccountEntity>, enabled: Boolean,
    onChange: (FinanceAccountMapping) -> Unit) {
    var opening by remember(mapping.source) { mutableStateOf("0") }
    var error by remember(mapping.source) { mutableStateOf<String?>(null) }
    Column {
        Text(mapping.source, style = MaterialTheme.typography.titleMedium)
        EnumChoice("账户", mapping.existingId ?: "", listOf("" to "新建账户") +
            accounts.filter { it.archivedAt == null && it.currencyCode == "CNY" }.map { it.id to it.name }, enabled) {
            onChange(mapping.copy(existingId = it.ifBlank { null }))
        }
        if (mapping.existingId == null) {
            OutlinedTextField(mapping.name, { onChange(mapping.copy(name = it)) }, enabled = enabled, label = { Text("账户名称") })
            EnumChoice("类型", mapping.kind, FinanceAccountKind.entries.map { it to it.name }, enabled) { onChange(mapping.copy(kind = it)) }
            EnumChoice("余额起点", mapping.fromHistory, listOf(true to "从历史重建", false to "以当前余额为准"), enabled) {
                onChange(mapping.copy(fromHistory = it, openingMinor = 0, anchorAt = System.currentTimeMillis(), openingValid = true))
                opening = "0"; error = null
            }
            if (!mapping.fromHistory) {
                OutlinedTextField(opening, { value ->
                    opening = value
                    try {
                        val parsed = java.math.BigDecimal(value).movePointRight(2).longValueExact()
                        onChange(mapping.copy(openingMinor = parsed, openingValid = true)); error = null
                    } catch (_: Exception) { error = "请输入最多两位小数的余额"; onChange(mapping.copy(openingValid = false)) }
                }, enabled = enabled, label = { Text("当前余额（欠款填负数）") }, isError = error != null)
                error?.let { Text(it) }
                Text("起点：${Instant.ofEpochMilli(mapping.anchorAt).atZone(ZoneId.systemDefault()).toLocalDateTime()}", style = MaterialTheme.typography.bodySmall)
            }
        }
        HorizontalDivider()
    }
}

@Composable
internal fun <T> EnumChoice(label: String, selected: T, values: List<Pair<T, String>>, enabled: Boolean = true, onSelect: (T) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }, enabled = enabled) {
            Text("$label：${values.find { it.first == selected }?.second ?: "请选择"}")
        }
        DropdownMenu(expanded, { expanded = false }) {
            values.forEach { (value, title) -> DropdownMenuItem(text = { Text(title) }, onClick = { expanded = false; onSelect(value) }) }
        }
    }
}
