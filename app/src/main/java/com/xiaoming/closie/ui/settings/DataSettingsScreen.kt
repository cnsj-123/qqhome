package com.xiaoming.closie.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import com.xiaoming.closie.data.backup.BackupManager
import com.xiaoming.closie.data.repository.WardrobeRepository
import com.xiaoming.closie.ui.components.ClosieBackButton
import com.xiaoming.closie.ui.theme.ClosieColor
import java.time.LocalDate
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DataSettingsScreen(repo: WardrobeRepository, back: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()

    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var confirmRestore by remember { mutableStateOf<Uri?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) {
            busy = true; message = null
            scope.launch {
                val result = BackupManager.export(context, repo, uri)
                result.onFailure { runCatching { context.contentResolver.delete(uri, null, null) } }
                message = result.fold({ "备份已导出" }, { "导出失败：${it.message ?: "未知错误"}" })
                busy = false
            }
        }
    }

    val csvLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) {
            busy = true; message = null
            scope.launch {
                val result = BackupManager.exportCsv(context, repo, uri)
                result.onFailure { runCatching { context.contentResolver.delete(uri, null, null) } }
                message = result.fold({ "CSV 已导出" }, { "导出失败：${it.message ?: "未知错误"}" })
                busy = false
            }
        }
    }

    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) confirmRestore = uri
    }

    Scaffold(
        containerColor = ClosieColor.Canvas,
        topBar = { TopAppBar(title = { Text("数据与备份") }, navigationIcon = { ClosieBackButton(onClick = back) }, colors = TopAppBarDefaults.topAppBarColors(containerColor = ClosieColor.Canvas)) }
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

            SettingsRow(
                icon = Icons.Default.CloudUpload,
                title = "导出完整备份",
                subtitle = "将衣橱数据和图片打包为 ZIP",
                onClick = { exportLauncher.launch("closie-backup-${LocalDate.now()}.zip") }
            )
            SettingsRow(
                icon = Icons.Default.Restore,
                title = "恢复备份",
                subtitle = "从 ZIP 恢复数据和图片",
                onClick = { restoreLauncher.launch(arrayOf("application/zip", "application/octet-stream", "application/x-zip-compressed")) }
            )
            SettingsRow(
                icon = Icons.Default.Download,
                title = "导出衣橱 CSV",
                subtitle = "导出衣物清单为表格",
                onClick = { csvLauncher.launch("closie-wardrobe-${LocalDate.now()}.csv") }
            )

            if (busy) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = ClosieColor.Rose)
                    Text("处理中…", color = ClosieColor.InkSecondary)
                }
            }
            message?.let {
                val isError = it.startsWith("导出失败") || it.startsWith("恢复失败")
                Text(it, color = if (isError) ClosieColor.Error else ClosieColor.RosePressed)
            }
        }
    }

    confirmRestore?.let { uri ->
        AlertDialog(
            onDismissRequest = { confirmRestore = null },
            title = { Text("恢复备份？") },
            text = { Text("恢复备份将替换当前本地衣橱数据，且无法撤销。建议先导出一份当前备份。") },
            confirmButton = {
                TextButton(onClick = {
                    val u = uri
                    confirmRestore = null
                    busy = true; message = null
                    scope.launch {
                        val result = BackupManager.restore(context, repo, u)
                        message = result.fold({ "恢复成功" }, { "恢复失败：${it.message ?: "未知错误"}" })
                        busy = false
                    }
                }) { Text("确认恢复", color = ClosieColor.Rose) }
            },
            dismissButton = { TextButton(onClick = { confirmRestore = null }) { Text("取消") } }
        )
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
