package com.xiaoming.closie.ui.lifeos.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
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
import com.xiaoming.closie.ui.lifeos.media.LifeMediaUi
import com.xiaoming.closie.ui.lifeos.theme.*
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
    var index by rememberSaveable { mutableIntStateOf(0) }
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
        BoxWithConstraints(Modifier.fillMaxWidth().height(272.dp).padding(horizontal = 8.dp, vertical = 12.dp)) {
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
            (minOf(3, photos.size) - 1 downTo 0).forEach { depth ->
                val photo = photos[PhotoStackInteraction.nextIndex(current, depth, photos.size)]
                val top = depth == 0
                Surface(
                    Modifier.fillMaxWidth().height(238.dp).zIndex((3 - depth).toFloat())
                        .graphicsLayer {
                            val progress = (abs(offset) / extent.coerceAtLeast(1f)).coerceIn(0f, 1f)
                            translationX = if (top && !vertical) offset else if (depth == 1) lift * .75f else -lift * .4f
                            translationY = if (top && vertical) offset else depth * lift * (1 - progress * .5f)
                            rotationZ = if (reduced) 0f else if (top) -1.4f + offset / 45f else if (depth == 1) 3.2f else -3f
                            scaleX = 1f - depth * .03f + if (top && dragging && !reduced) .012f else 0f
                            scaleY = scaleX
                            shadowElevation = if (reduced) 0f else (if (top && dragging) 16.dp else 6.dp).toPx()
                        }.then(if (top) dragModifier.clickable(role = Role.Button, onClickLabel = "查看照片", onClick = { if (!busy) onPhotoClick(photo) }) else Modifier.clearAndSetSemantics {}),
                    shape = RoundedCornerShape(5.dp), color = colors.card,
                    border = BorderStroke(1.dp, colors.line)
                ) {
                    Column(Modifier.padding(9.dp)) {
                        LifeMediaImage(photo, Modifier.weight(1f).fillMaxWidth())
                        Text(photo.caption ?: photo.title ?: "生活的一页", Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 4.dp),
                            style = LifeText.caption, color = colors.inkSecondary, maxLines = 2)
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { settle(-1, 0f, 0f) }, enabled = photos.size > 1 && !busy) {
                Icon(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, "上一张照片")
            }
            Text("${current + 1} / ${photos.size}", style = LifeText.caption, color = colors.muted)
            IconButton(onClick = { settle(1, 0f, 0f) }, enabled = photos.size > 1 && !busy) {
                Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, "下一张照片")
            }
            TextButton(onClick = { vertical = !vertical }, enabled = !busy) { Text(if (vertical) "左右翻" else "上下翻", style = LifeText.caption) }
        }
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
