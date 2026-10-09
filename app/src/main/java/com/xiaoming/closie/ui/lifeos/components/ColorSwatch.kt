package com.xiaoming.closie.ui.lifeos.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.xiaoming.closie.data.appearance.HexColor
import com.xiaoming.closie.ui.lifeos.theme.LocalLifeOsColors

@Composable
fun ColorSwatch(hex: String, modifier: Modifier = Modifier) {
    val valid = HexColor.normalizeOrNull(hex)
    val color = valid?.let { Color(0xFF000000L or it.substring(1).toLong(16)) } ?: LocalLifeOsColors.current.paperSecondary
    Box(modifier.size(30.dp).background(color, RoundedCornerShape(5.dp))
        .border(1.dp, LocalLifeOsColors.current.line, RoundedCornerShape(5.dp)))
}
