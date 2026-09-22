package com.xiaoming.closie.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.xiaoming.closie.data.backup.BackupManager
import com.xiaoming.closie.data.repository.WardrobeRepository
import com.xiaoming.closie.ui.BackButton
import com.xiaoming.closie.ui.Rose
import java.time.LocalDate
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DataSettingsScreen(repo: WardrobeRepository, back: () -> Unit) {
    val context = LocalContext.current
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
        topBar = { TopAppBar(title = { Text("数据与备份") }, navigationIcon = { BackButton(back) }) }
    ) { pad ->
        Column(
            Modifier.padding(pad).padding(18.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("数据与备份", style = MaterialTheme.typography.titleMedium)
            Text(
                "所有衣橱数据都只保存在本机（JSON + 私有图片）。建议定期导出完整备份，以防卸载、清除数据或换机时丢失。恢复会替换当前本地数据。",
                style = MaterialTheme.typography.bodySmall
            )

            Button(
                onClick = { exportLauncher.launch("closie-backup-${LocalDate.now()}.zip") },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth()
            ) { Text("导出完整备份") }
            OutlinedButton(
                onClick = { restoreLauncher.launch(arrayOf("application/zip", "application/octet-stream", "application/x-zip-compressed")) },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth()
            ) { Text("恢复备份") }
            OutlinedButton(
                onClick = { csvLauncher.launch("closie-wardrobe-${LocalDate.now()}.csv") },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth()
            ) { Text("导出衣橱 CSV") }

            if (busy) Text("处理中…", color = Rose)
            message?.let {
                val isError = it.startsWith("导出失败") || it.startsWith("恢复失败")
                Text(it, color = if (isError) MaterialTheme.colorScheme.error else Rose)
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
                }) { Text("确认恢复") }
            },
            dismissButton = { TextButton(onClick = { confirmRestore = null }) { Text("取消") } }
        )
    }
}
