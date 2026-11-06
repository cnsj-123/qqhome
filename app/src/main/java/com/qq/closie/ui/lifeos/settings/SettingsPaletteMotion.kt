package com.qq.closie.ui.lifeos.settings

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import com.qq.closie.data.appearance.CustomTheme
import com.qq.closie.data.appearance.HexColor
import com.qq.closie.life.ui.theme.BundledText as Text
import com.qq.closie.ui.lifeos.theme.LocalLifeOsColors
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.sin

internal fun ThemeField.shortLabel() = when (this) {
    ThemeField.ACCENT -> "主色"
    ThemeField.PAPER -> "纸色"
    ThemeField.SECONDARY -> "辅助色"
    ThemeField.SOFT -> "柔和色"
    ThemeField.WARM -> "暖色"
}

private fun CustomTheme.inks() = listOf(accent, paper, secondary, soft, warm).map { hex ->
    Color(0xFF000000L or hex.removePrefix("#").toLong(16))
}

@Composable
internal fun SettingsColorSwatch(hex: String, modifier: Modifier = Modifier, large: Boolean = false,
    corner: Dp = if (large) 14.dp else 10.dp, edge: Dp = if (large) 1.dp else 2.dp, elevation: Dp = 4.dp) {
    val colors = LocalLifeOsColors.current
    val color = HexColor.normalizeOrNull(hex)?.let { Color(0xFF000000L or it.substring(1).toLong(16)) } ?: colors.paperSecondary
    val shape = RoundedCornerShape(corner)
    Box(modifier.shadow(elevation, shape, ambientColor = colors.ink.copy(alpha = .08f), spotColor = colors.ink.copy(alpha = .08f))
        .background(color, shape).border(edge,
            if (large) colors.line else Color.White, shape))
}

/** A single interruptible timeline, only triggered by a real preset/collection preview choice. */
@Composable
internal fun SettingsInkWipe(event: Long, palette: CustomTheme, reduced: Boolean, modifier: Modifier = Modifier) {
    val reveal = remember { Animatable(1f) }
    var consumed by remember { mutableLongStateOf(event) }
    LaunchedEffect(event, reduced) {
        if (reduced) { reveal.snapTo(1f); return@LaunchedEffect }
        if (event == consumed) return@LaunchedEffect
        consumed = event
        if (reduced) reveal.snapTo(1f) else {
            if (reveal.value >= 1f) reveal.snapTo(0f)
            reveal.animateTo(1f, tween(620, easing = LinearEasing))
        }
    }
    val inks = remember(palette) { palette.inks() }
    if (!reduced) Box(modifier.drawWithCache {
        // Cache the brush geometry. Frames change only translation and opacity.
        val ribbonWidth = size.width * .28f
        val brush = Path().apply {
            moveTo(0f, 0f); lineTo(ribbonWidth, 0f)
            for (step in 1..24) {
                val y = size.height * step / 24f
                lineTo(ribbonWidth + sin(step * 2.7f) * 6.dp.toPx(), y)
            }
            lineTo(0f, size.height); close()
        }
        onDrawBehind {
            val elapsed = reveal.value * 620f
            val opacity = (1f - (elapsed - 540f).coerceAtLeast(0f) / 80f).coerceIn(0f, 1f)
            inks.forEachIndexed { index, color ->
                val p = CubicBezierEasing(.2f, 0f, 0f, 1f).transform(((elapsed - index * 38f) / 468f).coerceIn(0f, 1f))
                if (p > 0f && p < 1f) translate(left = -ribbonWidth + (size.width + ribbonWidth) * p) {
                    drawPath(brush, color, alpha = opacity)
                }
            }
        }
    })
}

/** Save-event ownership remains with the successful DataStore callback, never a button press. */
@Composable
internal fun SettingsPaletteSeal(event: Long, palette: CustomTheme, reduced: Boolean,
    content: @Composable (collapse: Float) -> Unit) {
    val colors = LocalLifeOsColors.current
    val phase = remember { Animatable(1f) }
    val stampScale = remember { Animatable(1f) }
    var consumed by rememberSaveable { mutableLongStateOf(event) }
    var celebrating by remember { mutableStateOf(false) }
    LaunchedEffect(event, reduced) {
        if (event == 0L || event <= consumed) return@LaunchedEffect
        consumed = event
        celebrating = true
        phase.snapTo(0f)
        try { coroutineScope {
            if (!reduced) launch {
                stampScale.snapTo(.72f)
                stampScale.animateTo(1.08f, spring(dampingRatio = .5f, stiffness = 350f, visibilityThreshold = .02f))
                stampScale.animateTo(1f, spring(dampingRatio = .75f, stiffness = 500f))
            }
            phase.animateTo(1f, tween(if (reduced) 150 else 720))
        } } finally { celebrating = false }
    }
    val p = phase.value
    val collapse = if (!celebrating || reduced) 0f else when {
        p < .24f -> p / .24f
        p > .84f -> (1f - p) / .16f
        else -> 1f
    }.coerceIn(0f, 1f)
    Box(Modifier.fillMaxWidth().heightIn(min = 58.dp)) {
        content(collapse)
        if (celebrating) {
            val alpha = if (reduced) (1f - p).coerceIn(0f, 1f) else ((p - .17f) / .12f).coerceIn(0f, 1f) * ((1f - p) / .16f).coerceIn(0f, 1f)
            Surface(Modifier.align(Alignment.TopCenter).size(56.dp).graphicsLayer {
                this.alpha = alpha
                scaleX = if (reduced) 1f else stampScale.value
                scaleY = scaleX
                rotationZ = if (reduced) 0f else -5f
                translationY = if (!reduced && p >= .35f && p <= .47f) sin((p - .35f) / .12f * Math.PI.toFloat() * 2f) * 2.dp.toPx() else 0f
            }, shape = CircleShape, color = colors.paper, border = BorderStroke(1.5.dp, colors.accentDeep)) {
                Box(Modifier.padding(4.dp).border(.5.dp, colors.accentDeep, CircleShape), contentAlignment = Alignment.Center) {
                    Text("已保存", style = SettingsText.caption, color = colors.accentDeep)
                }
            }
            if (!reduced) {
                val inks = remember(palette) { palette.inks() }
                Canvas(Modifier.matchParentSize()) {
                    val t = ((p - .36f) / .55f).coerceIn(0f, 1f) * .4f
                    if (p > .36f && p < .91f) inks.forEachIndexed { index, color ->
                        val x = size.width / 2 + (index - 2) * 70.dp.toPx() * t
                        val y = 28.dp.toPx() - (100f + index % 2 * 35f).dp.toPx() * t + 1200.dp.toPx() * t * t
                        val center = Offset(x, y)
                        rotate(index * 47f + t * 260f, center) {
                            drawRect(color, center - Offset(3.dp.toPx(), 2.dp.toPx()), Size(6.dp.toPx(), 4.dp.toPx()), alpha = 1f - t / .4f)
                        }
                    }
                }
            }
        }
    }
}
