package com.qq.closie.ui.lifeos.settings

import com.qq.closie.life.ui.theme.BundledText as Text

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.qq.closie.data.appearance.FunctionPageEffects
import com.qq.closie.data.appearance.HexColor
import com.qq.closie.ui.lifeos.components.ColorSwatch
import com.qq.closie.ui.lifeos.components.SuspendFunctionPageSwipe
import com.qq.closie.ui.lifeos.theme.*

@Composable
internal fun FunctionEffectControls(effects: FunctionPageEffects, onChange: (FunctionPageEffects) -> Unit,
    enabled: Boolean = true, home: Boolean = false) {
    val colors = LocalLifeOsColors.current
    val fields: List<Triple<String, Int, (Int) -> FunctionPageEffects>> = if (home) listOf(
        Triple("首页背景虚化", effects.homeBlur, { effects.copy(homeBlur = it) }),
        Triple("首页渐变强度", effects.homeGradient, { effects.copy(homeGradient = it) })
    ) else listOf(
        Triple("色彩氛围", effects.atmosphere, { effects.copy(atmosphere = it) }),
        Triple("不透明度", effects.opacity, { effects.copy(opacity = it) }),
        Triple("功能页背景虚化", effects.blur, { effects.copy(blur = it) }),
        Triple("功能页渐变强度", effects.gradient, { effects.copy(gradient = it) })
    )
    fields.forEach { (label, value, update) ->
        Row { Text(label, Modifier.weight(1f)); Text(value.toString(), style = LifeText.caption) }
        ThinAppearanceSlider(value.toFloat(), { onChange(update(it.toInt())) },
            valueRange = 0f..100f, enabled = enabled)
    }
    Text("仅改变背景，文字保持清晰。预览尚未保存。", style = LifeText.caption, color = colors.muted)
    TextButton({
        val defaults = FunctionPageEffects()
        onChange(if (home) effects.copy(homeBlur = defaults.homeBlur, homeGradient = defaults.homeGradient)
            else effects.copy(atmosphere = defaults.atmosphere, opacity = defaults.opacity,
                blur = defaults.blur, gradient = defaults.gradient))
    }, enabled = enabled) { Text("恢复推荐值") }
}

/** Local HSV editor. The plane and sliders are equivalent inputs; HEX is optional precision. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LocalColorEditor(title: String, initial: String, onColor: (String) -> Unit, onDismiss: () -> Unit) {
    SuspendFunctionPageSwipe()
    val colors = LocalLifeOsColors.current
    val hsv = remember(title, initial) {
        FloatArray(3).also { android.graphics.Color.colorToHSV(android.graphics.Color.parseColor(initial), it) }
    }
    var hue by rememberSaveable(title, initial) { mutableFloatStateOf(hsv[0]) }
    var saturation by rememberSaveable(title, initial) { mutableFloatStateOf(hsv[1]) }
    var value by rememberSaveable(title, initial) { mutableFloatStateOf(hsv[2]) }
    var hex by rememberSaveable(title, initial) { mutableStateOf(initial) }
    var planeOpen by rememberSaveable(title) { mutableStateOf(false) }
    fun publish() {
        hex = "#%06X".format(android.graphics.Color.HSVToColor(floatArrayOf(hue, saturation, value)) and 0xFFFFFF)
    }
    val updatePlane by rememberUpdatedState<(Float, Float) -> Unit>({ x, y ->
        saturation = x.coerceIn(0f, 1f); value = (1f - y).coerceIn(0f, 1f); publish()
    })
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = colors.paper
    ) {
        Column(Modifier.fillMaxWidth().heightIn(max = (LocalConfiguration.current.screenHeightDp * .88f).dp)
            .imePadding().padding(horizontal = 24.dp)) {
            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("编辑颜色 · $title", Modifier.weight(1f), style = LifeText.body, color = colors.ink)
                TextButton({ planeOpen = !planeOpen }) {
                    Text(if (planeOpen) "收起色板" else "打开色板", style = LifeText.caption)
                }
            }
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ColorSwatch(hex, Modifier.size(38.dp))
                    OutlinedTextField(hex, { text ->
                        hex = text
                        HexColor.normalizeOrNull(text)?.let { valid ->
                            android.graphics.Color.colorToHSV(android.graphics.Color.parseColor(valid), hsv)
                            hue = hsv[0]; saturation = hsv[1]; value = hsv[2]
                        }
                    }, Modifier.weight(1f), singleLine = true, label = { Text("HEX") },
                        isError = HexColor.normalizeOrNull(hex) == null,
                        textStyle = com.qq.closie.life.ui.theme.LifeType.inputFor(hex))
                    TextButton({
                        android.graphics.Color.colorToHSV(android.graphics.Color.parseColor(initial), hsv)
                        hue = hsv[0]; saturation = hsv[1]; value = hsv[2]; hex = initial
                    }) { Text("还原", style = LifeText.caption) }
                }
                if (HexColor.normalizeOrNull(hex) == null) Text("请输入六位 HEX，例如 #6F67A8。",
                    style = LifeText.caption, color = MaterialTheme.colorScheme.error)
                if (planeOpen) Canvas(Modifier.fillMaxWidth().height(160.dp)
                    .semantics { contentDescription = "饱和度与明度色板，也可使用下方滑块调整" }.pointerInput(Unit) {
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
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("色相 H", style = LifeText.caption)
                    Text("${hue.toInt()}°", style = LifeText.caption)
                }
                ThinAppearanceSlider(hue, { hue = it; publish() }, valueRange = 0f..359.9f,
                    trackColors = listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("饱和度 S", style = LifeText.caption)
                    Text("${(saturation * 100).toInt()}%", style = LifeText.caption)
                }
                ThinAppearanceSlider(saturation, { saturation = it; publish() },
                    trackColors = listOf(Color.hsv(hue, 0f, value), Color.hsv(hue, 1f, value)))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("明度 V", style = LifeText.caption)
                    Text("${(value * 100).toInt()}%", style = LifeText.caption)
                }
                ThinAppearanceSlider(value, { value = it; publish() },
                    trackColors = listOf(Color.Black, Color.hsv(hue, saturation, 1f)))
            }
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onDismiss, Modifier.weight(1f)) { Text("取消") }
                Button({
                    HexColor.normalizeOrNull(hex)?.let {
                        onColor(it)
                        onDismiss()
                    }
                }, Modifier.weight(1.3f), enabled = HexColor.normalizeOrNull(hex) != null) { Text("保存到草稿") }
            }
            Text("保存到草稿后可预览，保存主题后正式生效。",
                Modifier.padding(top = 6.dp, bottom = 16.dp), style = LifeText.caption, color = colors.muted)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ThinAppearanceSlider(value: Float, onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f, enabled: Boolean = true,
    trackColors: List<Color>? = null) {
    val colors = LocalLifeOsColors.current
    Slider(value, onValueChange, valueRange = valueRange, enabled = enabled,
        thumb = {
            Canvas(Modifier.size(20.dp)) { drawCircle(if (enabled) colors.accent else colors.muted, radius = 6.dp.toPx()) }
        },
        track = { state ->
            Canvas(Modifier.fillMaxWidth().height(4.dp)) {
                val fraction = ((state.value - valueRange.start) / (valueRange.endInclusive - valueRange.start)).coerceIn(0f, 1f)
                val rtl = layoutDirection == androidx.compose.ui.unit.LayoutDirection.Rtl
                val start = Offset(if (rtl) size.width else 0f, size.height / 2)
                val end = Offset(if (rtl) 0f else size.width, size.height / 2)
                if (trackColors != null && enabled) {
                    val stops = if (rtl) trackColors.reversed() else trackColors
                    drawLine(Brush.horizontalGradient(stops), start, end, size.height, androidx.compose.ui.graphics.StrokeCap.Round)
                } else {
                    drawLine(colors.line, start, end, size.height, androidx.compose.ui.graphics.StrokeCap.Round)
                    drawLine(if (enabled) colors.accent else colors.muted, start,
                        Offset(start.x + (end.x - start.x) * fraction, start.y), size.height, androidx.compose.ui.graphics.StrokeCap.Round)
                }
            }
        })
}
