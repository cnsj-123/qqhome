package com.xiaoming.closie.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xiaoming.closie.ui.theme.ClosieColor

/**
 * Compact ~48dp top bar shared by the secondary screens (OOTD, Outfits, Studio, Editor, Detail,
 * Settings). It handles status-bar insets itself and avoids the tall default app bar that left a
 * large blank gap below the status bar on the target device.
 */
@Composable
fun ClosieCompactTopBar(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {}
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .height(48.dp)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (onBack != null) {
            ClosieBackButton(onClick = onBack, modifier = Modifier.size(44.dp))
        }
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            color = ClosieColor.Ink,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 4.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        actions()
    }
}
