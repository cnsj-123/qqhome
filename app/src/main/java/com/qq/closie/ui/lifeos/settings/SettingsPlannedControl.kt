package com.qq.closie.ui.lifeos.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import com.qq.closie.life.ui.theme.BundledText as Text
import com.qq.closie.ui.lifeos.theme.LocalLifeOsColors

/** Source 1953:8792–8800: 42dp rows, 34×20 track, 16dp knob. Planned controls have no action. */
@Composable
internal fun SettingsPlannedControl(title:String,subtitle:String) {
    val colors=LocalLifeOsColors.current
    Row(Modifier.fillMaxWidth().heightIn(min=42.dp).padding(vertical=4.dp).semantics(mergeDescendants=true) {
        role=Role.Switch
        toggleableState=ToggleableState.Off
        stateDescription="规划中，尚未接入"
        disabled()
    },verticalAlignment=Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title,style=SettingsText.body,color=colors.inkSecondary)
            Text(subtitle,style=SettingsText.detail,color=colors.inkSecondary)
        }
        Box(Modifier.size(34.dp,20.dp).background(colors.warm.copy(alpha=.4f),RoundedCornerShape(10.dp))) {
            Box(Modifier.align(Alignment.CenterStart).padding(start=2.dp).size(16.dp)
                .background(colors.card.copy(alpha=.4f),CircleShape))
        }
    }
}
