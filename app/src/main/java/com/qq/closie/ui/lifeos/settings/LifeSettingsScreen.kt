package com.qq.closie.ui.lifeos.settings

import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.qq.closie.ui.lifeos.components.LifePage

@Composable
fun LifeSettingsScreen(onBack: () -> Unit, onAppearance: () -> Unit, onBackup: () -> Unit, onFinanceAutomation: () -> Unit = {}) {
    LifePage("设置", onBack) {
        OutlinedButton(onClick = onAppearance) { Text("主题与配色") }
        OutlinedButton(onClick = onFinanceAutomation) { Text("账目 · 自动发现与通知") }
        OutlinedButton(onClick = onBackup) { Text("备份与恢复") }
    }
}
