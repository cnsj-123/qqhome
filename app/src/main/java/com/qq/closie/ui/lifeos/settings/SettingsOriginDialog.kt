package com.qq.closie.ui.lifeos.settings

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.qq.closie.ui.lifeos.components.LifeSurfaceOrigin
import com.qq.closie.ui.lifeos.components.interpolateLifeSurface
import com.qq.closie.ui.lifeos.components.reducedLifeMotion
import com.qq.closie.ui.lifeos.theme.LocalLifeOsColors
import kotlinx.coroutines.launch

/** Closing starts before the exit animation. Pending commands must stop at that instant. */
internal class SettingsDialogDismissal {
    var closing by mutableStateOf(false)
        private set
    private val cancellations=mutableSetOf<()->Unit>()
    fun register(cancel:()->Unit):()->Unit {
        cancellations+=cancel
        if(closing) cancel()
        return {cancellations-=cancel}
    }
    fun beginClose() {
        closing=true
        cancellations.toList().forEach {it()}
    }
}

internal val LocalSettingsDialogDismissal=staticCompositionLocalOf<SettingsDialogDismissal?> {null}

/** Lay out at the destination once, then unfold from the real pressed control bounds. */
@Composable
internal fun SettingsOriginDialog(
    origin: LifeSurfaceOrigin?,
    onDismiss: () -> Unit,
    bottom: Boolean = false,
    dismissEnabled: Boolean = true,
    scrimColor: androidx.compose.ui.graphics.Color? = null,
    content: @Composable (close: () -> Unit) -> Unit
) {
    val colors = LocalLifeOsColors.current
    val reduced = reducedLifeMotion()
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val reveal = remember { Animatable(0f) }
    var measuredHeight by remember { mutableIntStateOf(0) }
    val dismissal=remember {SettingsDialogDismissal()}
    val scope = rememberCoroutineScope()
    val canDismiss by rememberUpdatedState(dismissEnabled)
    LaunchedEffect(reduced) {
        if (dismissal.closing) return@LaunchedEffect
        if (reduced) reveal.animateTo(1f, tween(150))
        else reveal.animateTo(1f, spring(dampingRatio = .5f, stiffness = 350f, visibilityThreshold = .015f))
    }
    fun close() {
        if (dismissal.closing || !canDismiss) return
        dismissal.beginClose()
        scope.launch {
            reveal.animateTo(0f, tween(if (reduced) 150 else 250))
            onDismiss()
        }
    }
    Dialog(onDismissRequest = ::close, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect { window?.setDimAmount(0f) }
        BoxWithConstraints(Modifier.fillMaxSize().imePadding()) {
            val width = constraints.maxWidth.toFloat()
            val height = constraints.maxHeight.toFloat()
            Box(Modifier.fillMaxSize().background((scrimColor ?: colors.shade).copy(alpha = .16f * reveal.value.coerceIn(0f, 1f)))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = ::close))
            val targetWidth = if (bottom) width else minOf(width - with(density) { 40.dp.toPx() }, with(density) { 360.dp.toPx() })
            val targetHeight = measuredHeight.toFloat().takeIf { it > 0 } ?: height * .6f
            val top = if (bottom) height - targetHeight else (height - targetHeight) / 2f
            val target = Rect((width - targetWidth) / 2f, top, (width + targetWidth) / 2f, top + targetHeight)
            val source = origin?.takeIf { it.usable(configuration.orientation, Rect(0f, 0f, width, height)) }
            val bounds = interpolateLifeSurface(source?.rect ?: target, target, reveal.value)
            Surface(Modifier.align(if (bottom) Alignment.BottomCenter else Alignment.Center)
                .width(with(density) { targetWidth.toDp() }).heightIn(max = with(density) { (height * .88f).toDp() })
                .onSizeChanged { measuredHeight = it.height }.pointerInput(Unit) { detectTapGestures(onTap = {}) }.graphicsLayer {
                    if (!reduced && source != null) {
                        translationX = bounds.center.x - target.center.x
                        translationY = bounds.center.y - target.center.y
                        val overshoot = 1f + (reveal.value - 1f).coerceAtLeast(0f) * .1f
                        scaleX = (bounds.width / target.width).coerceAtLeast(.01f) * overshoot
                        scaleY = (bounds.height / target.height).coerceAtLeast(.01f) * overshoot
                        rotationY = 5f * (1f - reveal.value.coerceIn(0f, 1f))
                        cameraDistance = 12f * this.density
                    } else alpha = reveal.value.coerceIn(0f, 1f)
                },
                shape = if (bottom) RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp) else RoundedCornerShape(20.dp),
                color = colors.paper, shadowElevation = 6.dp) {
                CompositionLocalProvider(LocalSettingsDialogDismissal provides dismissal) {content(::close)}
            }
        }
    }
}
