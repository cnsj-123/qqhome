package com.qq.closie.ui.lifeos.components

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qq.closie.R
import com.qq.closie.life.ui.theme.BundledText as Text
import com.qq.closie.life.ui.theme.LifeType
import com.qq.closie.ui.lifeos.media.LifeMediaUi
import com.qq.closie.ui.lifeos.theme.LocalLifeOsColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.abs

/** In-place viewer. The underlying Home stays mounted while the selected photo returns to its source. */
@Composable
fun LifePhotoViewer(
    photos: List<LifeMediaUi>,
    initialPhotoId: String,
    onDismiss: () -> Unit,
    onRemove: ((LifeMediaUi) -> Unit)? = null,
    origin: LifeSurfaceOrigin? = null,
    aspects: Map<String, Float> = emptyMap(),
    dateFor: (LifeMediaUi) -> LocalDate? = { null },
    today: LocalDate = LocalDate.now()
) {
    if (photos.isEmpty()) return
    var selectedId by rememberSaveable(initialPhotoId) { mutableStateOf(initialPhotoId) }
    val current = photos.indexOfFirst { it.id == selectedId }.coerceAtLeast(0)
    val photo = photos[current]
    val colors = LocalLifeOsColors.current
    val density = LocalDensity.current
    val orientation = LocalConfiguration.current.orientation
    val reduced = reducedLifeMotion()
    val scope = rememberCoroutineScope()
    var reveal by remember { mutableFloatStateOf(if (reduced) 1f else 0f) }
    var motion by remember { mutableStateOf<Job?>(null) }
    var closing by remember { mutableStateOf(false) }
    var removePhoto by remember { mutableStateOf<LifeMediaUi?>(null) }
    var windowOffset by remember { mutableStateOf(Offset.Zero) }
    var cardSize by remember { mutableStateOf(IntSize.Zero) }
    var dragOffset by remember { mutableStateOf(Offset.Zero) }
    val dismiss by rememberUpdatedState(onDismiss)

    fun animateTo(target: Float, done: () -> Unit = {}) {
        motion?.cancel()
        motion = scope.launch {
            if (reduced) reveal = target else animate(
                reveal, target, animationSpec = spring(dampingRatio = .82f, stiffness = 380f)
            ) { value, _ -> reveal = value.coerceIn(0f, 1f) }
            done()
        }
    }

    fun close() {
        if (closing) return
        closing = true
        animateTo(0f, dismiss)
    }

    fun step(direction: Int): Boolean {
        val next = photos.getOrNull(current + direction) ?: return false
        selectedId = next.id
        return true
    }

    LaunchedEffect(reduced) { if (!closing) animateTo(1f) }
    PredictiveBackHandler(enabled = removePhoto == null) { progress ->
        if (!closing) {
            motion?.cancel()
            val startingReveal = reveal
            try {
                progress.collect { event -> if (!reduced) reveal = startingReveal * (1f - event.progress) }
                close()
            } catch (cancelled: CancellationException) {
                if (!closing) animateTo(1f)
                throw cancelled
            }
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize().onGloballyPositioned { windowOffset = it.positionInWindow() }) {
        val viewport = Rect(0f, 0f, constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat())
        val source = origin?.takeIf { photo.id == initialPhotoId }?.let {
            it.copy(rect = it.rect.translate(-windowOffset))
        }?.takeIf { it.usable(orientation, viewport) }
        val panelWidth = minOf(370.dp, (maxWidth - 32.dp).coerceAtLeast(1.dp))
        val maxPanelHeight = minOf(700.dp, (maxHeight - 36.dp).coerceAtLeast(1.dp))
        val roomy = maxPanelHeight >= 450.dp
        val gutter = if (panelWidth >= 320.dp) 30.dp else 16.dp
        val maxImageWidth = minOf(303.dp, (panelWidth - gutter * 2).coerceAtLeast(1.dp))
        val maxImageHeight = minOf(478.dp, (maxPanelHeight - if (roomy) 155.dp else 90.dp).coerceAtLeast(1.dp))
        val ratio = (aspects[photo.id] ?: photo.aspectRatio ?: 1f).takeIf { it > 0f && it.isFinite() } ?: 1f
        val photoWidth = minOf(maxImageWidth, maxImageHeight * ratio).coerceAtLeast(1.dp)
        val photoHeight = minOf(maxImageHeight, maxImageWidth / ratio).coerceAtLeast(1.dp)
        val shade = colors.ink.copy(alpha = .12f * reveal)
        val panelShape = RoundedCornerShape(if (roomy) 69.dp else 40.dp)
        val panelFill = Brush.verticalGradient(
            listOf(colors.card.copy(alpha = .98f), colors.paperSecondary.copy(alpha = .88f), colors.card.copy(alpha = .96f))
        )
        val padding = if (roomy) 60.dp else 24.dp
        val distanceThreshold = with(density) { 48.dp.toPx() }
        val speedThreshold = with(density) { 720.dp.toPx() }
        val currentStep by rememberUpdatedState(::step)
        val currentClose by rememberUpdatedState(::close)
        val currentRemove by rememberUpdatedState(onRemove?.let { { removePhoto = photo } })

        Box(Modifier.fillMaxSize().background(shade).testTag("life-origin-scrim").clickable(
            interactionSource = remember { MutableInteractionSource() }, indication = null, onClickLabel = "关闭照片浮层", onClick = ::close
        ))
        val settleSize = if (reduced) Modifier else Modifier.animateContentSize(spring(dampingRatio = .9f, stiffness = 420f))
        Column(
            Modifier.align(Alignment.Center).width(panelWidth).heightIn(max = maxPanelHeight)
                .onSizeChanged { cardSize = it }
                .graphicsLayer {
                    val target = Rect(
                        viewport.center.x - cardSize.width / 2f, viewport.center.y - cardSize.height / 2f,
                        viewport.center.x + cardSize.width / 2f, viewport.center.y + cardSize.height / 2f
                    )
                    val bounds = interpolateLifeSurface(source?.rect ?: target, target, reveal)
                    translationX = bounds.center.x - target.center.x + if (reduced) 0f else dragOffset.x * .15f
                    translationY = bounds.center.y - target.center.y + if (reduced) 0f else dragOffset.y * .56f
                    scaleX = if (target.width > 0f) bounds.width / target.width else 1f
                    scaleY = if (target.height > 0f) bounds.height / target.height else 1f
                    rotationZ = (source?.rotation ?: 0f) * (1f - reveal) + if (reduced) 0f else (dragOffset.x / 150f).coerceIn(-1.5f, 1.5f)
                    alpha = if (source == null) reveal else 1f
                }
                .shadow(24.dp, panelShape, ambientColor = colors.ink.copy(alpha = .14f), spotColor = colors.ink.copy(alpha = .14f))
                .shadow(8.dp, panelShape, ambientColor = colors.ink.copy(alpha = .16f), spotColor = colors.ink.copy(alpha = .16f))
                .clip(panelShape).background(panelFill).border(1.dp, colors.card.copy(alpha = .9f), panelShape)
                .testTag("life-origin-paper")
                .semantics {
                    stateDescription = "第 " + (current + 1) + " 张，共 " + photos.size + " 张"
                    customActions = buildList {
                        add(CustomAccessibilityAction("关闭照片浮层") { close(); true })
                        if (current > 0) add(CustomAccessibilityAction("上一张照片") { step(-1) })
                        if (current < photos.lastIndex) add(CustomAccessibilityAction("下一张照片") { step(1) })
                        if (onRemove != null) add(CustomAccessibilityAction("从首页移除照片") { removePhoto = photo; true })
                    }
                }
                .pointerInput(distanceThreshold, speedThreshold) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val velocity = VelocityTracker()
                        velocity.addPosition(down.uptimeMillis, down.position)
                        var direction: LifeSwipeIntent.Direction? = null
                        val longPress = if (currentRemove != null) scope.launch {
                            kotlinx.coroutines.delay(viewConfiguration.longPressTimeoutMillis)
                            currentRemove?.invoke()
                        } else null
                        try {
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (change.isConsumed || event.changes.any { it.id != down.id && it.pressed }) break
                                val delta = change.position - down.position
                                velocity.addPosition(change.uptimeMillis, change.position)
                                if (direction == null) direction = LifeSwipeIntent.resolve(delta.x, delta.y, viewConfiguration.touchSlop)
                                if (direction != null) {
                                    longPress?.cancel()
                                    change.consume()
                                    dragOffset = if (direction == LifeSwipeIntent.Direction.VERTICAL) Offset(0f, delta.y.coerceAtLeast(0f))
                                    else Offset(delta.x, 0f)
                                }
                                if (!change.pressed) {
                                    val fling = velocity.calculateVelocity()
                                    when (direction) {
                                        LifeSwipeIntent.Direction.VERTICAL -> if (delta.y > distanceThreshold ||
                                            (delta.y > viewConfiguration.touchSlop && fling.y > speedThreshold)) currentClose()
                                        LifeSwipeIntent.Direction.LEFT, LifeSwipeIntent.Direction.RIGHT ->
                                            if (abs(delta.x) > distanceThreshold ||
                                                (abs(delta.x) > viewConfiguration.touchSlop && abs(fling.x) > speedThreshold)) {
                                                currentStep(if (delta.x < 0f) 1 else -1)
                                            }
                                        null -> Unit
                                    }
                                    break
                                }
                            }
                        } finally {
                            longPress?.cancel()
                            dragOffset = Offset.Zero
                        }
                    }
                }
                .then(settleSize)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = gutter, vertical = padding),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            LifeMediaImage(photo, Modifier.size(photoWidth, photoHeight), ContentScale.Fit)
            Column(Modifier.fillMaxWidth().padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                dateFor(photo)?.let { date ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(date.format(DateTimeFormatter.ofPattern("M月d日")), style = LifeType.JournalStamp, color = colors.muted)
                        if (date == today) Text("今天", style = LifeType.JournalMeta, color = colors.muted)
                    }
                }
                photo.caption?.takeIf(String::isNotBlank)?.let { note ->
                    Text(note, style = LifeType.journalFor(note).copy(fontSize = 14.sp), color = colors.accentDeep, maxLines = 3)
                }
                if (photo.motionAvailable) Text("此照片含动态组件，当前无法播放", style = LifeType.JournalMeta, color = colors.muted)
            }
            PhotoInkIndicator(current, photos.size, Modifier.padding(top = 18.dp))
        }
    }
    removePhoto?.let { selection ->
        AlertDialog(
            onDismissRequest = { removePhoto = null },
            title = { Text("从首页移除照片") },
            text = { Text("移除首页引用，原图会保留。") },
            dismissButton = { TextButton({ removePhoto = null }) { Text("取消") } },
            confirmButton = { TextButton({ removePhoto = null; onRemove?.invoke(selection) }) { Text("移除") } }
        )
    }
}

@Composable
private fun PhotoInkIndicator(current: Int, count: Int, modifier: Modifier) {
    val tint = ColorFilter.tint(LocalLifeOsColors.current.accentDeep)
    val dots = listOf(R.drawable.lifeos_photo_dot_one, R.drawable.lifeos_photo_dot_two, R.drawable.lifeos_photo_dot_three)
    val start = (current - 3).coerceIn(0, (count - 7).coerceAtLeast(0))
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(minOf(count, 7)) { offset ->
            val index = start + offset
            val selected = current == index
            Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) {
                Image(painterResource(if (selected) R.drawable.lifeos_home_paw else dots[index % dots.size]),
                    null, Modifier.offset(y = if (selected) 0.dp else (index % 3 - 1).dp)
                        .size(if (selected) 14.dp else (3 + index % 3).dp), colorFilter = tint)
            }
        }
    }
}
