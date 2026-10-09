package com.qq.closie.ui.lifeos.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.qq.closie.ui.lifeos.theme.LifeText
import com.qq.closie.ui.lifeos.theme.LocalLifeOsColors

@Composable
fun LifeEmptyState(text: String, modifier: Modifier = Modifier) {
    Column(modifier.padding(vertical = 18.dp)) {
        Text(text, style = LifeText.body, color = LocalLifeOsColors.current.muted)
    }
}
