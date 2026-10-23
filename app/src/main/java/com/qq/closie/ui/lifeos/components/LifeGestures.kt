package com.qq.closie.ui.lifeos.components

import android.animation.ValueAnimator
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.abs

@Composable
fun reducedLifeMotion(): Boolean {
    val scope = rememberCoroutineScope()
    return scope.coroutineContext[MotionDurationScale]?.scaleFactor == 0f || !ValueAnimator.areAnimatorsEnabled()
}

/** Content swipes, excluding both Android system-back edges. */
@Composable
fun Modifier.lifeHorizontalSwipe(enabled: Boolean = true, onLeft: (() -> Unit)? = null, onRight: (() -> Unit)? = null): Modifier {
    val left = rememberUpdatedState(onLeft)
    val right = rememberUpdatedState(onRight)
    val threshold = with(LocalDensity.current) { 56.dp.toPx() }
    val edge = systemBackEdgePx()
    return if (!enabled) this else pointerInput(threshold, edge) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            // Check the original down position before consuming even the touch-slop event.
            if (down.position.x <= edge || down.position.x >= size.width - edge) return@awaitEachGesture
            // Main pass visits children first: a photo card may own its drag before this container.
            // Do not consume anything until a horizontal intent is clear; vertical scrolling wins.
            var distance = 0f
            var drag: androidx.compose.ui.input.pointer.PointerInputChange? = null
            while (drag == null) {
                val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                    ?: return@awaitEachGesture
                if (!change.pressed || change.isConsumed) return@awaitEachGesture
                val delta = change.position - down.position
                when (LifeSwipeIntent.resolve(delta.x, delta.y, viewConfiguration.touchSlop)) {
                    LifeSwipeIntent.Direction.VERTICAL -> return@awaitEachGesture
                    LifeSwipeIntent.Direction.LEFT, LifeSwipeIntent.Direction.RIGHT -> {
                        if ((delta.x < 0 && left.value == null) || (delta.x > 0 && right.value == null)) return@awaitEachGesture
                        distance = delta.x
                        change.consume()
                        drag = change
                    }
                    null -> Unit
                }
            }
            val released = horizontalDrag(drag.id) { change ->
                distance += change.position.x - change.previousPosition.x
                change.consume()
            }
            if (released && distance < -threshold) left.value?.invoke()
            if (released && distance > threshold) right.value?.invoke()
        }
    }
}


/** Small, testable arbitration rule shared by content swipes; no Android state. */
object LifeSwipeIntent {
    const val systemBackEdgeDp = 24
    enum class Direction { LEFT, RIGHT, VERTICAL }
    fun resolve(dx: Float, dy: Float, slop: Float): Direction? = when {
        abs(dy) > slop && abs(dy) >= abs(dx) -> Direction.VERTICAL
        abs(dx) > slop && abs(dx) > abs(dy) -> if (dx < 0) Direction.LEFT else Direction.RIGHT
        else -> null
    }
}

/** Shell-global entry, limited to the bottom content strip outside system-back edges.
 * Horizontal intent is left to Function Pages/pagers; leased editors disable this owner. */
@Composable
fun Modifier.lifeCompanionSwipe(enabled: Boolean, onProgress: (Float) -> Unit = {}, onOpen: () -> Unit): Modifier {
    val updateProgress = rememberUpdatedState(onProgress)
    val open = rememberUpdatedState(onOpen)
    val density = LocalDensity.current
    val zone = with(density) { 88.dp.toPx() }
    val threshold = with(density) { 56.dp.toPx() }
    val edge = systemBackEdgePx()
    return if (!enabled) this else pointerInput(zone, threshold, edge) {
        awaitEachGesture {
            // Child scrolling, photo dragging and text selection retain priority.
            val pass = androidx.compose.ui.input.pointer.PointerEventPass.Main
            val down = awaitFirstDown(requireUnconsumed = false, pass = pass)
            if (down.position.y < size.height - zone || down.position.x <= edge ||
                down.position.x >= size.width - edge) return@awaitEachGesture
            var committed=false
            try {
            var owned = false
            while (true) {
                val event = awaitPointerEvent(pass)
                val change = event.changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
                if (change.isConsumed || event.changes.any { it.id != down.id && it.pressed }) return@awaitEachGesture
                val delta = change.position - down.position
                if (!owned) {
                    if (!change.pressed) return@awaitEachGesture
                    when (LifeSwipeIntent.resolve(delta.x, delta.y, viewConfiguration.touchSlop)) {
                        LifeSwipeIntent.Direction.VERTICAL -> if (delta.y < 0f) owned = true else return@awaitEachGesture
                        null -> continue
                        else -> return@awaitEachGesture
                    }
                }
                updateProgress.value((-delta.y / size.height.coerceAtLeast(1)).coerceIn(0f, 1f))
                change.consume()
                if (!change.pressed) {
                    if (-delta.y >= threshold) {committed=true;open.value()}
                    break
                }
            }
            } finally {if(!committed)updateProgress.value(0f)}
        }
    }
}

/** Respect OEM/system gesture widths as well as the conservative app corridor. */
@Composable
internal fun systemBackEdgePx(): Float {
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    return maxOf(with(density) { LifeSwipeIntent.systemBackEdgeDp.dp.toPx() },
        WindowInsets.systemGestures.getLeft(density, direction).toFloat(),
        WindowInsets.systemGestures.getRight(density, direction).toFloat())
}
