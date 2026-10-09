package com.qq.closie.ui.lifeos.modules

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.qq.closie.ui.lifeos.components.LifePage
import com.qq.closie.ui.lifeos.components.LifeEmptyState
import com.qq.closie.ui.lifeos.drawer.LifeModules
import com.qq.closie.ui.lifeos.theme.LifeText
import com.qq.closie.ui.lifeos.theme.LocalLifeOsColors

@Composable
fun ModuleLandingScreen(moduleId: String, onBack: () -> Unit) {
    LifePage(LifeModules.label(moduleId), onBack) {
        Text("尚待后续 Task", style = LifeText.title, color = LocalLifeOsColors.current.ink)
        LifeEmptyState("这里的入口已经留好，正式功能尚未接入。")
    }
}
