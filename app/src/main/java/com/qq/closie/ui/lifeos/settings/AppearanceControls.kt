package com.qq.closie.ui.lifeos.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.qq.closie.data.appearance.FunctionPageEffects
import com.qq.closie.data.appearance.HexColor
import com.qq.closie.ui.lifeos.components.ColorSwatch
import com.qq.closie.ui.lifeos.components.SuspendFunctionPageSwipe
import com.qq.closie.ui.lifeos.components.rememberFunctionPageVisuals
import com.qq.closie.ui.lifeos.theme.*

@Composable
internal fun FunctionEffectControls(effects: FunctionPageEffects, onChange: (FunctionPageEffects) -> Unit,
    onFinish: () -> Unit, enabled: Boolean = true) {
    val colors = LocalLifeOsColors.current
    val visuals = rememberFunctionPageVisuals(effects)
    Text("功能页效果", Modifier.padding(top = 12.dp), style = LifeText.title)
    Text("调整后即时保存；主题的保存 / 取消不会撤销这些调整。", style = LifeText.caption, color = colors.muted)
    listOf("色彩氛围" to effects.atmosphere, "不透明度" to effects.opacity, "背景柔化" to effects.blur).forEachIndexed { index, (label, value) ->
        Row { Text(label, Modifier.weight(1f)); Text(value.toString(), style = LifeText.caption) }
        Slider(value.toFloat(), { next -> onChange(when (index) {
            0 -> effects.copy(atmosphere = next.toInt())
            1 -> effects.copy(opacity = next.toInt())
            else -> effects.copy(blur = next.toInt())
        }) }, valueRange = 0f..100f, onValueChangeFinished = onFinish, enabled = enabled)
    }
    // The shell uses the same draft immediately; this sample stays visible while adjusting.
    Box(Modifier.fillMaxWidth().height(64.dp).background(Brush.horizontalGradient(listOf(colors.secondary, colors.warm)))) {
        Box(Modifier.fillMaxSize().graphicsLayer { renderEffect = visuals.backdropBlur }.padding(horizontal = 35.dp)
            .background(Brush.horizontalGradient(listOf(colors.accent, colors.paper, colors.warm))))
        Box(Modifier.fillMaxSize().background(visuals.backdropSoftening))
        Column(Modifier.fillMaxSize().background(visuals.surface).padding(12.dp)) {
            Text("功能页 · 即时预览", style = LifeText.caption, color = colors.ink)
            Text("色彩与透光随你调整", style = LifeText.caption, color = colors.inkSecondary)
        }
    }
    Text("柔化作用于背景；设备不支持模糊时使用轻量色调柔化。", style = LifeText.caption, color = colors.muted)
    TextButton({ onChange(FunctionPageEffects()); onFinish() }, enabled = enabled) { Text("恢复推荐值") }
}

/** Local HSV editor. The plane and sliders are equivalent inputs; HEX is optional precision. */
@Composable
internal fun LocalColorEditor(title: String, initial: String, onColor: (String) -> Unit, onDismiss: () -> Unit) {
    SuspendFunctionPageSwipe()
    val hsv = remember { FloatArray(3).also { android.graphics.Color.colorToHSV(android.graphics.Color.parseColor(initial), it) } }
    var hue by remember { mutableFloatStateOf(hsv[0]) }
    var saturation by remember { mutableFloatStateOf(hsv[1]) }
    var value by remember { mutableFloatStateOf(hsv[2]) }
    var hex by remember { mutableStateOf(initial) }
    fun publish() {
        hex = "#%06X".format(android.graphics.Color.HSVToColor(floatArrayOf(hue, saturation, value)) and 0xFFFFFF)
        onColor(hex)
    }
    val updatePlane by rememberUpdatedState<(Float, Float) -> Unit>({ x, y ->
        saturation = x.coerceIn(0f, 1f); value = (1f - y).coerceIn(0f, 1f); publish()
    })
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = {
        Column {
            Canvas(Modifier.fillMaxWidth().height(180.dp).pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    updatePlane(down.position.x / size.width, down.position.y / size.height)
                    down.consume()
                    do {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (change.pressed) updatePlane(change.position.x / size.width, change.position.y / size.height)
                        change.consume()
                    } while (event.changes.any { it.pressed })
                }
            }) {
                drawRect(Brush.horizontalGradient(listOf(Color.White, Color.hsv(hue, 1f, 1f))))
                drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
                val point = Offset(saturation * size.width, (1f - value) * size.height)
                drawCircle(Color.Black, 7.dp.toPx(), point, style = Stroke(3.dp.toPx()))
                drawCircle(Color.White, 7.dp.toPx(), point, style = Stroke(1.dp.toPx()))
            }
            Text("色相", style = LifeText.caption)
            Slider(hue, { hue = it; publish() }, valueRange = 0f..359.9f)
            Text("饱和度 ${(saturation * 100).toInt()}%", style = LifeText.caption)
            Slider(saturation, { saturation = it; publish() })
            Text("明度 ${(value * 100).toInt()}%", style = LifeText.caption)
            Slider(value, { value = it; publish() })
            OutlinedTextField(hex, { text ->
                hex = text
                HexColor.normalizeOrNull(text)?.let { valid ->
                    android.graphics.Color.colorToHSV(android.graphics.Color.parseColor(valid), hsv)
                    hue = hsv[0]; saturation = hsv[1]; value = hsv[2]; onColor(valid)
                }
            }, singleLine = true, label = { Text("HEX") }, leadingIcon = { ColorSwatch(hex) },
                isError = HexColor.normalizeOrNull(hex) == null)
        }
    }, confirmButton = { TextButton(onDismiss) { Text("完成") } })
}
