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
    val screenshotEnabled by remember(context) {com.qq.closie.life.finance.acquisition.FinanceScreenshotPreference.observe(context)}
        .collectAsStateWithLifecycle(initialValue=com.qq.closie.life.finance.acquisition.FinanceScreenshotPreference.enabled(context))
    val nativeEntry by com.qq.closie.life.finance.acquisition.FinanceCaptureEntryState.nativeVisible.collectAsStateWithLifecycle()
    var notificationEntry by remember {mutableStateOf(false)}
    var error by remember { mutableStateOf<String?>(null) }
    fun refresh() {
        com.qq.closie.life.finance.acquisition.FinanceProjectionBubbleService.ensure(context)
        notificationEntry=com.qq.closie.life.finance.acquisition.FinanceProjectionBubbleService.available(context)
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
        Text("点击入口后仅采集一次，隐藏 Life OS 浮层，在本机识别后复核。默认不保留原图。", style = MaterialTheme.typography.bodySmall)
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Switch(screenshotEnabled, { enabled ->
                if (enabled) screenshotDisclosure = true
                else { com.qq.closie.life.finance.acquisition.FinanceScreenshotPreference.set(context, false) }
            })
            Text("开启主动截图入口")
        }
        Text("无障碍未连接或 Android 8–10：使用通知中的“财务单次截图”，每次由系统请求授权；也可添加同名快捷设置。入口不依赖后台服务长驻。", style = MaterialTheme.typography.bodySmall)
        Text("Android 11+ 无障碍已连接且交易身份可验证时直接单次截图并原位核对。其他页面走系统授权并交到待确认；不会猜测宿主挂窗。", style = MaterialTheme.typography.bodySmall)
        if(screenshotEnabled) {
            Text("截图入口：" + if(nativeEntry)"原生悬浮按钮" else if(notificationEntry)"通知 / 快捷设置" else "快捷设置（通知权限或渠道未允许）",style=MaterialTheme.typography.bodySmall)
            if(!notificationEntry)TextButton({context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,context.packageName))}) {Text("管理截图入口通知")}
        }
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
            com.qq.closie.life.finance.acquisition.FinanceScreenshotPreference.set(context, true)
            screenshotDisclosure = false
            com.qq.closie.life.finance.acquisition.FinanceProjectionBubbleService.ensure(context)
            if (!Settings.canDrawOverlays(context)) runCatching {
                context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:" + context.packageName)))
            }.onFailure { error = "请在系统设置中允许 Life OS 悬浮显示" }
        }) { Text("同意并开启") } },
        dismissButton = { TextButton({ screenshotDisclosure = false }) { Text("暂不开启") } })

}
