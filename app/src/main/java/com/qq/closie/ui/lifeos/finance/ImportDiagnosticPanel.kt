package com.qq.closie.ui.lifeos.finance

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.qq.closie.ui.lifeos.theme.*

@Composable
internal fun ImportDiagnosticPanel(state: ImportUiState, onRetry: () -> Unit, onChoose: () -> Unit, onBrowse: () -> Unit) {
    val info = state.diagnostic
    var details by remember(info.technical) { mutableStateOf(false) }
    Surface(color = LocalLifeOsColors.current.paperSecondary) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Text("${info.phase.label} · ${if (state.busy) "处理中" else "未完成"}", style = LifeText.title)
            Text("文件：${info.filename ?: "尚未取得名称"} · ${info.format}", style = LifeText.caption, maxLines = 2)
            Text(info.rowCount?.let { "已暂存 $it 行" } ?: "暂存行数尚不可用", style = LifeText.caption)
            state.error?.let { Text(it, style = LifeText.body) }
            if (!state.busy) {
                Row {
                    TextButton(onRetry) { Text(if (info.phase == ImportPhase.COMMIT) "重新检查批次" else "重试") }
                    TextButton(onChoose) { Text("换个文件") }
                }
                Row {
                    TextButton(onBrowse) { Text("浏览所有文件") }
                    if (info.technical != null) TextButton({ details = !details }) { Text("技术详情") }
                }
                if (details) Text("阶段 ${info.phase.name} · ${info.technical}\n不包含工作簿内容。", style = LifeText.caption)
            }
        }
    }
}
