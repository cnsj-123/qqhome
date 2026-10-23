package com.qq.closie.ui.lifeos.components

import com.qq.closie.life.ui.theme.BundledText as Text
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.qq.closie.ui.lifeos.theme.*

internal val LocalShellCompanionGesture=staticCompositionLocalOf {false}

/** The shell owns drag and its reveal; standalone previews retain the visible entry's fallback. */
@Composable
fun CompanionEntry(onOpen:()->Unit,modifier:Modifier=Modifier) {
    val open=rememberUpdatedState(onOpen)
    val threshold=with(LocalDensity.current){32.dp.toPx()}
    val colors=LocalLifeOsColors.current
    val drag=if(LocalShellCompanionGesture.current)Modifier else Modifier.pointerInput(threshold) {
        var distance=0f
        detectVerticalDragGestures(onDragStart={distance=0f},
            onDragEnd={if(distance < -threshold)open.value();distance=0f},onDragCancel={distance=0f}) {change,delta->
            distance+=delta;change.consume()
        }
    }
    Column(modifier.fillMaxWidth().heightIn(min=56.dp)
        .clickable(role=Role.Button,onClickLabel="进入伙伴聊天",onClick=onOpen).then(drag).padding(8.dp),
        horizontalAlignment=Alignment.CenterHorizontally) {
        Icon(Icons.Outlined.KeyboardArrowUp,null,tint=colors.accent)
        Text("上滑 · 和伙伴聊聊",style=LifeText.caption,color=colors.muted)
    }
}
