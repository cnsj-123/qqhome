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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.qq.closie.data.backup.BackupManager
import com.qq.closie.data.backup.BackupRestorePreview
import com.qq.closie.data.backup.EncryptedBackupManager
import com.qq.closie.data.backup.FinanceExportManager
import com.qq.closie.data.backup.RestoreRecoveryPendingException
import com.qq.closie.data.backup.RestoreStartupGate
import com.qq.closie.data.repository.WardrobeRepository
import com.qq.closie.life.data.database.LifeDatabase
import com.qq.closie.life.finance.FinanceRepository
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
    lifeDatabase: LifeDatabase,
    finance: FinanceRepository? = null,
    openExportCenter: Boolean = false
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()

    val restoreOwner = (context.applicationContext as com.qq.closie.ClosieApplication).backupTasks
    val restoreTask by restoreOwner.state.collectAsState()
    var localBusy by remember { mutableStateOf(false) }
    val busy = localBusy || restoreTask.running
    var message by remember { mutableStateOf<String?>(null) }
    var confirmRestore by remember { mutableStateOf<Uri?>(null) }
    var restorePreview by remember { mutableStateOf<BackupRestorePreview?>(null) }
    var legacyZip by remember { mutableStateOf(false) }
    var backupPassword by remember { mutableStateOf("") }
    var repeatedPassword by remember { mutableStateOf("") }
    var restorePassword by remember { mutableStateOf("") }
    var showBackupPassword by remember { mutableStateOf(false) }
    var confirmCsv by remember { mutableStateOf(false) }
    var confirmFinanceCsv by remember { mutableStateOf(false) }
    var exportCenter by rememberSaveable { mutableStateOf(openExportCenter) }
    var historyOpen by rememberSaveable { mutableStateOf(false) }
    val backupHistory = remember(context) { context.getSharedPreferences("lifeos_verified_backups", android.content.Context.MODE_PRIVATE) }
    var verifiedTimes by remember { mutableStateOf(backupHistory.getStringSet("verified_times", emptySet()).orEmpty().mapNotNull(String::toLongOrNull).sortedDescending()) }
    var verifiedSources by remember {
        mutableStateOf(backupHistory.getStringSet("verified_sources", emptySet()).orEmpty().mapNotNull { record ->
            val fields = record.split('\n', limit = 2)
            val at = fields.firstOrNull()?.toLongOrNull() ?: return@mapNotNull null
            fields.getOrNull(1)?.let { at to Uri.parse(it) }
        }.toMap())
    }
    fun verifiedTimeLabel(at: Long): String = java.text.DateFormat.getDateTimeInstance().format(java.util.Date(at))
    fun selectRestoreSource(uri: Uri) {
        restorePreview = null
        restorePassword = ""
        legacyZip = false
        message = null
        confirmRestore = uri
    }
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
                val result = EncryptedBackupManager.export(context, repo, uri, lifeDatabase, password)
                result.onSuccess {
                    val now = System.currentTimeMillis()
                    val previous = verifiedSources
                    verifiedTimes = (listOf(now) + verifiedTimes.filter { previous[it] != uri }).distinct().take(5)
                    val resolver = context.contentResolver
                    val retained = runCatching {
                        resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }.isSuccess || resolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }
                    verifiedSources = previous.filterKeys { it in verifiedTimes } + if (retained) mapOf(now to uri) else emptyMap()
                    (previous.values.toSet() - verifiedSources.values.toSet()).forEach { expired ->
                        runCatching { resolver.releasePersistableUriPermission(expired, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                    }
                    backupHistory.edit().putStringSet("verified_times", verifiedTimes.map(Long::toString).toSet())
                        .putStringSet("verified_sources", verifiedSources.map { (at, source) -> "$at\n$source" }.toSet()).apply()
                }
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
        if (uri != null) selectRestoreSource(uri)
    }
    val financeCsvLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null && finance != null) {
            localBusy = true; message = null
            scope.launch {
                val result = FinanceExportManager.export(context, finance, uri)
                result.onFailure { runCatching { context.contentResolver.delete(uri, null, null) } }
                message = result.fold({ "账目 CSV 已导出" }, { failureMessage("导出失败", it) })
                localBusy = false
            }
        }
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
                "完整备份覆盖衣橱、账本、关系、草稿与应用内管理的媒体。外部附件只保存引用，换机可能需要重新选择原件。系统权限、采集会话与云授权不会恢复。恢复会替换当前本地数据。",
                style = MaterialTheme.typography.bodyMedium,
                color = ClosieColor.InkSecondary
            )

            Text(verifiedTimes.firstOrNull()?.let { "最近验证 · " + verifiedTimeLabel(it) } ?: "尚无验证成功的备份",
                style = MaterialTheme.typography.titleMedium)
            SettingsRow(Icons.Default.Restore, "历史恢复点", if (verifiedTimes.isEmpty()) "验证成功后才会出现" else "${verifiedTimes.size} 个已验证备份") {
                historyOpen = !historyOpen
            }
            if (historyOpen) verifiedTimes.forEach {
                val source = verifiedSources[it]?.takeIf { uri ->
                    context.contentResolver.persistedUriPermissions.any { grant -> grant.uri == uri && grant.isReadPermission }
                }
                if (source != null) {
                    TextButton({ selectRestoreSource(source) }, enabled = !busy) { Text("上次验证 · " + verifiedTimeLabel(it)) }
                } else {
                    Text(verifiedTimeLabel(it) + " · 需重新选择备份文件", style = MaterialTheme.typography.bodySmall)
                }
            }
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
                onClick = { if (!busy) showBackupPassword = true }
            )
            SettingsRow(
                icon = Icons.Default.Restore,
                title = "恢复备份",
                subtitle = "恢复加密备份；旧 ZIP 可在确认页单独选择",
                onClick = { if (!busy) restoreLauncher.launch(arrayOf("application/zip", "application/octet-stream", "application/x-zip-compressed")) }
            )
            SettingsRow(
                icon = Icons.Default.Download,
                title = "导出中心",
                subtitle = "选择表格范围并检查明文风险",
                onClick = { if (!busy) exportCenter = true }
            )
            Text("自动备份与跨设备同步 · 规划中", style = MaterialTheme.typography.bodySmall, color = ClosieColor.InkSecondary)

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
    if (exportCenter) AlertDialog(onDismissRequest = { exportCenter = false }, title = { Text("导出中心") },
        text = { Column {
            Text("表格导出为明文，接收方可读取内容。")
            TextButton({ exportCenter = false; confirmCsv = true }, enabled = !busy) { Text("衣橱清单 · CSV") }
            if (finance != null) TextButton({ exportCenter = false; confirmFinanceCsv = true }, enabled = !busy) { Text("账目与关联 ID · CSV") }
        } }, confirmButton = { TextButton({ exportCenter = false }) { Text("关闭") } })
    if (confirmFinanceCsv) AlertDialog(onDismissRequest = { confirmFinanceCsv = false }, title = { Text("导出明文账目？") },
        text = { Text("范围为当前账本的可导出记录，包含金额、账户和关联 ID。CSV 文件未加密。") },
        confirmButton = { TextButton({ confirmFinanceCsv = false; financeCsvLauncher.launch("lifeos-finance-${LocalDate.now()}.csv") }) { Text("确认导出明文") } },
        dismissButton = { TextButton({ confirmFinanceCsv = false }) { Text("取消") } })
    confirmRestore?.let { uri ->
        AlertDialog(
            onDismissRequest = { if (!busy) { confirmRestore = null; restorePassword = ""; restorePreview = null } },
            title = { Text(if (restorePreview == null) "检查备份" else "确认恢复范围") },
            text = { Column {
                val preview = restorePreview
                if (preview == null) {
                    Text("先校验备份内容，检查完成后再决定是否恢复。")
                    if (!legacyZip) OutlinedTextField(restorePassword, { restorePassword = it }, label = { Text("加密备份口令") },
                        enabled = !busy, visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation())
                    TextButton({ legacyZip = !legacyZip; restorePassword = ""; message = null }, enabled = !busy) {
                        Text(if (legacyZip) "切换到加密备份" else "这是未加密的旧 ZIP")
                    }
                } else {
                    Text(if (preview.includesLifeOs) "衣橱及 Life OS 数据 · 账目记录 ${preview.entries ?: 0} 条" else "旧版备份 · 仅覆盖衣橱数据")
                    Text("确认后会替换上述范围的当前数据。系统权限与云授权需重新确认。")
                }
                if (busy) Text("正在检查备份…")
                message?.let { Text(it, color = ClosieColor.Error) }
            } },
            confirmButton = {
                val preview = restorePreview
                TextButton(enabled = (legacyZip || restorePassword.isNotEmpty()) && !busy, onClick = {
                    val password = if (legacyZip) null else restorePassword.toCharArray()
                    if (preview == null) {
                        localBusy = true; message = null
                        scope.launch {
                            EncryptedBackupManager.inspect(context, uri, password).fold(
                                { restorePreview = it }, { message = failureMessage("检查失败", it) }
                            )
                            localBusy = false
                        }
                    } else if (restoreOwner.start("恢复成功") {
                        EncryptedBackupManager.restoreInspected(context.applicationContext, repo, uri, lifeDatabase, password, preview)
                    }) {
                        restorePassword = ""; confirmRestore = null; restorePreview = null; message = null
                    } else password?.fill('\u0000')
                }) { Text(if (preview == null) "校验并预览" else "确认恢复", color = ClosieColor.Rose) }
            },
            dismissButton = { TextButton(onClick = { confirmRestore = null; restorePassword = ""; restorePreview = null }, enabled = !busy) { Text("取消") } }
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
