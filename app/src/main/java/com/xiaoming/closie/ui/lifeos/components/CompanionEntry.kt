package com.xiaoming.closie.ui.lifeos.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.xiaoming.closie.ui.lifeos.theme.LifeText
import com.xiaoming.closie.ui.lifeos.theme.LocalLifeOsColors

/** Only this fixed entry owns upward drag; the Home scroll area is independent. */
@Composable
fun CompanionEntry(onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val open = rememberUpdatedState(onOpen)
    val threshold = with(LocalDensity.current) { 32.dp.toPx() }
    val colors = LocalLifeOsColors.current
    Column(
        modifier.fillMaxWidth().heightIn(min = 56.dp)
            .clickable(role = Role.Button, onClickLabel = "进入伙伴聊天", onClick = onOpen)
            .pointerInput(threshold) {
                var distance = 0f
                detectVerticalDragGestures(
                    onDragStart = { distance = 0f },
                    onDragEnd = { if (distance < -threshold) open.value(); distance = 0f },
                    onDragCancel = { distance = 0f },
                    onVerticalDrag = { change, delta -> distance += delta; change.consume() }
                )
            }.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(Icons.Outlined.KeyboardArrowUp, contentDescription = null, tint = colors.accent)
        Text("上滑 · 和伙伴聊聊", style = LifeText.caption, color = colors.muted)
    }
}
