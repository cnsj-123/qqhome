package com.qq.closie.ui.lifeos.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import com.qq.closie.data.appearance.FunctionPageStyle
import com.qq.closie.ui.lifeos.theme.LocalLifeOsColors

/** One mounted content tree. Opening this surface never changes the navigation back stack. */
@Composable
fun LifeFunctionPages(
    open: Boolean, enabled: Boolean, style: FunctionPageStyle, reducedMotion: Boolean,
    onOpenChange: (Boolean) -> Unit, functionPage: @Composable () -> Unit, content: @Composable () -> Unit
) {
    val colors = LocalLifeOsColors.current
    var progress by remember { mutableFloatStateOf(if (open) 1f else 0f) }
    var dragging by remember { mutableStateOf(false) }
    val currentOpen by rememberUpdatedState(open)
    val changeOpen by rememberUpdatedState(onOpenChange)
    val edge = with(LocalDensity.current) { 24.dp.toPx() }
    LaunchedEffect(open, dragging, reducedMotion) {
        if (!dragging) {
            if (reducedMotion) progress = if (open) 1f else 0f
            else animate(progress, if (open) 1f else 0f, animationSpec = tween(230)) { value, _ -> progress = value }
        }
    }
    BackHandler(open || progress > 0f) { onOpenChange(false) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val revealWidth = maxWidth * .84f
        val travel = with(LocalDensity.current) { revealWidth.toPx() }
        val layered = style == FunctionPageStyle.LAYERED && !reducedMotion
        Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(colors.paperSecondary, colors.paper)))
            .pointerInput(enabled, travel, edge) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (down.position.x <= edge || down.position.x >= size.width - edge) return@awaitEachGesture
                    var owned = false
                    while (!owned) {
                        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
                        if (!change.pressed || change.isConsumed) return@awaitEachGesture
                        val delta = change.position - down.position
                        when (LifeSwipeIntent.resolve(delta.x, delta.y, viewConfiguration.touchSlop)) {
                            LifeSwipeIntent.Direction.VERTICAL -> return@awaitEachGesture
                            LifeSwipeIntent.Direction.LEFT, LifeSwipeIntent.Direction.RIGHT -> {
                                if ((!currentOpen && delta.x < 0f) || (currentOpen && delta.x > 0f)) return@awaitEachGesture
                                dragging = true
                                progress = (progress + delta.x / travel).coerceIn(0f, 1f)
                                change.consume()
                                owned = true
                            }
                            null -> Unit
                        }
                    }
                    try {
                        val released = horizontalDrag(down.id) {
                            progress = (progress + (it.position.x - it.previousPosition.x) / travel).coerceIn(0f, 1f)
                            it.consume()
                        }
                        changeOpen(if (released) progress >= .5f else currentOpen)
                    } finally { dragging = false }
                }
            }) {
            if (progress > 0f) Box(Modifier.width(revealWidth).fillMaxHeight().graphicsLayer {
                translationX = if (layered) -(1f - progress) * travel * .1f else 0f
            }) { functionPage() }
            Box(Modifier.fillMaxSize().graphicsLayer {
                translationX = progress * travel
                scaleX = if (layered) 1f - .035f * progress else 1f
                scaleY = scaleX
                shape = RoundedCornerShape((22f * progress).dp)
                clip = progress > 0f
                shadowElevation = if (layered) 12.dp.toPx() * progress else 0f
            }.background(colors.paper)) {
                Box(if (open) Modifier.fillMaxSize().clearAndSetSemantics {} else Modifier.fillMaxSize()) { content() }
                if (open || dragging) Box(Modifier.fillMaxSize().clickable { changeOpen(false) })
            }
        }
    }
}
