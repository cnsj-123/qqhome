package com.qq.closie.ui.lifeos.finance

import com.qq.closie.life.ui.theme.BundledText as Text

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
    val vm: FinanceImportViewModel = viewModel(factory = PresentationFactory(FinanceImportViewModel::class.java) { FinanceImportViewModel(repository) })
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current.applicationContext
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { vm.select(context, it) } }
    var confirmation by remember { mutableStateOf<String?>(null) }
    var evidence by remember { mutableStateOf<FinanceImportRowEntity?>(null) }
    var detailGroup by remember { mutableStateOf<FinanceImportGroup?>(null) }
    var summary by androidx.compose.runtime.saveable.rememberSaveable(state.preview?.batch?.id) { mutableStateOf(false) }
    LaunchedEffect(state.stage) { if (state.stage != ImportStage.REVIEW) summary = false }
    val colors = com.qq.closie.ui.lifeos.theme.LocalLifeOsColors.current
    val preview = state.preview
    val completed = state.stage == ImportStage.COMPLETED
    val step = if (summary || completed) ImportPhase.COMMIT.ordinal else state.diagnostic.phase.ordinal
    val groups = remember(preview?.rows) { FinanceImportGroup.entries.associateWith { group -> preview?.rows.orEmpty().filter { group in FinanceImportReview.groups(it) } } }
    fun selectFile() { picker.launch(arrayOf("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "text/csv", "text/comma-separated-values", "application/csv", "application/octet-stream", "application/vnd.ms-excel")) }
    BackHandler(enabled = !state.busy) { if (summary && !completed) summary = false else onBack() }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            TextButton(onBack, enabled = !state.busy) { Text("返回账目") }
            Text("旧账导入", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            TextButton(::selectFile, enabled = !state.busy) { Text("选择文件") }
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("文件", "解析", "账户", "检查", if (completed) "完成" else "确认").forEachIndexed { index, label ->
                Surface(Modifier.weight(1f), color = if (index == step) colors.paperTertiary else colors.paperSecondary,
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp)) {
                    Text("${index + 1} $label", Modifier.padding(vertical = 10.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        if (state.error != null || state.busy) {
            ImportDiagnosticPanel(state, onRetry = {
                when (state.diagnostic.phase) {
                    ImportPhase.FILE_ACCESS, ImportPhase.PARSE -> vm.retryFile(context)
                    ImportPhase.MAPPING -> vm.prepare()
                    ImportPhase.REVIEW -> state.preview?.batch?.id?.let(vm::open)
                    ImportPhase.COMMIT -> { state.preview?.batch?.id?.let(vm::open) }
                }
            }, onChoose = ::selectFile, onBrowse = { picker.launch(arrayOf("*/*")) })
        }
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (preview == null) {
                item { FinancePanel {
                    Text("把过去的账本接过来", style = MaterialTheme.typography.headlineSmall)
                    Text("选择 XLSX 或 CSV，在本机检查。确认前不会写入账目。", style = MaterialTheme.typography.bodyMedium)
                    Text("无需先建立账户；预览后可把旧账户关联到已有账户，或在导入中建立。", style = MaterialTheme.typography.bodySmall)
                    TextButton({ picker.launch(arrayOf("*/*")) }, enabled = !state.busy) { Text("找不到文件？浏览所有文件") }
                    Button(::selectFile, enabled = !state.busy) { Text("选择 XLSX / CSV") }
                } }
                if (state.history.isNotEmpty()) item { Text("导入记录", style = MaterialTheme.typography.titleMedium) }
                items(state.history, key = { it.id }) { batch ->
                    OutlinedButton({ vm.open(batch.id) }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                        Text(batch.fileName + " · " + when (batch.status) {
                            FinanceImportStatus.STAGED -> "待检查"; FinanceImportStatus.COMMITTED -> "已导入"; FinanceImportStatus.UNDONE -> "已撤销"
                        })
                    }
                }
            } else {
                item { FinancePanel {
                    Text(preview.batch.fileName, style = MaterialTheme.typography.titleMedium)
                    Text("共 ${preview.rows.size} 行 · ${state.mappings.size} 个账户", style = MaterialTheme.typography.bodySmall)
                    val businessCounts = remember(preview.rows) { preview.rows.groupingBy(LegacyBusinessSemantics::state).eachCount() }
                    LegacyBusinessState.entries.forEach { status ->
                        Text("${status.label} · ${businessCounts[status] ?: 0} 行", style = MaterialTheme.typography.bodySmall)
                    }
                    Text("以上为来源识别数量，不代表已经写入；已报销和需核对记录保留在导入预览。", style = MaterialTheme.typography.bodySmall)
                } }
                if (preview.batch.status == FinanceImportStatus.UNDONE) item { Text("此批次已撤销；后来修改或关联的记录仍保留。") }
                else if (completed) {
                    item { FinancePanel(color = colors.paperTertiary) {
                        Text("旧账已收好", style = MaterialTheme.typography.headlineSmall)
                        state.message?.let { Text(it) }
                        Text("本批次已导入 ${preview.rows.count { it.status == FinanceRowStatus.COMMITTED }} 条", style = MaterialTheme.typography.titleLarge)
                        val remaining = preview.rows.count { it.canonicalId == null }
                        if (remaining > 0) Text("还有 $remaining 条未导入，包括跳过或待处理的记录。", style = MaterialTheme.typography.bodySmall)
                        if (preview.rows.any(FinanceImportReview::eligible))
                            TextButton(vm::backToAccounts, enabled = !state.busy) { Text("检查未导入记录") }
                    } }
                } else if (state.stage == ImportStage.ACCOUNTS) {
                    item { Text("先确认钱的位置", style = MaterialTheme.typography.titleLarge)
                        Text("每个旧账户可关联已有账户，或新建。展开卡片可调整余额起点。", style = MaterialTheme.typography.bodySmall) }
                    items(state.mappings, key = { it.source }) { mapping ->
                        var expanded by androidx.compose.runtime.saveable.rememberSaveable(mapping.source) { mutableStateOf(false) }
                        FinancePanel {
                            TextButton({ expanded = !expanded }, enabled = !state.busy) {
                                Text(mapping.source + " → " + (accounts.find { it.id == mapping.existingId }?.name ?: "新建 · ${mapping.name}") + if (expanded) "  收起" else "  修改")
                            }
                            if (expanded) ImportAccountMapping(mapping, accounts, !state.busy, vm::map)
                            else Text(if (mapping.existingId != null) "沿用已有账户的余额起点" else if (mapping.fromHistory) "从历史重建余额" else "使用填写的当前余额", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                } else if (summary) {
                    item { FinancePanel(color = colors.paperTertiary) {
                        Text("最后核对一次", style = MaterialTheme.typography.headlineSmall)
                        state.message?.let { Text(it) }
                        Text("本次选中 ${state.selected.size} 条", style = MaterialTheme.typography.titleLarge)
                        Text("重复候选选中 ${preview.rows.count { it.status == FinanceRowStatus.DUPLICATE && it.id in state.selected }} 条；其他重复候选保持跳过。")
                        Text("报销、退款只导入真实流水。附属证据保留，不生成额外到账。", style = MaterialTheme.typography.bodySmall)
                        TextButton({ summary = false }, enabled = !state.busy) { Text("返回检查") }
                    } }
                } else {
                    item { FinancePanel(color = colors.paperSecondary) {
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Checkbox(state.policy.ready, { vm.policy(FinanceImportGroup.READY, it) }, enabled = !state.busy)
                            Column(Modifier.weight(1f)) {
                                Text("普通记录 · ${groups[FinanceImportGroup.READY].orEmpty().size} 行", style = MaterialTheme.typography.titleMedium)
                                Text("默认选中，通常不需要逐条查看。", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        TextButton({ detailGroup = FinanceImportGroup.READY }) { Text("查看普通记录") }
                    } }
                    item { Text("留意这些记录", style = MaterialTheme.typography.titleLarge) }
                    FinanceImportGroup.entries.filter { it != FinanceImportGroup.READY }.forEach { group ->
                        val count = groups[group].orEmpty().size
                        if (count > 0) item(key = "group-${group.name}") { FinancePanel {
                            val checked = when(group) {
                                FinanceImportGroup.REIMBURSEMENT -> state.policy.reimbursement
                                FinanceImportGroup.REFUND -> state.policy.refund
                                FinanceImportGroup.FEES -> state.policy.fees
                                FinanceImportGroup.PROXY -> state.policy.proxy
                                FinanceImportGroup.SPLIT -> state.policy.split
                                else -> false
                            }
                            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                if (group !in setOf(FinanceImportGroup.DUPLICATES, FinanceImportGroup.UNSUPPORTED, FinanceImportGroup.SETTLED)) Checkbox(checked, { vm.policy(group, it) }, enabled = !state.busy)
                                Text("${group.label} · $count 行", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                                TextButton({ detailGroup = group }) { Text("查看") }
                            }
                            Text(when(group) {
                                FinanceImportGroup.DUPLICATES -> "默认跳过。只有明确需要重复入账时，才在明细中选择。"
                                FinanceImportGroup.UNSUPPORTED -> "暂不导入；查看原始记录，修正文件副本后重选。"
                                FinanceImportGroup.SETTLED -> "保留旧账已报销证据；到账账户未知，等待业务状态迁移。不会伪造收入或写成待报销。"
                                FinanceImportGroup.FEES -> "勾选表示确认手续费是转账金额之外的实际支出。"
                                else -> "勾选后导入真实流水，关系证据保留，之后可继续整理。"
                            }, style = MaterialTheme.typography.bodySmall)
                        } }
                    }
                    item { Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Checkbox(state.splitTags, vm::tags, enabled = !state.busy)
                        Text("按空格拆分标签", style = MaterialTheme.typography.bodyMedium)
                    }
                        Text("默认保留整个标签单元格。多种问题同时存在时，需要确认各项策略。", style = MaterialTheme.typography.bodySmall)
                        TextButton(vm::backToAccounts, enabled = !state.busy) { Text("修改账户映射") }
                    }
                }
                if (preview.batch.status == FinanceImportStatus.COMMITTED) item {
                    TextButton({ confirmation = "undo" }, enabled = !state.busy) { Text("撤销此批次") }
                }
            }
        }
        if (!summary && !completed) state.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        if (preview != null && preview.batch.status != FinanceImportStatus.UNDONE) {
            if (completed) Button(onBack, enabled = !state.busy, modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                Text("完成 · 返回账目")
            } else Button({ when { state.stage == ImportStage.ACCOUNTS -> vm.prepare(); !summary -> summary = true; else -> confirmation = "commit" } },
                enabled = !state.busy && (state.stage == ImportStage.ACCOUNTS || state.selected.isNotEmpty()), modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                Text(when { state.busy -> "正在处理…"; state.stage == ImportStage.ACCOUNTS -> "下一步 · 检查记录"; !summary -> "下一步 · 核对 ${state.selected.size} 条"; else -> "确认导入 ${state.selected.size} 条" })
            }
        }
    }
    detailGroup?.let { group ->
        ImportGroupSheet(group, groups[group].orEmpty(), state, vm, { evidence = it }) { detailGroup = null }
    }
    if (confirmation != null) AlertDialog(onDismissRequest = { confirmation = null },
        title = { Text(if (confirmation == "undo") "撤销导入" else "确认这一批账目") },
        text = { Text(if (confirmation == "undo") "只作废本批未被修改或再次关联的流水；后来编辑的记录保留。" else "将写入 ${state.selected.size} 条，并按映射新建所需账户。选中的重复候选也会入账。") },
        confirmButton = { TextButton({ if (confirmation == "undo") vm.undo(requireNotNull(state.preview).batch.id) else vm.commit(); confirmation = null }) { Text("确认") } },
        dismissButton = { TextButton({ confirmation = null }) { Text("继续检查") } })
    evidence?.let { row -> FinanceReceipt("原始记录 · 第 ${row.rowNumber} 行", onClose = { evidence = null }) {
        val cells = remember(row.rawPayload) { runCatching { com.google.gson.JsonParser.parseString(row.rawPayload).asJsonObject }.getOrNull() }
        LegacyLedgerReader.headers.forEach { header -> Text("$header：${cells?.get(header)?.asString.orEmpty()}") }
    } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ImportGroupSheet(group: FinanceImportGroup, rows: List<FinanceImportRowEntity>, state: ImportUiState,
    vm: FinanceImportViewModel, evidence: (FinanceImportRowEntity) -> Unit, close: () -> Unit) {
    var query by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    val filtered = remember(rows, query) { rows.filter { query.isBlank() || (it.description + " " + it.sourceAccount + " " + it.rowNumber).contains(query, true) } }
    ModalBottomSheet(onDismissRequest = close, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxHeight(.88f).padding(horizontal = 16.dp).imePadding()) {
            Text("${group.label} · ${rows.size} 行", style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), label = { Text("搜索事由、账户或行号") }, singleLine = true, textStyle = com.qq.closie.life.ui.theme.LifeType.inputFor(query))
            LazyColumn(Modifier.weight(1f)) {
                items(filtered, key = { it.id }) { row ->
                    Row(Modifier.padding(vertical = 8.dp)) {
                        Checkbox(row.id in state.selected, { vm.choose(row.id, it) }, enabled = !state.busy && FinanceImportReview.eligible(row))
                        Column(Modifier.weight(1f)) {
                            Text("第 ${row.rowNumber} 行 · ${row.description.ifBlank { "原备注为空" }}")
                            Text("${row.sourceAccount} · ${row.amountMinor?.let { FinanceMoney.display(it) } ?: "金额待处理"}", style = MaterialTheme.typography.bodySmall)
                            Text(LegacyBusinessSemantics.state(row).label, style = MaterialTheme.typography.bodySmall)
                            if (row.issue.isNotBlank()) Text(row.issue, style = MaterialTheme.typography.bodySmall)
                            TextButton({ evidence(row) }) { Text("查看原始记录") }
                        }
                    }
                }
            }
            Button(close, Modifier.fillMaxWidth().padding(vertical = 10.dp)) { Text("完成检查") }
        }
    }
}

@Composable
private fun ImportAccountMapping(mapping: FinanceAccountMapping, accounts: List<FinanceAccountEntity>, enabled: Boolean,
    onChange: (FinanceAccountMapping) -> Unit) {
    var opening by androidx.compose.runtime.saveable.rememberSaveable(mapping.source, mapping.fromHistory) {
        mutableStateOf(FinanceMoney.input(mapping.openingMinor))
    }
    var error by remember(mapping.source) { mutableStateOf<String?>(null) }
    Column {
        Text(mapping.source, style = MaterialTheme.typography.titleMedium)
        EnumChoice("账户", mapping.existingId ?: "", listOf("" to "新建账户") +
            accounts.filter { it.archivedAt == null && it.currencyCode == "CNY" }.map { it.id to it.name }, enabled) {
            onChange(mapping.copy(existingId = it.ifBlank { null }))
        }
        if (mapping.existingId == null) {
            OutlinedTextField(mapping.name, { onChange(mapping.copy(name = it)) }, enabled = enabled, label = { Text("账户名称") }, textStyle = com.qq.closie.life.ui.theme.LifeType.inputFor(mapping.name))
            EnumChoice("类型", mapping.kind, FinanceAccountKind.entries.map { it to it.label }, enabled) { onChange(mapping.copy(kind = it)) }
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
                }, enabled = enabled, label = { Text("当前余额（欠款填负数）") }, isError = error != null, textStyle = com.qq.closie.life.ui.theme.LifeType.inputFor(opening))
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
    Column {
        TextButton(onClick = { expanded = !expanded }, enabled = enabled) {
            Text("$label：${values.find { it.first == selected }?.second ?: "请选择"}")
        }
        if (expanded) values.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { (value, name) -> FilterChip(value == selected, { onSelect(value); expanded = false },
                    enabled = enabled, modifier = Modifier.weight(1f), label = { Text(name) }) }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}
