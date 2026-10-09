package com.xiaoming.closie.ui.lifeos.modules

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.xiaoming.closie.ui.lifeos.components.LifePage
import com.xiaoming.closie.ui.lifeos.components.LifeEmptyState
import com.xiaoming.closie.ui.lifeos.drawer.LifeModules
import com.xiaoming.closie.ui.lifeos.theme.LifeText
import com.xiaoming.closie.ui.lifeos.theme.LocalLifeOsColors

@Composable
fun ModuleLandingScreen(moduleId: String, onBack: () -> Unit) {
    LifePage(LifeModules.label(moduleId), onBack) {
        Text("尚待后续 Task", style = LifeText.title, color = LocalLifeOsColors.current.ink)
        LifeEmptyState("这里的入口已经留好，正式功能尚未接入。")
    }
}

@Composable
fun CaptureLandingScreen(onBack: () -> Unit, onOpenExistingCapture: () -> Unit) {
    LifePage("Capture · 记一下", onBack) {
        Text("先把看到的收下来", style = LifeText.title)
        LifeEmptyState("现在可使用已有的商品页快速采集：开启悬浮球，识别后审核，再加入衣橱或继续编辑。")
        OutlinedButton(onClick = onOpenExistingCapture) { Text("管理现有快速采集") }
        Spacer(Modifier.height(24.dp))
        Text("更多生活内容的 Capture 将在后续 Task 接入。", style = LifeText.caption, color = LocalLifeOsColors.current.muted)
    }
}
