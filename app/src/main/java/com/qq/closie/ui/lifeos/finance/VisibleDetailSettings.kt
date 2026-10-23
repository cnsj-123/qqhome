package com.qq.closie.ui.lifeos.finance

import android.content.ComponentName
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qq.closie.life.finance.acquisition.VisibleDetailRuntime
import com.qq.closie.R
import com.qq.closie.life.finance.FinanceAutomationSettings
import com.qq.closie.life.finance.FinanceDiscoverySettings
import com.qq.closie.life.finance.acquisition.FinanceAccessibilityService
import com.qq.closie.life.finance.acquisition.ProviderRegistry
import com.qq.closie.life.ui.theme.BundledText as Text

/** Separate, opt-in acquisition switches; the existing receipt delivery preference is shared. */
@Composable
internal fun VisibleDetailSettings(value: FinanceDiscoverySettings, settings: FinanceAutomationSettings) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var connected by remember { mutableStateOf(false) }
    val runtime by VisibleDetailRuntime.state.collectAsStateWithLifecycle()
    var disclosure by remember { mutableStateOf(false) }
    var screenshotDisclosure by remember { mutableStateOf(false) }
    var screenshotEnabled by remember { mutableStateOf(com.qq.closie.life.finance.acquisition.FinanceScreenshotPreference.enabled(context)) }
    var error by remember { mutableStateOf<String?>(null) }
    fun refresh() {
        val component = ComponentName(context, FinanceAccessibilityService::class.java).flattenToString()
        connected = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            .orEmpty().split(':').any { ComponentName.unflattenFromString(it)?.flattenToString() == component }
    }
    DisposableEffect(lifecycle) {
        refresh()
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) refresh() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    Column {
        Text("主动截图识别", style = MaterialTheme.typography.titleMedium)
        Text("点击悬浮入口后仅采集一次，隐藏 Life OS 浮层，在本机识别并打开待确认小票。默认不保留原图。", style = MaterialTheme.typography.bodySmall)
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Switch(screenshotEnabled, { enabled ->
                if (enabled) screenshotDisclosure = true
                else { screenshotEnabled = false; com.qq.closie.life.finance.acquisition.FinanceScreenshotPreference.set(context, false) }
            }, enabled = android.os.Build.VERSION.SDK_INT >= 30)
            Text("显示截图识别悬浮入口")
        }
        if (android.os.Build.VERSION.SDK_INT < 30) Text("此截图入口需要 Android 11 或以上；可使用选图识别或手动录入。", style = MaterialTheme.typography.bodySmall)
        Text("需要无障碍服务已连接及悬浮显示许可；截图用途与自动详情读取独立开关。", style = MaterialTheme.typography.bodySmall)
        Text("可见交易详情识别", style = MaterialTheme.typography.titleMedium)
        Text("手动打开详情后，在本机生成待确认小票。与通知发现分别开关，共用账目审核流程。", style = MaterialTheme.typography.bodySmall)
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Switch(value.visibleDetailEnabled, { if (it) disclosure = true else settings.save(settings.read().copy(visibleDetailEnabled = false)) })
            Text("详情识别 ${if (value.visibleDetailEnabled) "开启" else "关闭"}")
        }
        ProviderRegistry.providers.forEach { provider ->
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Checkbox(provider.key in value.enabledVisibleProviders, { checked ->
                    val live = settings.read()
                    settings.save(live.copy(enabledVisibleProviders = if (checked) live.enabledVisibleProviders + provider.key else live.enabledVisibleProviders - provider.key))
                }, enabled = value.visibleDetailEnabled)
                Text("${provider.displayName} · ${provider.detailScope}")
            }
        }
        Text("仅支持上方列出的详情类型，其他页面会跳过；来源应用不代表付款账户，记账前仍需选择账户。", style = MaterialTheme.typography.bodySmall)
        Text("系统无障碍：${if (connected) "已允许" else "未允许"}", style = MaterialTheme.typography.bodySmall)
        Text("详情服务：" + if (runtime.connected) "已连接" else "未连接", style = MaterialTheme.typography.bodySmall)
        Text("最近读取：" + runtime.phase.label, style = MaterialTheme.typography.bodySmall)
        var stagesOpen by remember { mutableStateOf(false) }
        TextButton({ stagesOpen = !stagesOpen }) { Text(if (stagesOpen) "收起读取过程" else "查看读取过程") }
        if (stagesOpen) runtime.recent.takeLast(8).forEach { stage ->
            Text(java.time.Instant.ofEpochMilli(stage.timestamp).atZone(java.time.ZoneId.systemDefault()).toLocalTime().withNano(0).toString() + " · " + stage.phase.label, style = MaterialTheme.typography.bodySmall)
        }
        TextButton({
            try { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            catch (_: Exception) { error = "请在系统设置中搜索“无障碍”，选择 Life OS 可见交易详情" }
        }) { Text("管理系统无障碍权限") }
        error?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        Text("只保留核对所需的金额、交易时间、来源与交易编号摘要。缺少可靠字段就跳过，不保留完整页面。", style = MaterialTheme.typography.bodySmall)
    }
    if (disclosure) AlertDialog(onDismissRequest = { disclosure = false }, title = { Text("允许在本机识别可见详情？") },
        text = { Text(context.getString(R.string.finance_visible_detail_disclosure)) },
        confirmButton = { TextButton({ settings.save(settings.read().copy(visibleDetailEnabled = true)); disclosure = false }) { Text("同意并开启") } },
        dismissButton = { TextButton({ disclosure = false }) { Text("暂不开启") } })
    if (screenshotDisclosure) AlertDialog(onDismissRequest = { screenshotDisclosure = false },
        title = { Text("开启单次截图识别？") },
        text = { Text("只有你点击悬浮入口才会截图；截图和 OCR 原文不落盘、不上传。当前页面受保护、锁屏、切换或识别失败时，不会自动记账。") },
        confirmButton = { TextButton({
            screenshotEnabled = true
            com.qq.closie.life.finance.acquisition.FinanceScreenshotPreference.set(context, true)
            screenshotDisclosure = false
            if (!Settings.canDrawOverlays(context)) runCatching {
                context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:" + context.packageName)))
            }.onFailure { error = "请在系统设置中允许 Life OS 悬浮显示" }
        }) { Text("同意并开启") } },
        dismissButton = { TextButton({ screenshotDisclosure = false }) { Text("暂不开启") } })

}
