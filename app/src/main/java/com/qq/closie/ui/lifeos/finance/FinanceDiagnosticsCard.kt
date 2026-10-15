package com.qq.closie.ui.lifeos.finance

import com.qq.closie.life.ui.theme.BundledText as Text

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
                val traces = diagnostics.recent[source].orEmpty()
                HorizontalDivider(color = colors.line)
                Text("$name · 最近 ${traces.size} 条", style = LifeText.body)
                if (traces.isEmpty()) Text("本次运行尚未收到", style = LifeText.caption)
                traces.forEach { trace ->
                    DiagnosticLine(name, DateTimeFormatter.ofPattern("HH:mm:ss").format(Instant.ofEpochMilli(trace.receivedAt).atZone(ZoneId.systemDefault())))
                    Text(trace.result.label, style = LifeText.body, color = colors.ink)
                    trace.amountMinor?.let { Text(FinanceMoney.display(it), style = LifeText.number) }
                    Text(if (trace.proposalId != null) "已创建待确认记录" else if (trace.result == DiscoveryResult.RECEIVED) "正在处理" else "未创建待确认记录", style = LifeText.caption, color = colors.muted)
                    trace.proposalId?.let { id -> TextButton({ FinanceReviewRequests.open(id) }) { Text("查看待确认") } }
                }
            }
            Text("仅在本次运行保留每种来源最近 ${FinanceDiscoveryDiagnostics.HISTORY_LIMIT} 条诊断，不保留标题、正文或联系人。缺少金额时请手动记账；支付应用未发通知时无法发现。", style = LifeText.caption, color = colors.muted)
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
