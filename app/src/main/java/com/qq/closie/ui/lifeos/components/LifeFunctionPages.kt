package com.qq.closie.ui.lifeos.components

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.zIndex
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.asComposeRenderEffect
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
    horizontalPages: Boolean = false,
    home: Boolean = false,
    effects: com.qq.closie.data.appearance.FunctionPageEffects = com.qq.closie.data.appearance.FunctionPageEffects(),
    onOpenChange: (Boolean) -> Unit, functionPage: @Composable () -> Unit,
    homePreview: (@Composable () -> Unit)? = null, content: @Composable () -> Unit
) {
    val colors = LocalLifeOsColors.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val lowRam = remember(context) { (context.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager).isLowRamDevice }
    var progress by remember { mutableFloatStateOf(if (open) 1f else 0f) }
    var settleVelocity by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    val currentOpen by rememberUpdatedState(open)
    val changeOpen by rememberUpdatedState(onOpenChange)
    val edge = com.qq.closie.ui.lifeos.components.systemBackEdgePx()
        LaunchedEffect(open, dragging, reducedMotion) {
        if (!dragging) {
            animate(progress, if (open) 1f else 0f, initialVelocity = if (reducedMotion) 0f else settleVelocity,
                animationSpec = if (reducedMotion) tween(150) else spring(dampingRatio = .9f, stiffness = 380f)) { value, velocity ->
                progress = value.coerceIn(0f, 1f); settleVelocity = velocity
            }
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val revealWidth = maxWidth * .84f
        val travel = with(LocalDensity.current) { revealWidth.toPx() }
        val policy = LocalFunctionPagePolicy.current
        DisposableEffect(policy, enabled, horizontalPages, travel) {
            if (enabled && horizontalPages && travel > 0f) policy?.financeDrawerDrag = { distance, settle ->
                if (settle == null) {
                    if (!currentOpen) {
                        dragging = true
                        settleVelocity = 0f
                        progress = (distance / travel).coerceIn(0f, 1f)
                    }
                } else {
                    dragging = false
                    changeOpen(settle)
                }
            }
            onDispose {
                if (horizontalPages) {
                    policy?.financeDrawerDrag = null
                    dragging = false
                }
            }
        }
        val mode = if (reducedMotion) FunctionPageStyle.FLAT else style
        Box(Modifier.fillMaxSize().background(colors.paper)
            .pointerInput(enabled, travel, edge, horizontalPages) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val pass = androidx.compose.ui.input.pointer.PointerEventPass.Main
                    val down = awaitFirstDown(requireUnconsumed = false, pass = pass)
                    // Finance owns its local regions and sends drag distance into this surface.
                    if (!currentOpen && horizontalPages) return@awaitEachGesture
                    if (down.position.x <= edge || down.position.x >= size.width - edge) return@awaitEachGesture
                    var owned = false
                    val tracker = VelocityTracker()
                    tracker.addPosition(down.uptimeMillis, down.position)
                    val startedOpen = currentOpen
                    while (!owned) {
                        val change = awaitPointerEvent(pass).changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
                        if (!change.pressed || change.isConsumed) return@awaitEachGesture
                        val delta = change.position - down.position
                        tracker.addPosition(change.uptimeMillis, change.position)
                        when (LifeSwipeIntent.resolve(delta.x, delta.y, viewConfiguration.touchSlop)) {
                            LifeSwipeIntent.Direction.VERTICAL -> return@awaitEachGesture
                            LifeSwipeIntent.Direction.LEFT, LifeSwipeIntent.Direction.RIGHT -> {
                                if ((!currentOpen && delta.x < 0f) || (currentOpen && delta.x > 0f)) return@awaitEachGesture
                                dragging = true
                                settleVelocity = 0f
                                progress = (progress + delta.x / travel).coerceIn(0f, 1f)
                                change.consume()
                                owned = true
                            }
                            null -> Unit
                        }
                    }
                    try {
                        val released = horizontalDrag(down.id) { change ->
                                tracker.addPosition(change.uptimeMillis, change.position)
                                progress = (progress + (change.position.x - change.previousPosition.x) / travel).coerceIn(0f, 1f)
                                change.consume()
                        }
                        val velocity = tracker.calculateVelocity().x
                        settleVelocity = velocity / travel
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
            Box(Modifier.width(revealWidth).fillMaxHeight()
                .zIndex(if (reducedMotion || mode == FunctionPageStyle.GLASS) 1f else 0f)
                .then(if (!open) Modifier.clearAndSetSemantics {} else Modifier).graphicsLayer {
                translationX = if (reducedMotion) 0f else when (mode) {
                    FunctionPageStyle.FLAT, FunctionPageStyle.GLASS -> -(1f - progress) * travel
                    FunctionPageStyle.DECK -> -(1f - progress) * travel * .24f
                    else -> -(1f - progress) * travel * .08f
                }
                alpha = if (reducedMotion) progress else 1f
                rotationY = if (mode == FunctionPageStyle.DECK) -14f * (1f - progress) else 0f
                transformOrigin = TransformOrigin(0f, .5f)
                cameraDistance = 18.dp.toPx()
            }) {
                LifePaperBackdrop(effects, Modifier.fillMaxSize(),drawer=true)
                functionPage()
            }
            Box(Modifier.fillMaxSize().zIndex(if (reducedMotion || mode == FunctionPageStyle.GLASS) 0f else 1f).graphicsLayer {
                translationX = if (reducedMotion) 0f else progress * travel * if (mode == FunctionPageStyle.GLASS) .18f else 1f
                scaleX = when (mode) {
                    FunctionPageStyle.LAYERED -> 1f - .085f * progress
                    FunctionPageStyle.DECK -> 1f - .10f * progress
                    else -> 1f
                }
                scaleY = scaleX
                transformOrigin = TransformOrigin(0f, .5f)
                rotationY = if (mode == FunctionPageStyle.DECK) -24f * progress else 0f
                rotationZ = if (mode == FunctionPageStyle.DECK) -2f * progress else 0f
                // This is the mounted content layer, so text/photos really diffuse behind the drawer.
                renderEffect = if (android.os.Build.VERSION.SDK_INT >= 31 && !lowRam && !reducedMotion && progress > 0f) {
                    val radius = (effects.blurDp * progress).dp.toPx().coerceAtLeast(.01f)
                    android.graphics.RenderEffect.createBlurEffect(radius, radius, android.graphics.Shader.TileMode.CLAMP).asComposeRenderEffect()
                } else null
                cameraDistance = 18.dp.toPx()
                shape = RoundedCornerShape((22f * progress).dp)
                clip = progress > 0f
                shadowElevation = if (mode == FunctionPageStyle.LAYERED || mode == FunctionPageStyle.DECK) 10.dp.toPx() * progress else 0f
            }.background(colors.paper)) {
                if (home) LifePaperBackdrop(effects, Modifier.fillMaxSize(), home = true)
                Box(if (open) Modifier.fillMaxSize().clearAndSetSemantics {} else Modifier.fillMaxSize()) { content() }
                if (!home && homePreview != null && (open || dragging || progress > 0f)) {
                    Box(Modifier.fillMaxSize().background(colors.paper).clearAndSetSemantics {}) {
                        LifePaperBackdrop(effects, Modifier.fillMaxSize(), home = true)
                        homePreview()
                    }
                }
                if (enabled && (open || dragging)) Box(Modifier.fillMaxSize().clickable { changeOpen(false) })
            }
        }
    }
}
