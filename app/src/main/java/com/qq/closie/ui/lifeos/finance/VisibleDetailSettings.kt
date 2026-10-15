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
    var disclosure by remember { mutableStateOf(false) }
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
}
