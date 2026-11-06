package com.qq.closie.ui.lifeos.finance

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.qq.closie.life.ui.theme.BundledText as Text
import com.qq.closie.ui.lifeos.theme.LocalLifeOsColors

/** The outlined 28dp paper chips in the supplied design, without M3's leading check mark. */
@Composable
internal fun LedgerFilterChip(selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier,
    enabled: Boolean = true, label: @Composable () -> Unit) {
    val colors = LocalLifeOsColors.current
    Box(modifier.heightIn(min = 40.dp), contentAlignment = Alignment.Center) {
        Surface(onClick, enabled = enabled, modifier = Modifier.semantics { this.selected = selected },
            shape = RoundedCornerShape(8.dp), border = BorderStroke(1.dp, if (selected) colors.accent else colors.line),
            color = if (selected) colors.paperTertiary else Color.Transparent,
            contentColor = if (selected) colors.accentDeep else colors.inkSecondary) {
            Box(Modifier.heightIn(min = 28.dp).padding(horizontal = 12.dp, vertical = 5.dp), contentAlignment = Alignment.Center) { label() }
        }
    }
}

@Composable
internal fun LedgerDashedRule(modifier: Modifier = Modifier) {
    val color = LocalLifeOsColors.current.line
    Canvas(modifier.fillMaxWidth().height(1.dp)) {
        drawLine(color, Offset.Zero, Offset(size.width, 0f), 1.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx())))
    }
}

/** Every digit retains its own vertical track; accessibility reads the complete current value. */
@Composable
internal fun LedgerOdometer(value: String, style: TextStyle = LedgerText.amount,
    color: Color = LocalLifeOsColors.current.ink, modifier: Modifier = Modifier) {
    Row(modifier.clearAndSetSemantics { text = AnnotatedString(value) }, verticalAlignment = Alignment.CenterVertically) {
        value.forEachIndexed { position, char ->
            androidx.compose.runtime.key(position) {
                AnimatedContent(char, transitionSpec = {
                    (slideInVertically(tween(300, easing = LedgerMotion.EaseOutStrong)) { it } + fadeIn(tween(150)))
                        .togetherWith(slideOutVertically(tween(300, easing = LedgerMotion.EaseOutStrong)) { -it } + fadeOut(tween(150)))
                }, label = "ledger-digit") { Text(it.toString(), style = style, color = color, maxLines = 1) }
            }
        }
    }
}
