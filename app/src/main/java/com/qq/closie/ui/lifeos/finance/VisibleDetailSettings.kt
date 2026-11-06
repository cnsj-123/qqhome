package com.qq.closie.ui.lifeos.finance

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qq.closie.R
import com.qq.closie.life.finance.FinanceAutomationSettings
import com.qq.closie.life.finance.FinanceDiscoverySettings
import com.qq.closie.life.finance.acquisition.FinanceAccessibilityService
import com.qq.closie.life.finance.acquisition.FinanceCaptureEntryState
import com.qq.closie.life.finance.acquisition.FinanceProjectionBubbleService
import com.qq.closie.life.finance.acquisition.FinanceScreenshotPreference
import com.qq.closie.life.finance.acquisition.ProviderRegistry
import com.qq.closie.life.finance.acquisition.VisibleDetailRuntime
import com.qq.closie.life.ui.theme.BundledText as Text
import com.qq.closie.ui.lifeos.settings.SettingsLine
import com.qq.closie.ui.lifeos.theme.LifeText
import com.qq.closie.ui.lifeos.theme.LocalLifeOsColors
import java.time.Instant
import java.time.ZoneId

/** Each source has an independent opt-in and a separate live platform state. */
@Composable
internal fun VisibleDetailSettings(value: FinanceDiscoverySettings, settings: FinanceAutomationSettings) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val colors = LocalLifeOsColors.current
    val runtime by VisibleDetailRuntime.state.collectAsStateWithLifecycle()
    val nativeEntry by FinanceCaptureEntryState.nativeVisible.collectAsStateWithLifecycle()
    val screenshotEnabled by remember(context) { FinanceScreenshotPreference.observe(context) }
        .collectAsStateWithLifecycle(initialValue = FinanceScreenshotPreference.enabled(context))
    var accessibilityAllowed by remember { mutableStateOf(false) }
    var notificationEntry by remember { mutableStateOf(false) }
    var detailsDisclosure by remember { mutableStateOf(false) }
    var screenshotDisclosure by remember { mutableStateOf(false) }
    var stagesOpen by rememberSaveable { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun open(intent: Intent, help: String) {
        runCatching { context.startActivity(intent) }.onFailure { error = help }
    }
    DisposableEffect(lifecycle, context) {
        fun refresh() {
            FinanceProjectionBubbleService.ensure(context)
            notificationEntry = FinanceProjectionBubbleService.available(context)
            val component = ComponentName(context, FinanceAccessibilityService::class.java)
            accessibilityAllowed = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                .orEmpty().split(':').any { ComponentName.unflattenFromString(it) == component }
        }
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) refresh() }
        lifecycle.addObserver(observer)
        refresh()
        onDispose { lifecycle.removeObserver(observer) }
    }

    DiscoverySwitch("可见交易详情识别", "只读取已授权来源的可见交易详情", value.visibleDetailEnabled, { enabled ->
        if (enabled) detailsDisclosure = true else settings.save(settings.read().copy(visibleDetailEnabled = false))
    })
    Text("系统无障碍 · " + if (accessibilityAllowed) "已允许" else "未允许", style = LedgerText.caption, color = colors.muted)
    Text("详情服务 · " + if (accessibilityAllowed && runtime.connected) "已连接" else "未连接", style = LedgerText.caption, color = colors.muted)
    SettingsLine("管理无障碍权限", trailing = if (accessibilityAllowed) "系统设置" else "去授权") {
        open(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS), "请在系统设置搜索“无障碍”，选择 Life OS 可见交易详情")
    }

    DiscoverySwitch("悬浮按钮 · 主动截图", "点击一次、本机 OCR、由你核对", screenshotEnabled, { enabled ->
        if (enabled) screenshotDisclosure = true else FinanceScreenshotPreference.set(context, false)
    })
    if (screenshotEnabled) {
        Text("截图入口 · " + when {
            nativeEntry -> "原生悬浮按钮可用"
            notificationEntry -> "通知入口可用，也可添加快捷设置"
            else -> "通知入口不可用，可添加快捷设置"
        }, style = LedgerText.caption, color = colors.muted)
        if (!notificationEntry) SettingsLine("管理截图入口通知") {
            open(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
                "请在系统设置中检查 Life OS 通知权限")
        }
    }
    SettingsLine("最近详情读取", runtime.phase.label, trailing = if (stagesOpen) "收起" else "过程") { stagesOpen = !stagesOpen }
    if (stagesOpen) {
        if (runtime.recent.isEmpty()) Text("本次运行没有读取记录", style = LedgerText.caption, color = colors.muted)
        runtime.recent.takeLast(8).asReversed().forEach { stage ->
            val time = Instant.ofEpochMilli(stage.timestamp).atZone(ZoneId.systemDefault()).toLocalTime().withNano(0)
            Text("$time · ${stage.phase.label}", style = LedgerText.caption, color = colors.inkSecondary)
        }
    }
    error?.let { Text(it, style = LedgerText.caption, color = MaterialTheme.colorScheme.error) }

    if (detailsDisclosure) AlertDialog(
        onDismissRequest = { detailsDisclosure = false },
        title = { Text("允许在本机识别可见详情？") },
        text = { Text(context.getString(R.string.finance_visible_detail_disclosure)) },
        confirmButton = { TextButton({
            settings.save(settings.read().copy(visibleDetailEnabled = true))
            detailsDisclosure = false
        }) { Text("同意并开启") } },
        dismissButton = { TextButton({ detailsDisclosure = false }) { Text("暂不开启") } }
    )
    if (screenshotDisclosure) AlertDialog(
        onDismissRequest = { screenshotDisclosure = false },
        title = { Text("开启单次截图识别？") },
        text = { Text("只有你点击悬浮入口才会截图。原图和 OCR 正文不会自动保存或上传。当前页面受保护、锁屏、切换或识别失败时，本次操作会停止。") },
        confirmButton = { TextButton({
            FinanceScreenshotPreference.set(context, true)
            screenshotDisclosure = false
            FinanceProjectionBubbleService.ensure(context)
            notificationEntry = FinanceProjectionBubbleService.available(context)
            if (!Settings.canDrawOverlays(context)) open(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + context.packageName)),
                "请在系统设置中允许 Life OS 悬浮显示"
            )
        }) { Text("同意并开启") } },
        dismissButton = { TextButton({ screenshotDisclosure = false }) { Text("暂不开启") } }
    )
}

@Composable
internal fun VisibleDetailProviderSettings(value: FinanceDiscoverySettings, settings: FinanceAutomationSettings) {
    ProviderRegistry.providers.forEach { provider ->
        DiscoverySwitch(provider.displayName + "详情", provider.detailScope, provider.key in value.enabledVisibleProviders, { checked ->
            val live = settings.read()
            val enabled = if (checked) live.enabledVisibleProviders + provider.key else live.enabledVisibleProviders - provider.key
            settings.save(live.copy(enabledVisibleProviders = enabled))
        }, enabled = value.visibleDetailEnabled)
    }
}
