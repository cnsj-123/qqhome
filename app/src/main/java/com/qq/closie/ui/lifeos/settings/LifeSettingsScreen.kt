package com.qq.closie.ui.lifeos.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qq.closie.BuildConfig
import com.qq.closie.data.appearance.ThemePreset
import com.qq.closie.life.finance.FinanceAutomationRepository
import com.qq.closie.life.finance.FinanceAutomationSettings
import com.qq.closie.life.finance.FinanceDiscoveryDiagnostics
import com.qq.closie.life.finance.FinanceProposalStatus
import com.qq.closie.life.finance.acquisition.VisibleDetailRuntime
import com.qq.closie.life.ui.theme.BundledText as Text
import com.qq.closie.ui.lifeos.theme.*
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

@Composable
fun LifeSettingsScreen(
    appearanceViewModel: AppearanceViewModel,
    automationRepository: FinanceAutomationRepository,
    onBack: () -> Unit,
    onAppearance: () -> Unit,
    onBackup: () -> Unit,
    onFinanceAutomation: () -> Unit,
    onReviewInbox: () -> Unit,
    onFinanceSettings: () -> Unit,
    onPrivacy: () -> Unit,
    onExport: () -> Unit
) {
    val context = LocalContext.current
    val colors = LocalLifeOsColors.current
    val saved by appearanceViewModel.savedAppearance.collectAsStateWithLifecycle()
    val store = remember(context) { FinanceAutomationSettings(context) }
    val discovery by remember(store) { store.observe() }.collectAsStateWithLifecycle(initialValue = store.read())
    val notice by FinanceDiscoveryDiagnostics.state.collectAsStateWithLifecycle()
    val details by VisibleDetailRuntime.state.collectAsStateWithLifecycle()
    val pending by remember(automationRepository) {
        automationRepository.observe().map { snapshot ->
            snapshot.proposals.count { it.status == FinanceProposalStatus.ACTIVE || it.status == FinanceProposalStatus.SNOOZED }
                .takeIf { it > 0 }
        }.catch { emit(null) }
    }.collectAsStateWithLifecycle(initialValue = null)
    val activeTheme = saved?.let {
        ThemePreset.fromId(it.selectedThemeId)?.displayName ?: it.namedThemes
            .firstOrNull { theme -> theme.id == it.selectedThemeId }?.name ?: "自定义配色"
    }
    val disconnected = (discovery.enabled && !notice.connected) || (discovery.visibleDetailEnabled && !details.connected)
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var about by rememberSaveable { mutableStateOf(false) }
    val rows = listOf(
        SettingsGroup("外观", listOf(SettingsDestination("主题", "五色、页面背景与实时预览", Icons.Outlined.Palette, activeTheme, onAppearance))),
        SettingsGroup("账目", listOf(
            SettingsDestination("自动发现与通知", "识别方式、复核与通知规则", Icons.Outlined.Sync,
                when {
                    disconnected -> "未连接"
                    discovery.enabled || discovery.visibleDetailEnabled -> "已连接"
                    else -> "未开启"
                }, onFinanceAutomation),
            SettingsDestination("待确认记录", "确认后才会写入正式账目", Icons.Outlined.FactCheck, pending?.toString(), onReviewInbox),
            SettingsDestination("账目专项设置", "分类、往来、对账、预算与数据", Icons.Outlined.Calculate, action = onFinanceSettings)
        )),
        SettingsGroup("隐私与 AI", listOf(
            SettingsDestination("权限与授权", "用途范围、有效期、授权历史与 Creative Vault", Icons.Outlined.Lock, action = onPrivacy)
        )),
        SettingsGroup("数据", listOf(
            SettingsDestination("备份与恢复", "加密备份与历史恢复点", Icons.Outlined.History, action = onBackup),
            SettingsDestination("导出与媒体存储", "导出范围、文件与媒体位置", Icons.Outlined.FileDownload, action = onExport)
        )),
        SettingsGroup("展示与入口", listOf(
            SettingsDestination("桌面组件", "规划中，当前不可设置", Icons.Outlined.Widgets, "规划中"),
            SettingsDestination("首页展示与分享范围", "按用途授权可以出现在首页或分享的内容", Icons.Outlined.Share, action = onPrivacy)
        )),
        SettingsGroup("关于", listOf(SettingsDestination("关于 Life OS", "版本与隐私边界", Icons.Outlined.Info, action = { about = true })))
    )
    SettingsPaperPage("设置", onBack, showBack = false, actions = {
        IconButton({
            searchOpen = !searchOpen
            if (!searchOpen) query = ""
        }) { Icon(if (searchOpen) Icons.Outlined.Close else Icons.Outlined.Search, if (searchOpen) "关闭设置搜索" else "搜索设置", tint = colors.ink) }
    }) {
        if (searchOpen) OutlinedTextField(
            query, { query = it }, Modifier.fillMaxWidth().padding(bottom = 10.dp),
            label = { Text("搜索设置") }, singleLine = true
        )
        if (query.isBlank() && (disconnected || pending != null)) {
            SettingsDashedRule()
            if (disconnected) SettingsLine("自动发现尚未连接", trailing = "去设置", onClick = onFinanceAutomation)
            pending?.let { SettingsLine("${it} 条账目候选等待确认", trailing = "去确认", onClick = onReviewInbox) }
            SettingsDashedRule()
        }
        val filtered = rows.map { group -> group.copy(rows = group.rows.filter {
            query.isBlank() || (group.title + it.title + it.subtitle).contains(query.trim(), ignoreCase = true)
        }) }.filter { it.rows.isNotEmpty() }
        if (filtered.isEmpty()) Text("没有找到相关设置", Modifier.padding(top = 20.dp), style = LifeText.caption, color = colors.muted)
        filtered.forEach { group ->
            SettingsSection(group.title)
            group.rows.forEach { row -> SettingsLine(row.title, row.subtitle, row.icon, row.trailing, row.action) }
        }
    }
    if (about) AlertDialog(
        onDismissRequest = { about = false },
        title = { Text("关于 Life OS") },
        text = { Text("版本 ${BuildConfig.VERSION_NAME}\n本地记录由你管理。财务用途授权可随时撤销，外部 AI 连接尚未接入。") },
        confirmButton = { TextButton({ about = false }) { Text("关闭") } }
    )
}

private data class SettingsGroup(val title: String, val rows: List<SettingsDestination>)
private data class SettingsDestination(
    val title: String, val subtitle: String, val icon: ImageVector,
    val trailing: String? = null, val action: (() -> Unit)? = null
)

@Composable
internal fun SettingsPaperPage(
    title: String,
    onBack: () -> Unit,
    showBack: Boolean = true,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit
) {
    val colors = LocalLifeOsColors.current
    Scaffold(containerColor = colors.paper) { inner ->
        Column(Modifier.fillMaxSize().padding(inner).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
            Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                if (showBack) IconButton(onBack, Modifier.offset(x = (-8).dp)) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回上一页", Modifier.size(19.dp), tint = colors.inkSecondary)
                }
                Text(title, Modifier.weight(1f), style = LifeText.title, color = colors.ink)
                actions()
            }
            content()
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
internal fun SettingsSection(title: String) {
    Text(title, Modifier.padding(top = 22.dp, bottom = 5.dp), style = LifeText.caption, color = LocalLifeOsColors.current.muted)
}

@Composable
internal fun SettingsLine(
    title: String,
    subtitle: String? = null,
    icon: ImageVector? = null,
    trailing: String? = null,
    onClick: (() -> Unit)? = null
) {
    val colors = LocalLifeOsColors.current
    val interaction = if (onClick == null) Modifier.semantics { disabled() }
        else Modifier.clickable(role = Role.Button, onClick = onClick)
    Row(
        Modifier.fillMaxWidth().then(interaction).heightIn(min = 48.dp).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) Icon(icon, null, Modifier.padding(end = 12.dp).size(18.dp), tint = colors.accent)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = LifeText.body, color = if (onClick == null) colors.inkSecondary else colors.ink)
            subtitle?.let { Text(it, style = LifeText.caption, color = colors.muted) }
        }
        trailing?.let { Text(it, Modifier.widthIn(max = 102.dp).padding(start = 8.dp), style = LifeText.caption, color = colors.muted) }
        if (onClick != null) Icon(Icons.Outlined.ChevronRight, null, Modifier.padding(start = 7.dp).size(14.dp), tint = colors.muted)
    }
}

@Composable
private fun SettingsDashedRule() {
    val colors = LocalLifeOsColors.current
    Canvas(Modifier.fillMaxWidth().height(1.dp)) {
        drawLine(colors.line, Offset.Zero, Offset(size.width, 0f), 1.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 4.dp.toPx())))
    }
}
