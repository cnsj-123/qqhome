package com.qq.closie.ui.lifeos.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.qq.closie.R
import com.qq.closie.life.ui.theme.BundledText as Text
import com.qq.closie.life.ui.theme.LifeType
import com.qq.closie.ui.lifeos.media.LifeMediaUi
import com.qq.closie.ui.lifeos.theme.LocalLifeOsColors
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/** Slot geometry changes together, so a photo keeps its identity and pose throughout a swipe. */
@Composable
fun LifePhotoStack(
    photos: List<LifeMediaUi>,
    onPhotoClick: (LifeMediaUi) -> Unit,
    modifier: Modifier = Modifier,
    hiddenPhotoId: String? = null,
    onPhotoOrigin: ((LifeMediaUi, LifeSurfaceOrigin) -> Unit)? = null,
    interactive: Boolean = true,
    aspects: Map<String, Float> = emptyMap()
) {
    if (photos.isEmpty()) return
    val colors = LocalLifeOsColors.current
    val orientation = LocalConfiguration.current.orientation
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val reduced = reducedLifeMotion()
    val photoIds = remember(photos) { photos.map { it.id } }
    var selectedId by rememberSaveable { mutableStateOf(photos.first().id) }
    val index = photoIds.indexOf(selectedId).coerceAtLeast(0)
    var fraction by remember(photoIds) { mutableFloatStateOf(0f) }
    var settlement by remember(photoIds) { mutableStateOf<Job?>(null) }
    DisposableEffect(photoIds) { onDispose { settlement?.cancel() } }
    val origins = remember(photoIds) { mutableMapOf<String, LifeSurfaceOrigin>() }
    val caption = photos[index].caption?.takeIf(String::isNotBlank)

    Column(modifier) {
        BoxWithConstraints(Modifier.fillMaxWidth().aspectRatio(1.41f).clipToBounds()) {
            val width = maxWidth.value
            val height = maxHeight.value
            if (width <= 0f || height <= 0f) return@BoxWithConstraints
            val travel = with(density) { (maxWidth * .74f).toPx() }.coerceAtLeast(1f)
            val swipeThreshold = with(density) { 48.dp.toPx() }
            val flingThreshold = with(density) { 640.dp.toPx() }

            fun settle(step: Int, velocity: Float = 0f) {
                settlement?.cancel()
                settlement = scope.launch {
                    if (!reduced) animate(
                        fraction, -step.toFloat(), velocity / travel,
                        spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 420f)
                    ) { value, _ -> fraction = value.coerceIn(-1f, 1f) }
                    selectedId = photos[PhotoStackInteraction.nextIndex(index, step, photos.size)].id
                    fraction = 0f
                }
            }

            val step = if (fraction > 0f) -1 else 1
            val progress = abs(fraction)
            val next = PhotoStackInteraction.nextIndex(index, step, photos.size)
            // The whole collection remains navigable. Cards deep enough to be occluded
            // have no decoded image until they move toward the visible layers.
            val layerCount = photos.size.coerceAtMost(12)
            val activeIndices = remember(index, next, photos.size, layerCount) {
                buildSet {
                    repeat(layerCount) { depth ->
                        add(PhotoStackInteraction.nextIndex(index, depth, photos.size))
                        add(PhotoStackInteraction.nextIndex(next, depth, photos.size))
                    }
                }
            }
            for (photoIndex in activeIndices) {
                val photo = photos[photoIndex]
                key(photo.id) {
                    val oldDepth = Math.floorMod(photoIndex - index, photos.size)
                    val newDepth = Math.floorMod(photoIndex - next, photos.size)
                    val ratio = aspects[photo.id] ?: photo.aspectRatio
                    val from = PhotoStackLayout.pose(photo.id, oldDepth, width, height, ratio)
                    val to = PhotoStackLayout.pose(photo.id, newDepth, width, height, ratio)
                    val pose = PhotoStackLayout.interpolate(from, to, progress)
                    val wraps = abs(oldDepth - newDepth) > 1
                    val excursion = if (wraps && !reduced) {
                        val direction = if (step > 0) -1 else 1
                        width * .47f * direction * sin(PI * progress).toFloat()
                    } else 0f
                    val depth = if (progress < .50f) oldDepth else newDepth
                    val top = oldDepth == 0
                    val showTape = if (progress < .50f) top else newDepth == 0
                    val changeAlpha = when {
                        oldDepth >= layerCount -> progress
                        newDepth >= layerCount -> 1f - progress
                        else -> 1f
                    }
                    val paperSize = minOf(7.dp, pose.width.dp / 5, pose.height.dp / 5)
                    val input = if (top && interactive) {
                        val drag = rememberDraggableState { delta ->
                            fraction = (fraction + delta / travel).coerceIn(-1f, 1f)
                        }
                        Modifier.draggable(
                            state = drag,
                            orientation = Orientation.Horizontal,
                            enabled = photos.size > 1,
                            startDragImmediately = settlement?.isActive == true,
                            onDragStarted = { settlement?.cancel() },
                            onDragStopped = { velocity ->
                                val distance = fraction * travel
                                val release = if (abs(velocity) >= flingThreshold && abs(distance) >= swipeThreshold * .33f)
                                    if (velocity < 0f) 1 else -1
                                else PhotoStackInteraction.releaseStep(distance, swipeThreshold, photos.size)
                                settle(release, velocity)
                            }
                        ).semantics {
                            if (photos.size > 1) customActions = listOf(
                                CustomAccessibilityAction("上一张照片") { settle(-1); true },
                                CustomAccessibilityAction("下一张照片") { settle(1); true }
                            )
                        }.clickable(role = Role.Button, onClickLabel = "查看照片") {
                            if (settlement?.isActive != true) {
                                val origin = origins[photo.id]
                                if (origin != null && onPhotoOrigin != null) onPhotoOrigin(photo, origin)
                                else onPhotoClick(photo)
                            }
                        }
                    } else Modifier.clearAndSetSemantics {}

                    Box(
                        Modifier.size(pose.width.dp, pose.height.dp)
                            .offset((pose.centerX - pose.width / 2 + excursion).dp, (pose.centerY - pose.height / 2).dp)
                            .zIndex(-depth.toFloat())
                            .lifeSurfaceOrigin(orientation, pose.rotation) { origins[photo.id] = it }
                            .graphicsLayer {
                                rotationZ = pose.rotation
                                alpha = if (photo.id == hiddenPhotoId) 0f else changeAlpha
                            }.then(input)
                    ) {
                        Surface(
                            Modifier.fillMaxSize(), shape = RoundedCornerShape(1.dp),
                            color = if (colors.isDark) colors.card else Color(0xFFFFFDFA),
                            shadowElevation = 5.dp
                        ) {
                            LifeMediaImage(photo, Modifier.fillMaxSize().padding(paperSize), ContentScale.Fit)
                        }
                        if (showTape) Image(
                            painterResource(R.drawable.lifeos_photo_tape), null,
                            Modifier.align(Alignment.TopCenter).offset(y = (-10).dp).size(41.dp, 22.dp)
                        )
                    }
                }
            }
        }
        if (caption != null) Text(
            caption, Modifier.fillMaxWidth().padding(top = 5.dp, end = 18.dp),
            style = LifeType.journalFor(caption).copy(fontSize = androidx.compose.ui.unit.TextUnit(14f, androidx.compose.ui.unit.TextUnitType.Sp)),
            color = colors.accentDeep, textAlign = TextAlign.End, maxLines = 2
        )
    }
}
