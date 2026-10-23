package com.qq.closie.ui.settings

import com.qq.closie.life.ui.theme.BundledText as Text

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.qq.closie.data.backup.BackupManager
import com.qq.closie.data.backup.RestoreRecoveryPendingException
import com.qq.closie.data.backup.RestoreStartupGate
import com.qq.closie.data.repository.WardrobeRepository
import com.qq.closie.life.data.database.LifeDatabase
import com.qq.closie.ui.components.ClosieCompactTopBar
import com.qq.closie.ui.quickcapture.QuickCaptureActivity
import com.qq.closie.ui.quickcapture.QuickCaptureService
import com.qq.closie.ui.theme.ClosieColor
import java.time.LocalDate
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DataSettingsScreen(
    repo: WardrobeRepository,
    back: () -> Unit,
    /**
     * The Life OS database, so 备份与恢复 covers both halves of the app.
     *
     * **Required, not nullable.** A v0.3 backup is only complete if it carries the Life OS section, so
     * there is no such thing as a legitimate "no database" export from this screen. An earlier revision
     * made this nullable with a `null` default, which let the export fall through to a `v2` archive with
     * `includesLifeOs=false` — a wardrobe-only file wearing the v2 format's clothes, indistinguishable
     * at a glance from a complete one. Anyone restoring it would silently lose every Life OS row.
     * Making the parameter required moves that mistake to compile time.
     *
     * Passing the database through rather than looking it up here keeps this screen a renderer — it
     * already receives the wardrobe repository the same way.
     */
    lifeDatabase: LifeDatabase
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()

    val restoreOwner = (context.applicationContext as com.qq.closie.ClosieApplication).backupTasks
    val restoreTask by restoreOwner.state.collectAsState()
    var localBusy by remember { mutableStateOf(false) }
    val busy = localBusy || restoreTask.running
    var message by remember { mutableStateOf<String?>(null) }
    var confirmRestore by remember { mutableStateOf<Uri?>(null) }
    var backupPassword by remember { mutableStateOf("") }
    var repeatedPassword by remember { mutableStateOf("") }
    var restorePassword by remember { mutableStateOf("") }
    var showBackupPassword by remember { mutableStateOf(false) }
    var confirmCsv by remember { mutableStateOf(false) }
    var quickCapture by remember { mutableStateOf(QuickCaptureService.isEnabled(context)) }

    /**
     * A restore in progress, observed for real rather than inferred from this screen's own `busy` flag.
     *
     * ### The bug this closes, and why it is a *consequence* of the Result-contract fix
     *
     * An export or a restore attempted while the gate is closed is now correctly reported as
     * `Result.failure` with `RestoreRecoveryPendingException` as its cause — that is the whole point of
     * putting the lease acquisition inside `runCatching`. But `it.message` for that exception is the bare
     * technical string from the gate, and `result.fold` would render it verbatim: a user who taps 导出衣橱
     * CSV during a restore would read a Kotlin exception message about "恢复进行中" or similar, which is
     * accurate and useless. Worse, the *restore* path has the same problem in reverse — the operation
     * genuinely did not complete, and the message would read 导出失败 rather than "the app is restoring;
     * try again in a moment".
     *
     * So the screen reports the *condition* when it can see it, and falls back to the exception text
     * otherwise. Reading the gate here rather than parsing the message keeps the wording decision in the
     * UI (where it belongs) and keeps the gate's exception free of presentation concerns.
     */
    val restoring by remember {
        RestoreStartupGate.restoreInProgressFlowForUi
    }.collectAsState(initial = RestoreStartupGate.isRestoring)

    /** The message for a failed export/restore, distinguishing "a restore is running" from a real error. */
    fun failureMessage(prefix: String, error: Throwable): String =
        if (restoring || error is RestoreRecoveryPendingException) {
            "应用正在恢复备份，请稍后再试"
        } else {
            "$prefix：${error.message ?: "未知错误"}"
        }

    fun launchConsent() {
        runCatching { context.startActivity(Intent(context, QuickCaptureActivity::class.java)) }
    }

    // Resync the toggle against the real in-process session state (not just persisted prefs). A
    // stale enabled=true with no active session and no pending consent is cleared.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        val pending = QuickCaptureService.hasPendingConsent(context)
        val starting = QuickCaptureService.isSessionStarting()
        val active = QuickCaptureService.isSessionActive()
        quickCapture = pending || starting || active

        if (QuickCaptureService.isEnabled(context) && !pending && !starting && !active) {
            QuickCaptureService.setEnabled(context, false)
            QuickCaptureService.setRunning(context, false)
        }

        if (pending && Settings.canDrawOverlays(context)) {
            QuickCaptureService.setPendingConsent(context, false)
            launchConsent()
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) {
            val password = backupPassword.toCharArray()
            backupPassword = ""; repeatedPassword = ""
            localBusy = true; message = null
            scope.launch {
                val result = com.qq.closie.data.backup.EncryptedBackupManager.export(context, repo, uri, lifeDatabase, password)
                result.onFailure { runCatching { context.contentResolver.delete(uri, null, null) } }
                message = result.fold({ "加密备份已写入并验证可解密，请保管好口令" }, { failureMessage("导出失败", it) })
                localBusy = false
            }
        } else { backupPassword = ""; repeatedPassword = "" }
    }

    val csvLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) {
            localBusy = true; message = null
            scope.launch {
                val result = BackupManager.exportCsv(context, repo, uri)
                result.onFailure { runCatching { context.contentResolver.delete(uri, null, null) } }
                message = result.fold({ "CSV 已导出" }, { failureMessage("导出失败", it) })
                localBusy = false
            }
        }
    }

    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) confirmRestore = uri
    }

    Scaffold(
        containerColor = ClosieColor.Canvas,
        topBar = { ClosieCompactTopBar(title = "数据与备份", onBack = back) }
    ) { pad ->
        Column(
            modifier = Modifier
                .padding(pad)
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "所有衣橱数据都只保存在本机（JSON + 私有图片）。建议定期导出完整备份，以防卸载、清除数据或换机时丢失。恢复会替换当前本地数据。",
                style = MaterialTheme.typography.bodyMedium,
                color = ClosieColor.InkSecondary
            )

            SettingsToggleRow(
                title = "快速采集",
                subtitle = "通过悬浮球从其他 App 的商品页快速录入",
                checked = quickCapture,
                onCheckedChange = { enable ->
                    quickCapture = enable
                    QuickCaptureService.setEnabled(context, enable)
                    if (enable) {
                        if (Settings.canDrawOverlays(context)) {
                            launchConsent()
                        } else {
                            QuickCaptureService.setPendingConsent(context, true)
                            runCatching {
                                context.startActivity(
                                    Intent(
                                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                        Uri.parse("package:${context.packageName}")
                                    )
                                )
                            }
                        }
                    } else {
                        QuickCaptureService.endSession(context)
                    }
                }
            )

            SettingsRow(
                icon = Icons.Default.CloudUpload,
                title = "创建加密备份",
                subtitle = "衣橱、Finance、关系、草稿和媒体；使用独立口令并验证恢复",
                onClick = { showBackupPassword = true }
            )
            SettingsRow(
                icon = Icons.Default.Restore,
                title = "恢复备份",
                subtitle = "恢复加密备份；旧 ZIP 可在确认页单独选择",
                onClick = { restoreLauncher.launch(arrayOf("application/zip", "application/octet-stream", "application/x-zip-compressed")) }
            )
            SettingsRow(
                icon = Icons.Default.Download,
                title = "导出衣橱 CSV",
                subtitle = "导出衣物清单为表格",
                onClick = { confirmCsv = true }
            )

            if (busy) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = ClosieColor.Rose)
                    Text("处理中…", color = ClosieColor.InkSecondary)
                }
            }
            message?.let {
                // A refused-while-restoring message is not an error the user caused or must act on
                // beyond waiting, so it is rendered in the neutral accent rather than the error colour.
                // `failureMessage` is the only producer of that wording, so matching its prefix keeps
                // the decision next to the string instead of re-deriving it from an exception type here.
                val isError = (it.startsWith("导出失败") || it.startsWith("恢复失败")) &&
                    !it.startsWith("应用正在恢复备份")
                Text(it, color = if (isError) ClosieColor.Error else ClosieColor.RosePressed)
            }
        }
    }

    if (showBackupPassword) AlertDialog(
        onDismissRequest = { showBackupPassword = false; backupPassword = ""; repeatedPassword = "" },
        title = { Text("设置备份口令") },
        text = { Column {
            Text("至少 12 个字符。换机恢复需要此口令；丢失后无法解密。未来自动加密同步仍是产品目标，当前仅主动本地备份。")
            OutlinedTextField(backupPassword, { backupPassword = it }, label = { Text("备份口令") },
                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation())
            OutlinedTextField(repeatedPassword, { repeatedPassword = it }, label = { Text("再次输入") },
                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation())
        } },
        confirmButton = { TextButton(enabled = backupPassword.length >= 12 && backupPassword == repeatedPassword,
            onClick = { showBackupPassword = false; exportLauncher.launch("LifeOS-backup-${LocalDate.now()}.lifeos") }) { Text("创建并验证") } },
        dismissButton = { TextButton({ showBackupPassword = false; backupPassword = ""; repeatedPassword = "" }) { Text("取消") } })
    if (confirmCsv) AlertDialog(onDismissRequest = { confirmCsv = false }, title = { Text("导出明文 CSV？") },
        text = { Text("CSV 未加密，接收方可以读取全部导出内容。仅导出当前衣橱清单，请自行保管。") },
        confirmButton = { TextButton({ confirmCsv = false; csvLauncher.launch("closie-wardrobe-${LocalDate.now()}.csv") }) { Text("确认导出明文") } },
        dismissButton = { TextButton({ confirmCsv = false }) { Text("取消") } })
    confirmRestore?.let { uri ->
        AlertDialog(
            onDismissRequest = { confirmRestore = null; restorePassword = "" },
            title = { Text("恢复备份？") },
            text = { Column {
                Text("恢复将替换备份覆盖的数据，系统权限与云授权不会恢复。建议先创建当前数据的加密备份。")
                OutlinedTextField(restorePassword, { restorePassword = it }, label = { Text("加密备份口令") },
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation())
                TextButton(enabled = !busy, onClick = {
                    confirmRestore = null; restorePassword = ""; message = null
                    restoreOwner.start("旧 ZIP 恢复成功，权限需重新评估") {
                        BackupManager.restore(context.applicationContext, repo, uri, lifeDatabase)
                    }
                }) { Text("这是未加密的旧 ZIP，确认恢复") }
            } },
            confirmButton = {
                TextButton(enabled = restorePassword.isNotEmpty() && !busy, onClick = {
                    val u = uri
                    val password = restorePassword.toCharArray()
                    restorePassword = ""
                    confirmRestore = null
                    message = null
                    if (!restoreOwner.start("恢复成功") {
                        try { com.qq.closie.data.backup.EncryptedBackupManager.restore(context.applicationContext, repo, u, lifeDatabase, password) }
                        finally { password.fill('\u0000') }
                    }) password.fill('\u0000')
                }) { Text("确认恢复", color = ClosieColor.Rose) }
            },
            dismissButton = { TextButton(onClick = { confirmRestore = null; restorePassword = "" }) { Text("取消") } }
        )
    }
}

@Composable
private fun SettingsToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = ClosieColor.Surface,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        border = androidx.compose.foundation.BorderStroke(1.dp, ClosieColor.Hairline)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = ClosieColor.InkSecondary)
            }
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
}

@Composable
private fun SettingsRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = MaterialTheme.shapes.large,
        color = ClosieColor.Surface,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        border = androidx.compose.foundation.BorderStroke(1.dp, ClosieColor.Hairline)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Icon(icon, contentDescription = null, tint = ClosieColor.InkSecondary)
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = ClosieColor.InkSecondary)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = ClosieColor.InkTertiary)
        }
    }
}
