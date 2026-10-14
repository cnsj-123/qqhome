package com.qq.closie.ui.lifeos.components

import android.animation.ValueAnimator
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
    val edge = with(LocalDensity.current) { 24.dp.toPx() }
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
    enum class Direction { LEFT, RIGHT, VERTICAL }
    fun resolve(dx: Float, dy: Float, slop: Float): Direction? = when {
        abs(dy) > slop && abs(dy) >= abs(dx) -> Direction.VERTICAL
        abs(dx) > slop && abs(dx) > abs(dy) -> if (dx < 0) Direction.LEFT else Direction.RIGHT
        else -> null
    }
}
