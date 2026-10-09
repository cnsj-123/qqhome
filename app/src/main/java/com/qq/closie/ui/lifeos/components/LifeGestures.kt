package com.qq.closie.ui.lifeos.components

import android.animation.ValueAnimator
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

@Composable
fun reducedLifeMotion(): Boolean {
    val scope = rememberCoroutineScope()
    return scope.coroutineContext[MotionDurationScale]?.scaleFactor == 0f || !ValueAnimator.areAnimatorsEnabled()
}

/** Content swipes, excluding both Android system-back edges. */
@Composable
fun Modifier.lifeHorizontalSwipe(enabled: Boolean = true, onLeft: () -> Unit = {}, onRight: () -> Unit = {}): Modifier {
    val left = rememberUpdatedState(onLeft)
    val right = rememberUpdatedState(onRight)
    val threshold = with(LocalDensity.current) { 56.dp.toPx() }
    val edge = with(LocalDensity.current) { 24.dp.toPx() }
    return if (!enabled) this else pointerInput(threshold, edge) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            // Check the original down position before consuming even the touch-slop event.
            if (down.position.x <= edge || down.position.x >= size.width - edge) return@awaitEachGesture
            var distance = 0f
            val drag = awaitHorizontalTouchSlopOrCancellation(down.id) { change, overSlop ->
                change.consume()
                distance = overSlop
            } ?: return@awaitEachGesture
            val released = horizontalDrag(drag.id) { change ->
                distance += change.position.x - change.previousPosition.x
                change.consume()
            }
            if (released && distance < -threshold) left.value()
            if (released && distance > threshold) right.value()
        }
    }
}
