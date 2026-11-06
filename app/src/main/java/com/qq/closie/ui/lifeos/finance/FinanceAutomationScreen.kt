package com.qq.closie.ui.lifeos.finance

import com.qq.closie.life.ui.theme.BundledText as Text

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Alignment
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import com.qq.closie.ui.lifeos.settings.SettingsPaperPage
import com.qq.closie.ui.lifeos.settings.SettingsLine
import com.qq.closie.ui.lifeos.settings.SettingsSection
import com.qq.closie.ui.lifeos.theme.*
import java.util.UUID

enum class FinanceAutomationSection { DISCOVERY, REVIEW }

@Composable
internal fun FinanceAutomationScreen(
    repository: FinanceAutomationRepository,
    finance: FinanceRepository,
    initialSection: FinanceAutomationSection = FinanceAutomationSection.DISCOVERY,
    onBack: () -> Unit
) {
    val vm: FinanceAutomationViewModel = viewModel(factory = PresentationFactory(FinanceAutomationViewModel::class.java) {
        FinanceAutomationViewModel(repository, finance)
    })
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val colors = LocalLifeOsColors.current
    val settings = remember(context) { FinanceAutomationSettings(context) }
    val discovery by remember(settings) { settings.observe() }.collectAsStateWithLifecycle(initialValue = settings.read())
    val diagnostic by FinanceDiscoveryDiagnostics.state.collectAsStateWithLifecycle()
    var access by remember { mutableStateOf(false) }
    var notifications by remember { mutableStateOf(false) }
    var overlayPermission by remember { mutableStateOf(false) }
    var settingsError by remember { mutableStateOf<String?>(null) }
    var editRule by remember { mutableStateOf<FinanceRuleSet?>(null) }
    var section by rememberSaveable(initialSection) { mutableStateOf(initialSection) }
    var recentOpen by rememberSaveable { mutableStateOf(false) }
    var principlesOpen by rememberSaveable { mutableStateOf(false) }
    var rulesOpen by rememberSaveable { mutableStateOf(false) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, context) {
        fun refresh() {
            access = context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context)
            notifications = NotificationManagerCompat.from(context).areNotificationsEnabled()
            overlayPermission = Settings.canDrawOverlays(context)
        }
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) refresh() }
        lifecycle.addObserver(observer)
        refresh()
        onDispose { lifecycle.removeObserver(observer) }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        notifications = NotificationManagerCompat.from(context).areNotificationsEnabled()
    }
    fun requestNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context,
                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            permission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    val pending = state.data.proposals.filter {
        it.status == FinanceProposalStatus.ACTIVE || it.status == FinanceProposalStatus.SNOOZED
    }
    val back = {
        if (initialSection == FinanceAutomationSection.DISCOVERY && section == FinanceAutomationSection.REVIEW)
            section = FinanceAutomationSection.DISCOVERY
        else onBack()
    }
    BackHandler(onBack = back)
    SettingsPaperPage(if (section == FinanceAutomationSection.REVIEW) "待确认记录" else "自动发现", back) {
        state.error?.let { Text(it, style = LedgerText.caption, color = MaterialTheme.colorScheme.error) }
        settingsError?.let { Text(it, style = LedgerText.caption, color = MaterialTheme.colorScheme.error) }
        if (section == FinanceAutomationSection.REVIEW) {
            if (state.loaded) {
                val epoch = state.finance.v16?.controlRows?.singleOrNull()?.restoreEpoch ?: 0L
                key(epoch) { FinanceOcrIntake(state.busy) { block -> vm.mediaAction(epoch, block) } }
                SettingsSection("待复核")
                if (pending.isEmpty()) Text("目前没有待确认的候选", style = LedgerText.caption, color = colors.muted)
                FinancePagedItems(pending, "pending-review", { it.id }) { proposal ->
                    SettingsLine(
                        proposal.description.ifBlank { "待确认交易" },
                        if (proposal.status == FinanceProposalStatus.SNOOZED) "稍后处理" else "确认后才会写入正式账目"
                    ) { FinanceReviewRequests.open(proposal.id) }
                }
                SettingsSection("最近处理")
                val processed = state.data.proposals.filterNot { it in pending }.sortedByDescending { it.createdAt }
                FinancePagedItems(processed, "processed-review", { it.id }) { proposal ->
                    val label = when (proposal.status) {
                        FinanceProposalStatus.CONFIRMED -> "已确认"
                        FinanceProposalStatus.REJECTED -> "已忽略"
                        else -> "已失效"
                    }
                    SettingsLine(proposal.description.ifBlank { "交易候选" }, FinanceTime.input(proposal.createdAt), trailing = label)
                    FinanceAttachments(state.finance, FinanceMediaTarget(proposalId = proposal.id), state.busy, state.error) { block ->
                        vm.mediaAction(epoch, block)
                    }
                }
            } else Text("正在读取候选…", style = LedgerText.caption, color = colors.muted)
        } else {
            Text("识别 → 小票 → 你确认 → 入账。识别结果先留在本机。", style = LedgerText.caption, color = colors.muted)
            SettingsSection("三种能力，三种权限")
            DiscoverySwitch(
                "支付通知识别", "读取获授权的微信、支付宝支付提示",
                discovery.enabled, { enabled ->
                    settings.save(settings.read().copy(enabled = enabled))
                    if (enabled && discovery.deliveryMode != FinanceReviewDeliveryMode.INBOX_ONLY) requestNotifications()
                }
            )
            Text("系统通知访问 · " + if (access) "已允许" else "未允许", style = LedgerText.caption, color = colors.muted)
            Text("通知服务 · " + if (access && diagnostic.connected) "已连接" else "未连接", style = LedgerText.caption, color = colors.muted)
            SettingsLine("管理通知访问", trailing = if (access) "系统设置" else "去授权") {
                runCatching { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
                    .onFailure { settingsError = "请在系统设置中搜索“通知使用权”" }
            }
            VisibleDetailSettings(discovery, settings)

            SettingsSection("支付后的复核方式")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(FinanceReviewDeliveryMode.IMMEDIATE, FinanceReviewDeliveryMode.NORMAL, FinanceReviewDeliveryMode.INBOX_ONLY)
                    .forEach { mode ->
                        LedgerFilterChip(
                            selected = discovery.deliveryMode == mode,
                            onClick = {
                                settings.save(settings.read().copy(deliveryMode = mode))
                                if (mode != FinanceReviewDeliveryMode.INBOX_ONLY) requestNotifications()
                            },
                            label = { Text(when (mode) {
                                FinanceReviewDeliveryMode.IMMEDIATE -> "即时小票"
                                FinanceReviewDeliveryMode.NORMAL -> "普通通知"
                                FinanceReviewDeliveryMode.INBOX_ONLY -> "仅待确认"
                            }, style = LedgerText.caption) },
                            modifier = Modifier.weight(1f)
                        )
                    }
            }
            if (discovery.deliveryMode == FinanceReviewDeliveryMode.IMMEDIATE) {
                DiscoverySwitch("通知来源 · 增强小票", "允许支付通知来源在上层显示复核小票",
                    discovery.enhancedReceiptOverlay, { enabled ->
                        settings.save(settings.read().copy(enhancedReceiptOverlay = enabled))
                        if (enabled && !overlayPermission) runCatching {
                            context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:" + context.packageName)))
                        }.onFailure { settingsError = "请在系统设置中管理上层显示权限" }
                    })
                if (!discovery.enhancedReceiptOverlay || !overlayPermission) {
                    Text("通知来源的增强小票暂不可用时，降级为通知或留在待确认。", style = LedgerText.caption, color = colors.muted)
                }
            }
            if (discovery.deliveryMode != FinanceReviewDeliveryMode.INBOX_ONLY && !notifications) {
                Text("系统未允许复核通知。待确认中的候选仍可查看。", style = LedgerText.caption, color = colors.muted)
            }

            SettingsSection("来源与支持范围")
            DiscoverySwitch("微信通知", installedSource(context, "com.tencent.mm"), discovery.wechat, {
                settings.save(settings.read().copy(wechat = it))
            }, enabled = discovery.enabled)
            DiscoverySwitch("支付宝通知", installedSource(context, "com.eg.android.AlipayGphone"), discovery.alipay, {
                settings.save(settings.read().copy(alipay = it))
            }, enabled = discovery.enabled)
            VisibleDetailProviderSettings(discovery, settings)

            val rules = state.data.rules.filter { it.rule.deletedAt == null }
            SettingsLine("记账识别规则", "匹配账户、分类和标签，仅提供建议", trailing = if (state.loaded) "${rules.size} 条" else null) {
                rulesOpen = !rulesOpen
            }
            if (rulesOpen) {
                TextButton(onClick = {
                    val id = UUID.randomUUID().toString()
                    editRule = FinanceRuleSet(FinanceRuleEntity(id, "", 0, true, System.currentTimeMillis()),
                        listOf(FinanceRuleConditionEntity(id, 0, FinanceConditionKind.TEXT_CONTAINS, "")),
                        listOf(FinanceRuleActionEntity(id, 0, FinanceActionKind.SUGGEST_CATEGORY, "")))
                }) { Text("添加规则") }
                FinancePagedItems(rules, "discovery-rules", { it.rule.id }) { rule ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(rule.rule.enabled, { vm.saveRule(rule.copy(rule = rule.rule.copy(enabled = it))) {} }, enabled = !state.busy)
                        TextButton(onClick = { editRule = rule }, enabled = !state.busy) { Text(rule.rule.name) }
                    }
                }
            }
            SettingsLine("待确认记录", "查看候选、已确认和失败结果", trailing = if (state.loaded) pending.size.toString() else null) {
                section = FinanceAutomationSection.REVIEW
            }
            SettingsLine("最近一次识别", discovery.lastStatus.ifBlank { "尚无结果" }, trailing = if (recentOpen) "收起" else "过程") {
                recentOpen = !recentOpen
            }
            if (recentOpen) FinanceDiagnosticsCard(access, discovery)
            SettingsLine("了解自动发现的工作原理", "权限、隐私与数据流向", trailing = if (principlesOpen) "收起" else "展开") {
                principlesOpen = !principlesOpen
            }
            if (principlesOpen) Text(
                "通知和详情各自需要授权。主动截图只在你点击后采集一次，在本机 OCR 后交给你核对。权限撤回、宿主切换或页面受保护时，本次操作会停止。",
                style = LedgerText.caption, color = colors.inkSecondary
            )
        }
    }
    editRule?.let { set -> key(set.rule.id) { FinanceRuleEditor(set, state, vm) { editRule = null } } }
}

@Composable
internal fun DiscoverySwitch(
    title: String, subtitle: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit, enabled: Boolean = true
) {
    val colors = LocalLifeOsColors.current
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = LedgerText.body, color = colors.ink)
            Text(subtitle, style = LedgerText.caption, color = colors.inkSecondary)
        }
        Switch(checked, onCheckedChange, enabled = enabled,
            modifier = Modifier.semantics { contentDescription = title })
    }
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
    FinanceReceipt("记账规则", state.busy, state.error, onClose, actions = {
        Button(onClick = { try { vm.saveRule(draft(), onClose) } catch (e: Exception) { result = e.message } },
            modifier = Modifier.fillMaxWidth(), enabled = !state.busy) { Text("保存规则") }
    }) {
        OutlinedTextField(name, { name = it }, label = { Text("规则名称") }, enabled = !state.busy, textStyle = com.qq.closie.life.ui.theme.LifeType.inputFor(name))
        OutlinedTextField(priority, { priority = it }, label = { Text("顺序（较小的先应用）") }, enabled = !state.busy, textStyle = com.qq.closie.life.ui.theme.LifeType.inputFor(priority))
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
                    label = { Text(if (c.kind in setOf(FinanceConditionKind.AMOUNT_MIN, FinanceConditionKind.AMOUNT_MAX)) "金额（分，例如 2680）" else "包含文字") }, enabled = !state.busy, textStyle = com.qq.closie.life.ui.theme.LifeType.inputFor(c.value))
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
                    label = { Text(if (a.kind == FinanceActionKind.SUGGEST_CATEGORY) "分类 / 子分类" else "建议内容") }, enabled = !state.busy, textStyle = com.qq.closie.life.ui.theme.LifeType.inputFor(a.value))
            }
            TextButton(onClick = { actions.removeAt(index) }, enabled = !state.busy) { Text("移除此建议") }
        }
        TextButton(onClick = { actions += FinanceRuleActionEntity(initial.rule.id, actions.size, FinanceActionKind.ADD_TAG, "") }, enabled = !state.busy) { Text("增加建议") }
        HorizontalDivider()
        EnumChoice("预览来源", source, listOf(FinanceSource.WECHAT to "微信", FinanceSource.ALIPAY to "支付宝")) { source = it }
        OutlinedTextField(sample, { sample = it }, label = { Text("示例支付文字（例如：支付成功 26.80元 瑞幸）") }, textStyle = com.qq.closie.life.ui.theme.LifeType.inputFor(sample))
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
