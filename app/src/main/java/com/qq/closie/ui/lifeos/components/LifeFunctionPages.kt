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
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.input.pointer.util.VelocityTracker
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
    val revealed by remember { derivedStateOf { progress > 0f } }
    val currentOpen by rememberUpdatedState(open)
    val changeOpen by rememberUpdatedState(onOpenChange)
    val edge = with(LocalDensity.current) { 24.dp.toPx() }
    LaunchedEffect(open, dragging, reducedMotion) {
        if (!dragging) {
            if (reducedMotion) progress = if (open) 1f else 0f
            else animate(progress, if (open) 1f else 0f, animationSpec = tween(230)) { value, _ -> progress = value }
        }
    }
    BackHandler(open || revealed) { onOpenChange(false) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val revealWidth = maxWidth * .84f
        val travel = with(LocalDensity.current) { revealWidth.toPx() }
        val mode = if (reducedMotion) FunctionPageStyle.FLAT else style
        Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(colors.paperSecondary, colors.paperTertiary, colors.warmSurface)))
            .pointerInput(enabled, travel, edge) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (down.position.x <= edge || down.position.x >= size.width - edge) return@awaitEachGesture
                    var owned = false
                    val tracker = VelocityTracker()
                    tracker.addPosition(down.uptimeMillis, down.position)
                    val startedOpen = currentOpen
                    while (!owned) {
                        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
                        if (!change.pressed || change.isConsumed) return@awaitEachGesture
                        val delta = change.position - down.position
                        tracker.addPosition(change.uptimeMillis, change.position)
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
                            tracker.addPosition(it.uptimeMillis, it.position)
                            progress = (progress + (it.position.x - it.previousPosition.x) / travel).coerceIn(0f, 1f)
                            it.consume()
                        }
                        val velocity = tracker.calculateVelocity().x
                        val fling = 650.dp.toPx()
                        val settled = when {
                            velocity > fling -> true
                            velocity < -fling -> false
                            startedOpen -> progress > .78f
                            else -> progress >= .22f
                        }
                        changeOpen(if (released) settled else startedOpen)
                    } finally { dragging = false }
                }
            }) {
            if (revealed && mode != FunctionPageStyle.GLASS) Box(Modifier.width(revealWidth).fillMaxHeight().graphicsLayer {
                translationX = when (mode) {
                    FunctionPageStyle.FLAT -> -(1f - progress) * travel
                    FunctionPageStyle.DECK -> -(1f - progress) * travel * .24f
                    else -> -(1f - progress) * travel * .08f
                }
                rotationY = if (mode == FunctionPageStyle.DECK) -14f * (1f - progress) else 0f
                transformOrigin = TransformOrigin(0f, .5f)
                cameraDistance = 18.dp.toPx()
            }) { functionPage() }
            Box(Modifier.fillMaxSize().graphicsLayer {
                translationX = progress * travel * if (mode == FunctionPageStyle.GLASS) .18f else 1f
                scaleX = when (mode) {
                    FunctionPageStyle.LAYERED -> 1f - .085f * progress
                    FunctionPageStyle.DECK -> 1f - .10f * progress
                    else -> 1f
                }
                scaleY = scaleX
                transformOrigin = TransformOrigin(0f, .5f)
                rotationY = if (mode == FunctionPageStyle.DECK) -24f * progress else 0f
                rotationZ = if (mode == FunctionPageStyle.DECK) -2f * progress else 0f
                cameraDistance = 18.dp.toPx()
                shape = RoundedCornerShape((22f * progress).dp)
                clip = progress > 0f
                shadowElevation = if (mode != FunctionPageStyle.FLAT) 24.dp.toPx() * progress else 0f
            }.background(colors.paper)) {
                Box(if (open) Modifier.fillMaxSize().clearAndSetSemantics {} else Modifier.fillMaxSize()) { content() }
                if (open || dragging) Box(Modifier.fillMaxSize().clickable { changeOpen(false) })
            }
            if (revealed && mode == FunctionPageStyle.GLASS) {
                Box(Modifier.fillMaxSize().graphicsLayer { alpha = progress }.background(colors.shade.copy(alpha = .12f)))
                Box(Modifier.width(revealWidth).fillMaxHeight().graphicsLayer {
                    translationX = -(1f - progress) * travel
                    shape = RoundedCornerShape(topEnd = 28.dp, bottomEnd = 28.dp)
                    clip = true
                    shadowElevation = 18.dp.toPx()
                }.background(Brush.linearGradient(listOf(colors.card.copy(alpha = .78f), colors.paperSecondary.copy(alpha = .86f), colors.warmSurface.copy(alpha = .92f))))) {
                    functionPage()
                }
            }
        }
    }
}
