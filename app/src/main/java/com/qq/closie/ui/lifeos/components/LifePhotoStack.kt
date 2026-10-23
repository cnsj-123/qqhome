package com.qq.closie.ui.lifeos.components

import com.qq.closie.life.ui.theme.BundledText as Text

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.qq.closie.ui.lifeos.media.LifeMediaUi
import com.qq.closie.ui.lifeos.theme.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs

/** UI interaction only. No storage knowledge and no production sample images. */
@Composable
fun LifePhotoStack(photos: List<LifeMediaUi>, onPhotoClick: (LifeMediaUi) -> Unit, modifier: Modifier = Modifier) {
    if (photos.isEmpty()) { LifeEmptyState("今天还没有照片。", modifier); return }
    val colors = LocalLifeOsColors.current
    val reduced = reducedLifeMotion()
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val threshold = with(density) { 48.dp.toPx() }
    val lift = with(density) { 8.dp.toPx() }
    var index by rememberSaveable(photos.map { it.id }) { mutableIntStateOf(0) }
    var vertical by rememberSaveable { mutableStateOf(false) }
    var drag by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val settling = remember { Animatable(0f) }
    var animationJob by remember { mutableStateOf<Job?>(null) }
    val current = PhotoStackInteraction.nextIndex(index, 0, photos.size)

    fun settle(step: Int, distance: Float, extent: Float) {
        animationJob?.cancel()
        dragging = false
        animationJob = scope.launch {
            busy = true
            try {
                settling.snapTo(distance)
                if (!reduced) settling.animateTo(
                    if (step == 0) 0f else (if (step > 0) -1 else 1) * (extent + threshold),
                    spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)
                )
                if (step != 0) index = PhotoStackInteraction.nextIndex(current, step, photos.size)
                settling.snapTo(0f)
                drag = 0f
            } finally { busy = false }
        }
    }

    Column(modifier) {
        BoxWithConstraints(Modifier.fillMaxWidth().aspectRatio(1f / .84f).clipToBounds()) {
            val extent = with(density) { (if (vertical) maxHeight else maxWidth).toPx() }
            val offset = if (dragging) drag else settling.value
            val dragModifier = Modifier.pointerInput(photos.map { it.id }, vertical, reduced, busy) {
                if (busy || photos.size < 2) return@pointerInput
                val start: (androidx.compose.ui.geometry.Offset) -> Unit = {
                    animationJob?.cancel(); drag = 0f; dragging = true
                }
                val end: () -> Unit = { settle(PhotoStackInteraction.releaseStep(drag, threshold, photos.size), drag, extent) }
                val cancel: () -> Unit = { settle(0, drag, extent) }
                if (vertical) detectVerticalDragGestures(start, end, cancel) { change, delta -> change.consume(); drag += delta }
                else detectHorizontalDragGestures(start, end, cancel) { change, delta -> change.consume(); drag += delta }
            }
            (minOf(4, photos.size) - 1 downTo 0).forEach { depth ->
                val photo = photos[PhotoStackInteraction.nextIndex(current, depth, photos.size)]
                val top = depth == 0
                val pose = PhotoStackLayout.pose(photo.id, depth, maxWidth.value, maxHeight.value)
                Surface(
                    Modifier.width(pose.width.dp).height(pose.height.dp)
                        .offset((pose.centerX - pose.width / 2).dp, (pose.centerY - pose.height / 2).dp)
                        .zIndex((4 - depth).toFloat())
                        .graphicsLayer {
                            val progress = (abs(offset) / extent.coerceAtLeast(1f)).coerceIn(0f, 1f)
                            translationX = if (top && !vertical) offset else 0f
                            translationY = if (top && vertical) offset else if (top && dragging && !reduced) -lift else 0f
                            rotationZ = pose.rotation + if (top && !reduced) (offset / extent.coerceAtLeast(1f)).coerceIn(-1f, 1f) * 4f else 0f
                            scaleX = 1f + if (top && dragging && !reduced) .012f else if (depth == 1 && !reduced) progress * .015f else 0f
                            scaleY = scaleX
                            shadowElevation = if (reduced) 0f else (if (top && dragging) 16.dp else 6.dp).toPx()
                        }.then(if (top) dragModifier.clickable(role = Role.Button, onClickLabel = "查看照片", onClick = { if (!busy) onPhotoClick(photo) }) else Modifier.clearAndSetSemantics {}),
                    shape = RoundedCornerShape(1.dp), color = Color(0xFFFFFDFA)
                ) {
                    Column(Modifier.padding(7.dp)) {
                        LifeMediaImage(photo, Modifier.weight(1f).fillMaxWidth())

                    }
                }
            }
        }
        val selected = photos[current]
        Text(selected.caption ?: selected.title ?: "", Modifier.fillMaxWidth().padding(top = 8.dp)
            .semantics {
                customActions = listOf(
                    CustomAccessibilityAction("上一张照片") { if (photos.size > 1 && !busy) settle(-1, 0f, 0f); true },
                    CustomAccessibilityAction("下一张照片") { if (photos.size > 1 && !busy) settle(1, 0f, 0f); true }
                )
            }, style = LifeText.caption, color = colors.inkSecondary, maxLines = 2)
    }
}

@Preview(name = "Photo Stack · PreviewOnly fixture", showBackground = true)
@Composable
private fun LifePhotoStackPreview() {
    LifeOsTheme {
        LifePhotoStack(listOf(LifeMediaUi("preview-only-1", null, caption = "PreviewOnly · 第一张"),
            LifeMediaUi("preview-only-2", null, caption = "PreviewOnly · 第二张"),
            LifeMediaUi("preview-only-3", null, caption = "PreviewOnly · 第三张")), {})
    }
}
