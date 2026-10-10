package com.qq.closie.ui.lifeos.finance

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
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
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.qq.closie.life.finance.*
import com.qq.closie.navigation.modules.PresentationFactory
import java.util.UUID

@Composable
internal fun FinanceAutomationScreen(repository: FinanceAutomationRepository, finance: FinanceRepository, onBack: () -> Unit) {
    val vm: FinanceAutomationViewModel = viewModel(factory = PresentationFactory(FinanceAutomationViewModel::class.java) {
        FinanceAutomationViewModel(repository, finance)
    })
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val settings = remember { FinanceAutomationSettings(context) }
    val settingsFlow = remember(settings) { settings.observe() }
    val discovery by settingsFlow.collectAsStateWithLifecycle(initialValue = settings.read())
    var access by remember { mutableStateOf(false) }
    var notifications by remember { mutableStateOf(false) }
    var settingsError by remember { mutableStateOf<String?>(null) }
    var editRule by remember { mutableStateOf<FinanceRuleSet?>(null) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) {
            access = context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context)
            notifications = NotificationManagerCompat.from(context).areNotificationsEnabled()
        } }
        lifecycle.addObserver(observer)
        access = context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context)
        notifications = NotificationManagerCompat.from(context).areNotificationsEnabled()
        onDispose { lifecycle.removeObserver(observer) }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        notifications = NotificationManagerCompat.from(context).areNotificationsEnabled()
    }
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        TextButton(onClick = onBack) { Text("返回账目") }
        Text("自动发现", style = MaterialTheme.typography.headlineSmall)
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        settingsError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text("通知访问允许应用读取设备通知。本功能只处理下面选中的微信／支付宝支付提示，在本机解析，不上传内容，其他通知忽略。发现的信息仅供确认，不会自动记账。")
                Row { Switch(discovery.enabled, { settings.save(discovery.copy(enabled = it)) }); Text("自动发现 ${if (discovery.enabled) "开启" else "关闭"}") }
                Text("系统通知访问：${if (access) "已允许" else "未允许"}")
                TextButton(onClick = {
                    try { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
                    catch (_: Exception) { settingsError = "无法打开系统设置，请在系统设置中搜索“通知使用权”" }
                }) { Text("管理通知访问") }
                Row { Checkbox(discovery.wechat, { settings.save(discovery.copy(wechat = it)) }); Text("微信 · ${installedSource(context, "com.tencent.mm")}") }
                Row { Checkbox(discovery.alipay, { settings.save(discovery.copy(alipay = it)) }); Text("支付宝 · ${installedSource(context, "com.eg.android.AlipayGphone")}") }
                Row { Checkbox(discovery.backgroundReview, {
                    settings.save(discovery.copy(backgroundReview = it))
                    if (it && Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                        permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                }); Text("后台发现后发出确认提醒") }
                if (discovery.backgroundReview && !notifications) Text("系统通知未允许，发现的记录仍可在这里查看。")
                if (discovery.lastStatus.isNotBlank()) Text(discovery.lastStatus, style = MaterialTheme.typography.bodySmall)
                HorizontalDivider()
                Text("规则只提供建议", style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = {
                    val id = UUID.randomUUID().toString()
                    editRule = FinanceRuleSet(FinanceRuleEntity(id, "", 0, true, System.currentTimeMillis()),
                        listOf(FinanceRuleConditionEntity(id, 0, FinanceConditionKind.TEXT_CONTAINS, "")),
                        listOf(FinanceRuleActionEntity(id, 0, FinanceActionKind.SUGGEST_CATEGORY, "")))
                }) { Text("添加规则") }
            }
            items(state.data.rules.filter { it.rule.deletedAt == null }, key = { it.rule.id }) { rule ->
                Row {
                    Checkbox(rule.rule.enabled, { vm.saveRule(rule.copy(rule = rule.rule.copy(enabled = it))) {} }, enabled = !state.busy)
                    TextButton(onClick = { editRule = rule }, enabled = !state.busy) { Text("${rule.rule.name} · 顺序 ${rule.rule.priority}") }
                }
            }
            item {
                HorizontalDivider()
                val pending = state.data.proposals.count { it.status in setOf(FinanceProposalStatus.ACTIVE, FinanceProposalStatus.SNOOZED) }
                Text("待确认 $pending · 有空再看", style = MaterialTheme.typography.titleMedium)
            }
            items(state.data.proposals.filter { it.status in setOf(FinanceProposalStatus.ACTIVE, FinanceProposalStatus.SNOOZED) }, key = { it.id }) { proposal ->
                TextButton(onClick = { FinanceReviewRequests.open(proposal.id) }) {
                    Text("${proposal.description} · ${proposal.amountMinor?.let { FinanceMoney.display(it) } ?: "金额待确认"} · ${if (proposal.status == FinanceProposalStatus.SNOOZED) "稍后" else "待确认"}")
                }
            }
            item { Text("最近处理", style = MaterialTheme.typography.titleMedium) }
            items(state.data.proposals.filter { it.status !in setOf(FinanceProposalStatus.ACTIVE, FinanceProposalStatus.SNOOZED) }.take(30), key = { it.id }) {
                Text("${FinanceTime.input(it.createdAt)} · ${it.description} · " + when (it.status) {
                    FinanceProposalStatus.CONFIRMED -> "已确认"
                    FinanceProposalStatus.REJECTED -> "已忽略"
                    else -> "已失效"
                }, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    editRule?.let { set -> key(set.rule.id) { FinanceRuleEditor(set, state, vm) { editRule = null } } }
}

@Suppress("DEPRECATION")
private fun installedSource(context: android.content.Context, name: String): String =
    if (runCatching { context.packageManager.getApplicationInfo(name, 0).enabled }.getOrDefault(false)) "已安装" else "未安装或已停用"

@Composable
private fun FinanceRuleEditor(initial: FinanceRuleSet, state: AutomationUiState, vm: FinanceAutomationViewModel, onClose: () -> Unit) {
    var name by remember { mutableStateOf(initial.rule.name) }
    var priority by remember { mutableStateOf(initial.rule.priority.toString()) }
    val conditions = remember { initial.conditions.toMutableStateList() }
    val actions = remember { initial.actions.toMutableStateList() }
    var sample by remember { mutableStateOf("") }
    var source by remember { mutableStateOf(FinanceSource.WECHAT) }
    var result by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    fun draft() = FinanceRuleSet(initial.rule.copy(name = name, priority = requireNotNull(priority.toIntOrNull()) { "顺序请输入整数" }),
        conditions.toList(), actions.toList())
    FinanceReceipt("记账规则", state.busy, state.error, onClose) {
        OutlinedTextField(name, { name = it }, label = { Text("规则名称") }, enabled = !state.busy)
        OutlinedTextField(priority, { priority = it }, label = { Text("顺序（较小的先应用）") }, enabled = !state.busy)
        Text("以下条件同时满足；多条规则命中时，靠前规则的单项建议优先，标签合并。")
        conditions.forEachIndexed { index, c ->
            EnumChoice("条件", c.kind, FinanceConditionKind.entries.map { it to conditionLabel(it) }, !state.busy) {
                conditions[index] = c.copy(kind = it, value = "")
            }
            when (c.kind) {
                FinanceConditionKind.SOURCE -> EnumChoice("来源", c.value, listOf("WECHAT" to "微信", "ALIPAY" to "支付宝"), !state.busy) { conditions[index] = c.copy(value = it) }
                FinanceConditionKind.DIRECTION -> EnumChoice("方向", c.value, FinanceEditorKind.entries.map { it.name to it.label }, !state.busy) { conditions[index] = c.copy(value = it) }
                FinanceConditionKind.ACCOUNT -> EnumChoice("账户", c.value, state.finance.accounts.filter { it.archivedAt == null }.map { it.id to it.name }, !state.busy) { conditions[index] = c.copy(value = it) }
                else -> OutlinedTextField(c.value, { conditions[index] = c.copy(value = it) },
                    label = { Text(if (c.kind in setOf(FinanceConditionKind.AMOUNT_MIN, FinanceConditionKind.AMOUNT_MAX)) "金额（分，例如 2680）" else "包含文字") }, enabled = !state.busy)
            }
            TextButton(onClick = { conditions.removeAt(index) }, enabled = !state.busy) { Text("移除此条件") }
        }
        TextButton(onClick = { conditions += FinanceRuleConditionEntity(initial.rule.id, conditions.size, FinanceConditionKind.TEXT_CONTAINS, "") }, enabled = !state.busy) { Text("增加条件") }
        actions.forEachIndexed { index, a ->
            EnumChoice("建议", a.kind, FinanceActionKind.entries.map { it to actionLabel(it) }, !state.busy) { actions[index] = a.copy(kind = it, value = "") }
            when (a.kind) {
                FinanceActionKind.SUGGEST_ACCOUNT -> EnumChoice("账户", a.value, state.finance.accounts.filter { it.archivedAt == null && it.currencyCode == "CNY" }.map { it.id to it.name }, !state.busy) { actions[index] = a.copy(value = it) }
                FinanceActionKind.SUGGEST_BUSINESS_NATURE -> EnumChoice("性质", a.value, FinanceNature.entries.map { it.name to it.label }, !state.busy) { actions[index] = a.copy(value = it) }
                else -> OutlinedTextField(a.value, { actions[index] = a.copy(value = it) },
                    label = { Text(if (a.kind == FinanceActionKind.SUGGEST_CATEGORY) "分类 / 子分类" else "建议内容") }, enabled = !state.busy)
            }
            TextButton(onClick = { actions.removeAt(index) }, enabled = !state.busy) { Text("移除此建议") }
        }
        TextButton(onClick = { actions += FinanceRuleActionEntity(initial.rule.id, actions.size, FinanceActionKind.ADD_TAG, "") }, enabled = !state.busy) { Text("增加建议") }
        HorizontalDivider()
        EnumChoice("预览来源", source, listOf(FinanceSource.WECHAT to "微信", FinanceSource.ALIPAY to "支付宝")) { source = it }
        OutlinedTextField(sample, { sample = it }, label = { Text("示例支付文字（例如：支付成功 26.80元 瑞幸）") })
        TextButton(onClick = {
            result = try {
                val rule = draft(); FinanceRuleEngine.validate(rule)
                val pkg = FinanceNotificationParser.packages.entries.first { it.value == source }.key
                val proposal = requireNotNull(FinanceNotificationParser.parse(pkg, "preview", "支付通知", sample, System.currentTimeMillis())) { "未识别出明确收支方向和金额" }
                val preview = FinanceRuleEngine.apply(proposal, listOf(rule.copy(rule = rule.rule.copy(enabled = true))))
                "命中：${preview.matched.joinToString().ifBlank { "无" }}\n事由：${preview.proposal.description}\n账户：${state.finance.accounts.find { it.id == preview.proposal.accountId }?.name ?: "待选择"}\n分类：${preview.proposal.category} / ${preview.proposal.subcategory}\n标签：${preview.tags.joinToString()}\n性质：${preview.proposal.nature.label}（仍需确认）"
            } catch (e: Exception) { e.message ?: "无法预览" }
        }) { Text("预览效果（不保存账目）") }
        result?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        Button(onClick = { try { vm.saveRule(draft(), onClose) } catch (e: Exception) { result = e.message } }, enabled = !state.busy) { Text("保存规则") }
        if (state.data.rules.any { it.rule.id == initial.rule.id }) TextButton(onClick = { confirmDelete = true }, enabled = !state.busy) { Text("删除规则") }
    }
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false }, title = { Text("删除这条规则？") },
        text = { Text("已有账目和待确认记录不受影响。") },
        confirmButton = { TextButton(onClick = { vm.deleteRule(initial.rule.id, onClose) }, enabled = !state.busy) { Text("删除") } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } })
}
private fun conditionLabel(kind: FinanceConditionKind) = when (kind) {
    FinanceConditionKind.SOURCE -> "来源"; FinanceConditionKind.TEXT_CONTAINS -> "包含文字"
    FinanceConditionKind.DIRECTION -> "收支方向"; FinanceConditionKind.AMOUNT_MIN -> "最低金额"
    FinanceConditionKind.AMOUNT_MAX -> "最高金额"; FinanceConditionKind.ACCOUNT -> "原账户"
}
private fun actionLabel(kind: FinanceActionKind) = when (kind) {
    FinanceActionKind.SUGGEST_ACCOUNT -> "建议账户"; FinanceActionKind.SUGGEST_CATEGORY -> "建议分类"
    FinanceActionKind.ADD_TAG -> "添加标签"; FinanceActionKind.NORMALIZE_DESCRIPTION -> "整理事由"
    FinanceActionKind.SUGGEST_BUSINESS_NATURE -> "建议业务性质"
}
