package com.qq.closie.ui.lifeos.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qq.closie.ClosieApplication
import com.qq.closie.life.permissions.*
import com.qq.closie.life.ui.theme.BundledText as Text
import com.qq.closie.ui.lifeos.theme.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

private fun ModuleReadMode.title() = when (this) {
    ModuleReadMode.ALLOW -> "允许访问"
    ModuleReadMode.ASK -> "访问前询问"
    ModuleReadMode.DENY -> "禁止访问"
}

/** The row edits the actual AI dispatch gate. Record-purpose grants remain a separate control. */
@Composable
internal fun FinanceModulePermissionControl(epoch: Long, loaded: Boolean) {
    val app = LocalContext.current.applicationContext as ClosieApplication
    val repository = app.moduleAccess
    var error by remember { mutableStateOf<String?>(null) }
    val rulesFlow = remember(repository) { repository.rules.catch { error = "模块策略读取失败，请重新进入后重试"; emit(emptyMap()) } }
    val rules by rulesFlow.collectAsStateWithLifecycle(initialValue = emptyMap())
    val pending by repository.owner.pending.collectAsStateWithLifecycle()
    val stored = rules[ReadModule.FINANCE]
    val mode = stored?.takeIf { it.epoch == epoch }?.mode ?: ModuleReadMode.ASK
    var open by remember { mutableStateOf(false) }
    var editingEpoch by remember { mutableLongStateOf(epoch) }
    var editingRevision by remember { mutableLongStateOf(0L) }
    var editingGeneration by remember { mutableLongStateOf(0L) }
    var request by remember { mutableStateOf<ModuleReadRequest?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val ready = loaded && rules.isNotEmpty()
    SettingsLine("财务 Finance", "模块 AI 读取策略；仍受具体用途、字段和记录范围限制",
        Icons.Outlined.AccountBalanceWallet, if (ready) mode.title() else "读取策略",
        onClick = if (ready) { {
            editingEpoch = epoch; editingRevision = stored?.revision ?: 0L
            editingGeneration = com.qq.closie.data.backup.RestoreStartupGate.state.value.restoreGeneration
            request = pending[ReadModule.FINANCE]?.takeIf { it.restoreEpoch == epoch && it.restoreGeneration == editingGeneration }
            message = null; error = null; open = true
        } } else null)
    error?.let { Text(it, style = LifeText.caption, color = MaterialTheme.colorScheme.error) }
    message?.let { Text(it, style = LifeText.caption, color = LocalLifeOsColors.current.accentDeep) }
    if (open) ModuleReadPermissionDialog(mode, request, busy, error, onClose = { if (!busy) open = false }) { selected, duration ->
        busy = true; error = null
        scope.launch {
            try {
                repository.owner.authorize(ReadModule.FINANCE, selected, duration, editingEpoch, request, editingRevision, editingGeneration)
                message = when (duration) {
                    ModuleGrantDuration.ONCE -> "已确认此读取请求，使用后失效"
                    ModuleGrantDuration.CONVERSATION -> "已更新此对话的读取权限，结束对话或重启后失效"
                    ModuleGrantDuration.LONG_TERM -> "模块策略已更新；备份恢复后需重新确认"
                }
                open = false
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.message ?: "模块策略未保存" }
            finally { busy = false }
        }
    }
}

@Composable
private fun ModuleReadPermissionDialog(initial: ModuleReadMode, request: ModuleReadRequest?, busy: Boolean, error: String?,
    onClose: () -> Unit, onConfirm: (ModuleReadMode, ModuleGrantDuration) -> Unit) {
    var selected by rememberSaveable { mutableStateOf(initial) }
    var duration by rememberSaveable { mutableStateOf(ModuleGrantDuration.LONG_TERM) }
    val colors = LocalLifeOsColors.current
    AlertDialog(onDismissRequest = { if (!busy) onClose() }, containerColor = colors.paper,
        title = { Text("财务模块授权", style = LifeText.title) }, text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("当前权限 · ${initial.title()}", style = LifeText.caption)
                ModuleReadMode.entries.forEach { mode ->
                    FilterChip(selected == mode, { selected = mode }, enabled = !busy,
                        label = { Column(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                            Text(mode.title())
                            Text(when (mode) {
                                ModuleReadMode.ALLOW -> "允许已授权用途的模块读取，不扩大记录范围。"
                                ModuleReadMode.ASK -> "每次读取都需要对应请求的明确确认。"
                                ModuleReadMode.DENY -> "AI 不能通过读取工具访问此模块。"
                            }, style = LifeText.caption)
                        } })
                }
                Text("授权有效期", style = LifeText.body)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ModuleGrantDuration.entries.forEach { item ->
                        val enabled = !busy && when (item) {
                            ModuleGrantDuration.ONCE -> request != null
                            ModuleGrantDuration.CONVERSATION -> request?.conversationId != null
                            ModuleGrantDuration.LONG_TERM -> true
                        }
                        FilterChip(duration == item, { duration = item }, enabled = enabled, label = { Text(when (item) {
                            ModuleGrantDuration.ONCE -> "仅本次"
                            ModuleGrantDuration.CONVERSATION -> "本次对话"
                            ModuleGrantDuration.LONG_TERM -> "长期"
                        }, style = LifeText.caption) })
                    }
                }
                if (request == null) Text("仅本次与本次对话需先发起真实读取请求。", style = LifeText.caption, color = colors.muted)
                Text("当前无真实 AI 连接，不会因允许访问而启动网络或发送数据。写入财务仍须人工确认。", style = LifeText.caption, color = colors.muted)
                error?.let { Text(it, style = LifeText.caption, color = MaterialTheme.colorScheme.error) }
            }
        }, confirmButton = { TextButton({ onConfirm(selected, duration) }, enabled = !busy) { Text(if (busy) "保存中…" else "确认更新") } },
        dismissButton = { TextButton(onClose, enabled = !busy) { Text("取消") } })
}
