package com.qq.closie.ui.lifeos.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import android.app.ActivityManager
import android.content.Context
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.qq.closie.data.appearance.FunctionPageEffects
import com.qq.closie.ui.lifeos.theme.LocalLifeOsColors

/** Shared by the real surface and its preview. Motion/style never changes effect semantics. */
internal data class FunctionPageVisuals(val surface: Brush, val backdropBlur: RenderEffect?)

@Composable
internal fun rememberFunctionPageVisuals(effects: FunctionPageEffects, home: Boolean = false): FunctionPageVisuals {
    val colors = LocalLifeOsColors.current
    val context = LocalContext.current
    val reduced = reducedLifeMotion()
    val radius = with(LocalDensity.current) { (if (home) effects.homeBlur / 5f else effects.blurDp).dp.toPx() }
    return remember(colors, effects, context, radius, home, reduced) {
        val lowRam = (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).isLowRamDevice
        val blur = if (Build.VERSION.SDK_INT >= 31 && !lowRam && !reduced && radius > 0f)
            android.graphics.RenderEffect.createBlurEffect(radius, radius, android.graphics.Shader.TileMode.CLAMP).asComposeRenderEffect()
        else null
        // Tonal diffusion also works on API 26/low-RAM devices. It changes softness,
        // never the opacity endpoint or the atmosphere amount.
        val neutral = lerp(colors.paper, if (colors.isDark) androidx.compose.ui.graphics.Color.Black
            else androidx.compose.ui.graphics.Color.White, (if (home) effects.homeBlur / 100f * .35f else effects.diffusion))
        val stops = listOf(colors.secondary to .26f, colors.soft to .32f,
            colors.paper to 0f, colors.warm to .30f, colors.accent to .09f).map { (tint, mix) ->
            lerp(neutral, tint, mix * effects.atmosphereScale * (if (home) effects.homeGradient else effects.gradient) / 50f).copy(alpha = if (home) 1f else effects.surfaceAlpha)
        }
        FunctionPageVisuals(Brush.linearGradient(stops), blur)
    }
}

/** Clip after blur; never apply a render effect or opacity to the text/content layer. */
@Composable
internal fun LifePaperBackdrop(effects: FunctionPageEffects, modifier: Modifier = Modifier, home: Boolean = false) {
    val visuals = rememberFunctionPageVisuals(effects, home)
    Box(modifier.graphicsLayer { clip = true }) {
        Box(Modifier.fillMaxSize().graphicsLayer { renderEffect = visuals.backdropBlur }.background(visuals.surface))
    }
}
