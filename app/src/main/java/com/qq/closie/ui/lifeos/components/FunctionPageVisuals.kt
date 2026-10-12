package com.qq.closie.ui.lifeos.components

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
internal data class FunctionPageVisuals(val surface: Brush, val backdropBlur: RenderEffect?, val backdropSoftening: Color)

@Composable
internal fun rememberFunctionPageVisuals(effects: FunctionPageEffects): FunctionPageVisuals {
    val colors = LocalLifeOsColors.current
    val context = LocalContext.current
    val radius = with(LocalDensity.current) { effects.blurDp.dp.toPx() }
    return remember(colors, effects, context, radius) {
        val lowRam = (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).isLowRamDevice
        val blur = if (Build.VERSION.SDK_INT >= 31 && !lowRam && radius > 0f)
            android.graphics.RenderEffect.createBlurEffect(radius, radius, android.graphics.Shader.TileMode.CLAMP).asComposeRenderEffect()
        else null
        // Tonal diffusion also works on API 26/low-RAM devices. It changes softness,
        // never the opacity endpoint or the atmosphere amount.
        val neutral = lerp(colors.paper, if (colors.isDark) androidx.compose.ui.graphics.Color.Black
            else androidx.compose.ui.graphics.Color.White, effects.diffusion)
        val stops = listOf(colors.secondary to .26f, colors.soft to .32f,
            colors.paper to 0f, colors.warm to .30f, colors.accent to .09f).map { (tint, mix) ->
            lerp(neutral, tint, mix * effects.atmosphereScale).copy(alpha = effects.surfaceAlpha)
        }
        FunctionPageVisuals(Brush.linearGradient(stops), blur, colors.paper.copy(alpha = effects.diffusion))
    }
}
