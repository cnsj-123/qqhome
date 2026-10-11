package com.qq.closie.ui.lifeos.finance

import android.content.ComponentName
import android.service.notification.NotificationListenerService
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qq.closie.life.finance.*
import com.qq.closie.ui.lifeos.theme.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
internal fun FinanceDiagnosticsCard(access: Boolean, settings: FinanceDiscoverySettings) {
    val diagnostics by FinanceDiscoveryDiagnostics.state.collectAsStateWithLifecycle()
    val colors = LocalLifeOsColors.current
    val context = LocalContext.current
    var reconnectError by remember { mutableStateOf<String?>(null) }
    Surface(color = colors.paperSecondary, shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("发现状态", style = LifeText.title)
            DiagnosticLine("通知访问", when { !access -> "未授权"; diagnostics.connected -> "已连接"; else -> "已授权 · 尚未连接" })
            DiagnosticLine("自动发现", if (settings.enabled) "已开启" else "已关闭")
            DiagnosticLine("微信 / 支付宝", "${if (settings.wechat) "开" else "关"} / ${if (settings.alipay) "开" else "关"}")
            if (access && !diagnostics.connected) TextButton({
                runCatching { NotificationListenerService.requestRebind(ComponentName(context, FinanceNotificationListener::class.java)) }
                    .onSuccess { reconnectError = "已请求重新连接；仍未连接时，请重新开启系统通知访问。" }
                    .onFailure { reconnectError = "未能请求连接，请在系统设置重新开启通知访问。" }
            }) { Text("重新连接") }
            reconnectError?.let { Text(it, style = LifeText.caption) }
            listOf(FinanceSource.WECHAT to "微信", FinanceSource.ALIPAY to "支付宝").forEach { (source, name) ->
                val trace = diagnostics.latest[source]
                HorizontalDivider(color = colors.line)
                DiagnosticLine("最近$name", trace?.receivedAt?.let {
                    DateTimeFormatter.ofPattern("HH:mm:ss").format(Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()))
                } ?: "本次运行尚未收到")
                if (trace != null) {
                    Text(trace.result.label, style = LifeText.body, color = colors.ink)
                    trace.amountMinor?.let { Text(FinanceMoney.display(it), style = LifeText.number) }
                    trace.proposalId?.let { id -> TextButton({ FinanceReviewRequests.open(id) }) { Text("查看待确认") } }
                }
            }
            Text("仅保留本次运行中两种来源的最近状态，不保存诊断原文。支付应用未发通知时无法发现。", style = LifeText.caption, color = colors.muted)
            TextButton(FinanceDiscoveryDiagnostics::clear) { Text("清除诊断状态") }
        }
    }
}

@Composable
private fun DiagnosticLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = LifeText.caption, color = LocalLifeOsColors.current.muted)
        Text(value, style = LifeText.body)
    }
}
