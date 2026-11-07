package com.qq.closie.ui.lifeos.components

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.drawscope.translate
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
import androidx.compose.ui.unit.Dp
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
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.cos

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
    today: LocalDate = LocalDate.now(),
    onEditCaption: ((LifeMediaUi) -> Unit)? = null,
    originPaperInset: Dp = 0.dp,
    backdropLayer: GraphicsLayer? = null,
    currentOrigin:((String)->LifeSurfaceOrigin?)?=null,
    onPhotoChanged:((String)->Unit)?=null
) {
    if (photos.isEmpty()) return
    var selectedId by rememberSaveable(initialPhotoId) { mutableStateOf(initialPhotoId) }
    val current = photos.indexOfFirst { it.id == selectedId }.coerceAtLeast(0)
    val photo = photos[current]
    val colors = LocalLifeOsColors.current
    val density = LocalDensity.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val lowRam = remember(context) { (context.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager).isLowRamDevice }
    val glassDrawing = rememberGraphicsLayer()
    val orientation = LocalConfiguration.current.orientation
    val reduced = reducedLifeMotion()
    val scope = rememberCoroutineScope()
    var reveal by remember { mutableFloatStateOf(0f) }
    var revealVelocity by remember { mutableFloatStateOf(0f) }
    var motion by remember { mutableStateOf<Job?>(null) }
    var closing by remember { mutableStateOf(false) }
    var removePhoto by remember { mutableStateOf<LifeMediaUi?>(null) }
    var windowOffset by remember { mutableStateOf(Offset.Zero) }
    var imageTarget by remember { mutableStateOf(Rect.Zero) }
    var cardSize by remember { mutableStateOf(IntSize.Zero) }
    var dragOffset by remember { mutableStateOf(Offset.Zero) }
    val dismiss by rememberUpdatedState(onDismiss)

    fun animateTo(target: Float, done: () -> Unit = {}) {
        motion?.cancel()
        motion = scope.launch {
            animate(
                reveal, target, initialVelocity = if (reduced) 0f else revealVelocity,
                animationSpec = if (reduced) tween(150) else spring(dampingRatio = .75f, stiffness = 380f)
            ) { value, velocity -> reveal = value.coerceIn(0f, 1f); revealVelocity = velocity }
            done()
        }
    }

    fun close() {
        if (closing) return
        closing = true
        animateTo(0f, dismiss)
    }

    fun step(direction: Int): Boolean {
        if (closing) return false
        val next = photos.getOrNull(current + direction) ?: return false
        selectedId = next.id
        onPhotoChanged?.invoke(next.id)
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
        val source = (if(currentOrigin!=null) currentOrigin(photo.id) else origin?.takeIf { photo.id == initialPhotoId })?.let {
            it.copy(rect = it.rect.translate(-windowOffset))
        }?.takeIf { it.usable(orientation, viewport) }
        val panelWidth = minOf(370.dp, (maxWidth - 32.dp).coerceAtLeast(1.dp))
        val maxPanelHeight = minOf(697.dp, (maxHeight - 36.dp).coerceAtLeast(1.dp))
        val roomy = maxPanelHeight >= 450.dp
        val gutter = if (panelWidth >= 320.dp) 32.dp else 16.dp
        val maxImageWidth = minOf(303.dp, (panelWidth - gutter * 2).coerceAtLeast(1.dp))
        val maxImageHeight = minOf(478.dp, (maxPanelHeight - if (roomy) 219.dp else 90.dp).coerceAtLeast(1.dp))
        val ratio = (aspects[photo.id] ?: photo.aspectRatio ?: 1f).takeIf { it > 0f && it.isFinite() } ?: 1f
        val photoWidth = minOf(maxImageWidth, maxImageHeight * ratio).coerceAtLeast(1.dp)
        val photoHeight = minOf(maxImageHeight, maxImageWidth / ratio).coerceAtLeast(1.dp)
        val shade = colors.ink.copy(alpha = .12f * reveal)
        val panelShape = RoundedCornerShape(if (roomy) 69.dp else 40.dp)
        val liveGlass = backdropLayer != null && android.os.Build.VERSION.SDK_INT >= 31 && !lowRam && !reduced
        val panelFill = if (liveGlass) Brush.verticalGradient(
            0f to colors.card.copy(alpha = .98f), .10f to colors.card.copy(alpha = .52f),
            .30f to colors.paperSecondary.copy(alpha = .14f), .75f to colors.paperSecondary.copy(alpha = .22f),
            1f to colors.card.copy(alpha = .92f)
        ) else Brush.verticalGradient(listOf(colors.card.copy(alpha = .98f), colors.paperSecondary.copy(alpha = .88f), colors.card.copy(alpha = .96f)))
        val topPadding = if (roomy) 64.dp else 24.dp
        val bottomPadding = if (roomy) 56.dp else 24.dp
        val lift = if (reduced) 0f else with(density) { 8.dp.toPx() } * sin(PI * reveal).toFloat()
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
                    translationX = if (reduced) 0f else dragOffset.x * .15f * reveal
                    translationY = if (reduced) 0f else dragOffset.y * .56f * reveal - lift
                    rotationZ = if (reduced) 0f else (dragOffset.x / 150f).coerceIn(-1.5f, 1.5f)
                    alpha = reveal
                }
                .shadow(24.dp, panelShape, ambientColor = colors.ink.copy(alpha = .06f), spotColor = colors.ink.copy(alpha = .06f))
                .shadow(8.dp, panelShape, ambientColor = colors.ink.copy(alpha = .12f), spotColor = colors.ink.copy(alpha = .12f))
                .clip(panelShape).drawBehind {
                    if (liveGlass && backdropLayer != null) {
                        // Reuse the mounted Home display list. No bitmap, file, or second data tree.
                        glassDrawing.record(size = IntSize(viewport.width.toInt(), viewport.height.toInt())) { drawLayer(backdropLayer) }
                        val radius = (34.65.dp.toPx() * reveal).coerceAtLeast(.01f)
                        glassDrawing.renderEffect = android.graphics.RenderEffect.createBlurEffect(radius, radius,
                            android.graphics.Shader.TileMode.CLAMP).asComposeRenderEffect()
                        drawRect(colors.paper)
                        translate(viewport.center.x * -1f + cardSize.width / 2f,
                            viewport.center.y * -1f + cardSize.height / 2f) { drawLayer(glassDrawing) }
                        drawRect(shade)
                    }
                }.background(panelFill).border(1.dp, colors.card.copy(alpha = .9f), panelShape)
                .testTag("life-origin-paper")
                .semantics {
                    stateDescription = "第 " + (current + 1) + " 张，共 " + photos.size + " 张"
                    customActions = buildList {
                        add(CustomAccessibilityAction("关闭照片浮层") { close(); true })
                        if (current > 0) add(CustomAccessibilityAction("上一张照片") { step(-1) })
                        if (current < photos.lastIndex) add(CustomAccessibilityAction("下一张照片") { step(1) })
                        if (onRemove != null) add(CustomAccessibilityAction("从首页移除照片") { removePhoto = photo; true })
                        if (onEditCaption != null) add(CustomAccessibilityAction("编辑照片备注") { onEditCaption(photo); true })
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
                            if (!closing) dragOffset = Offset.Zero
                        }
                    }
                }
                .then(settleSize)
                .verticalScroll(rememberScrollState())
                .padding(start = gutter, end = gutter, top = topPadding, bottom = bottomPadding),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Only this image moves from the source. The glass and metadata fade independently,
            // so opening a portrait never stretches the photo to the glass card's aspect ratio.
            Spacer(Modifier.size(photoWidth, photoHeight).onGloballyPositioned {
                // Undo the card's display-only lean/lift when measuring the layout slot;
                // otherwise the separately drawn image would receive those transforms twice.
                val center = it.localToWindow(Offset(it.size.width / 2f, it.size.height / 2f)) - windowOffset
                val translation = if (reduced) Offset.Zero else Offset(dragOffset.x * .15f * reveal, dragOffset.y * .56f * reveal - lift)
                val delta = center - viewport.center - translation
                val angle = Math.toRadians((if (reduced) 0f else -(dragOffset.x / 150f).coerceIn(-1.5f, 1.5f)).toDouble())
                val point = viewport.center + Offset((delta.x * cos(angle) - delta.y * sin(angle)).toFloat(),
                    (delta.x * sin(angle) + delta.y * cos(angle)).toFloat())
                imageTarget = Rect(point.x - it.size.width / 2f, point.y - it.size.height / 2f,
                    point.x + it.size.width / 2f, point.y + it.size.height / 2f)
            })
            Column(Modifier.fillMaxWidth().padding(top = 15.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                dateFor(photo)?.let { date ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(date.format(DateTimeFormatter.ofPattern("M月d日")), style = LifeType.DateHero.copy(fontSize = 16.sp, lineHeight = 19.sp, letterSpacing = .65.sp), color = colors.inkSecondary)
                        if (date == today) Text("今天", style = LifeType.JournalMeta.copy(fontSize = 14.sp, lineHeight = 19.sp, letterSpacing = 2.sp), color = colors.inkSecondary)
                    }
                }
                val note = photo.caption?.takeIf(String::isNotBlank)
                if (note != null || onEditCaption != null) Text(note ?: "要不要留下点什么呢",
                    Modifier.then(if (onEditCaption != null) Modifier.clickable(onClickLabel = "编辑照片备注") { onEditCaption(photo) } else Modifier),
                    style = LifeType.journalFor(note.orEmpty()).copy(fontSize = 14.sp, lineHeight = 16.sp, letterSpacing = 4.sp), color = colors.accentDeep, maxLines = 3)
                if (photo.motionAvailable) Text("此照片含动态组件，当前无法播放", style = LifeType.JournalMeta, color = colors.muted)
            }
            PhotoInkIndicator(current, photos.size, if (reduced) 0f else (-dragOffset.x / distanceThreshold).coerceIn(-1f, 1f), Modifier.padding(top = 23.dp))
        }
        if (imageTarget.width > 0f && imageTarget.height > 0f) {
            val inset = with(density) { originPaperInset.toPx() }
            val imageSource = source?.let {
                it.copy(rect = if (it.rect.width > inset * 2 && it.rect.height > inset * 2) Rect(
                    it.rect.left + inset, it.rect.top + inset, it.rect.right - inset, it.rect.bottom - inset
                ) else it.rect)
            }
            val transform = photoSharedTransform(if (reduced) null else imageSource, imageTarget, reveal)
            Box(Modifier.offset(with(density) { imageTarget.left.toDp() }, with(density) { imageTarget.top.toDp() })
                .size(photoWidth, photoHeight).graphicsLayer {
                    translationX = transform.translation.x + if (reduced) 0f else dragOffset.x * .15f * reveal
                    translationY = transform.translation.y + if (reduced) 0f else dragOffset.y * .56f * reveal - lift
                    scaleX = transform.scale; scaleY = transform.scale
                    rotationZ = transform.rotation + if (reduced) 0f else (dragOffset.x / 150f).coerceIn(-1.5f, 1.5f) * reveal
                    alpha = if (source == null || reduced) reveal else 1f
                }.testTag("life-photo-shared-image").drawBehind {
                    val edge = if (source == null || reduced) 0f else inset * (1f - reveal) / transform.scale.coerceAtLeast(.01f)
                    if (edge > 0f) drawRoundRect(if (colors.isDark) colors.card else Color(0xFFFFFDFA),
                        Offset(-edge, -edge), androidx.compose.ui.geometry.Size(size.width + 2 * edge, size.height + 2 * edge), CornerRadius(1.dp.toPx()))
                }) {
                LifeMediaImage(photo, Modifier.fillMaxSize(), ContentScale.Fit)
                if (inset > 0f && source != null && !reduced && reveal < 1f) Image(
                    painterResource(R.drawable.lifeos_photo_tape), null,
                    Modifier.align(Alignment.TopCenter).offset(y = -17.dp / transform.scale.coerceAtLeast(.01f))
                        .size(41.dp / transform.scale.coerceAtLeast(.01f), 22.dp / transform.scale.coerceAtLeast(.01f))
                        .graphicsLayer { alpha = 1f - reveal; rotationZ = 50.817f * (1f - reveal); translationY = -12.dp.toPx() * reveal }
                )
            }
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
private fun PhotoInkIndicator(current: Int, count: Int, pageOffset: Float, modifier: Modifier) {
    val tint = ColorFilter.tint(LocalLifeOsColors.current.accentDeep)
    val dots = listOf(R.drawable.lifeos_photo_dot_one, R.drawable.lifeos_photo_dot_two, R.drawable.lifeos_photo_dot_three)
    val start = (current - 3).coerceIn(0, (count - 7).coerceAtLeast(0))
    val focus = (current + pageOffset).coerceIn(0f, (count - 1).toFloat())
    val reduced = reducedLifeMotion()
    Row(modifier.height(16.dp), horizontalArrangement = Arrangement.spacedBy(9.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(minOf(count, 7)) { offset ->
            val index = start + offset
            val selection by animateFloatAsState((1f - abs(focus - index)).coerceIn(0f, 1f),
                animationSpec = if (reduced) tween(150) else spring(dampingRatio = .9f, stiffness = 450f), label = "photo-ink-paw")
            Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) {
                Image(painterResource(dots[index % dots.size]), null,
                    Modifier.offset(y = listOf((-6).dp, 5.dp, 0.dp)[index % 3]).size((3 + index % 3).dp)
                        .graphicsLayer { alpha = 1f - selection }, colorFilter = tint)
                Image(painterResource(R.drawable.lifeos_home_paw), null, Modifier.size(14.dp).graphicsLayer {
                    alpha = selection; scaleX = if (reduced) 1f else .7f + .3f * selection; scaleY = scaleX
                }, colorFilter = tint)
            }
        }
    }
}
