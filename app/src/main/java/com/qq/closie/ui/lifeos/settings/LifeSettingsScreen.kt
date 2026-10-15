package com.qq.closie.ui.lifeos.settings

import com.qq.closie.life.ui.theme.BundledText as Text

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.qq.closie.ui.lifeos.components.LifePage
import com.qq.closie.ui.lifeos.theme.*

@Composable
fun LifeSettingsScreen(onBack: () -> Unit, onAppearance: () -> Unit, onBackup: () -> Unit, onFinanceAutomation: () -> Unit = {}) {
    LifePage("设置", onBack) {
        SettingsRow("外观", "主题与配色", "主题库、视觉试色与功能页效果", Icons.Outlined.Palette, onAppearance)
        SettingsRow("账目", "自动发现与通知", "支付提示、待确认记录与发现状态", Icons.Outlined.Notifications, onFinanceAutomation)
        SettingsRow("数据", "备份与恢复", "保留一份副本，安全恢复生活记录", Icons.Outlined.Backup, onBackup)
    }
}

@Composable
private fun SettingsRow(section: String, title: String, subtitle: String, icon: ImageVector, onClick: () -> Unit) {
    val colors = LocalLifeOsColors.current
    Text(section, Modifier.padding(top = 20.dp, bottom = 8.dp), style = LifeText.caption, color = colors.accentDeep)
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(22.dp), tint = colors.inkSecondary)
        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
            Text(title, style = LifeText.body)
            Text(subtitle, style = LifeText.caption, color = colors.muted)
        }
        Icon(Icons.Outlined.ChevronRight, null, Modifier.size(18.dp), tint = colors.muted)
    }
    HorizontalDivider(color = colors.line)
}
