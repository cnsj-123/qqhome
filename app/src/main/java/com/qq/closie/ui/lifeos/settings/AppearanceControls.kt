package com.qq.closie.ui.lifeos.settings

import com.qq.closie.life.ui.theme.BundledText as Text

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.mapSaver
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
import androidx.compose.ui.unit.sp
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

/** A local color-edit session. Cancel never changes the theme draft. */
@Composable
internal fun LocalColorEditor(title: String, initial: String, origin: com.qq.closie.ui.lifeos.components.LifeSurfaceOrigin? = null,
    roles: List<Pair<ThemeField, String>> = emptyList(), activeRole: ThemeField? = null,
    onColors: ((Map<ThemeField, String>) -> Unit)? = null,
    onColor: (String) -> Unit, onDismiss: () -> Unit) {
    SuspendFunctionPageSwipe()
    val colors = LocalLifeOsColors.current
    val originals = remember { roles.toMap() }
    val drafts = rememberSaveable(saver = mapSaver<androidx.compose.runtime.snapshots.SnapshotStateMap<ThemeField, String>>(
        save = { values -> values.entries.associate { it.key.name to it.value } },
        restore = { values -> mutableStateMapOf<ThemeField, String>().apply {
            values.forEach { (key, value) -> put(ThemeField.valueOf(key), value as String) }
        } }
    )) { mutableStateMapOf<ThemeField, String>().apply { putAll(originals) } }
    var role by rememberSaveable { mutableStateOf(activeRole) }
    val starting = role?.let { drafts[it] } ?: initial
    val hsv = remember(role) { FloatArray(3).also { android.graphics.Color.colorToHSV(android.graphics.Color.parseColor(starting), it) } }
    var hue by rememberSaveable(role) { mutableFloatStateOf(hsv[0]) }
    var saturation by rememberSaveable(role) { mutableFloatStateOf(hsv[1]) }
    var value by rememberSaveable(role) { mutableFloatStateOf(hsv[2]) }
    var hex by rememberSaveable(role) { mutableStateOf(starting) }
    var planeOpen by rememberSaveable { mutableStateOf(false) }
    fun publish() {
        hex = "#%06X".format(android.graphics.Color.HSVToColor(floatArrayOf(hue, saturation, value)) and 0xFFFFFF)
        role?.let { drafts[it] = hex }
    }
    val updatePlane by rememberUpdatedState<(Float, Float) -> Unit>({ x, y ->
        saturation = x.coerceIn(0f, 1f); value = (1f - y).coerceIn(0f, 1f); publish()
    })
    SettingsOriginDialog(origin, onDismiss, bottom = true) { close ->
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 32.dp)) {
            Box(Modifier.align(Alignment.CenterHorizontally).padding(top = 13.dp, bottom = 12.dp).size(34.dp, 4.dp)
                .background(colors.inkSecondary.copy(alpha = .22f), androidx.compose.foundation.shape.RoundedCornerShape(2.dp)))
            Row(Modifier.fillMaxWidth().heightIn(min = 30.dp).padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("编辑颜色 · ", style = SettingsText.caption, color = colors.inkSecondary)
                Text(role?.shortLabel() ?: title, Modifier.weight(1f), style = SettingsText.title.copy(fontSize = 20.sp, lineHeight = 24.sp), color = colors.ink)
                roles.forEach { (field, original) ->
                    Box(Modifier.size(30.dp).clickable { role = field }, contentAlignment = Alignment.Center) {
                        if (field == role) Box(Modifier.size(25.dp).border(1.dp, colors.accentDeep, androidx.compose.foundation.shape.CircleShape))
                        SettingsColorSwatch(drafts[field] ?: original, Modifier.size(17.dp), corner = 8.5.dp, edge = .5.dp, elevation = 0.dp)
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(bottom = 14.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SettingsColorSwatch(hex, Modifier.size(42.dp).clickable { planeOpen = !planeOpen }
                    .semantics { contentDescription = if (planeOpen) "收起饱和度与明度色板" else "打开饱和度与明度色板" },
                    large = true, corner = 12.dp, elevation = 0.dp)
                Box(Modifier.weight(1f).height(32.dp).border(1.dp,
                    if (HexColor.normalizeOrNull(hex) == null) MaterialTheme.colorScheme.error else colors.line,
                    androidx.compose.foundation.shape.RoundedCornerShape(8.dp)).padding(horizontal = 9.dp), contentAlignment = Alignment.CenterStart) {
                    BasicTextField(hex, { text ->
                        hex = text
                        HexColor.normalizeOrNull(text)?.let { valid ->
                            android.graphics.Color.colorToHSV(android.graphics.Color.parseColor(valid), hsv)
                            hue = hsv[0]; saturation = hsv[1]; value = hsv[2]
                            role?.let { drafts[it] = valid }
                        }
                    }, Modifier.fillMaxWidth().semantics { contentDescription = "HEX 颜色" }, singleLine = true,
                        textStyle = SettingsText.caption.copy(color = colors.ink))
                }
                Text("还原", Modifier.heightIn(min = 42.dp).wrapContentHeight().clickable {
                    val original = role?.let { originals[it] } ?: initial
                    android.graphics.Color.colorToHSV(android.graphics.Color.parseColor(original), hsv)
                    hue = hsv[0]; saturation = hsv[1]; value = hsv[2]; hex = original
                    role?.let { drafts[it] = original }
                }, style = SettingsText.detail, color = colors.inkSecondary)
            }
            if (HexColor.normalizeOrNull(hex) == null) Text("请输入六位 HEX，例如 #6F67A8。", style = SettingsText.caption, color = MaterialTheme.colorScheme.error)
            if (planeOpen) Canvas(Modifier.fillMaxWidth().height(160.dp).padding(bottom = 12.dp)
                .semantics { contentDescription = "饱和度与明度色板，也可使用下方滑块调整" }.pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(); updatePlane(down.position.x / size.width, down.position.y / size.height); down.consume()
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
            @Composable fun HsvLabel(label: String, current: String) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(label, style = SettingsText.caption, color = colors.inkSecondary)
                    Text(current, style = SettingsText.tiny, color = colors.ink)
                }
            }
            HsvLabel("色相 H", hue.toInt().toString() + "°")
            ThinAppearanceSlider(hue, { hue = it; publish() }, valueRange = 0f..359.9f, compact = true,
                trackColors = listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red))
            HsvLabel("饱和度 S", (saturation * 100).toInt().toString() + "%")
            ThinAppearanceSlider(saturation, { saturation = it; publish() }, compact = true,
                trackColors = listOf(Color.hsv(hue, 0f, value), Color.hsv(hue, 1f, value)))
            HsvLabel("明度 V", (value * 100).toInt().toString() + "%")
            ThinAppearanceSlider(value, { value = it; publish() }, compact = true,
                trackColors = listOf(Color.Black, Color.hsv(hue, saturation, 1f)))
            Row(Modifier.fillMaxWidth().padding(top = 5.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(close, Modifier.weight(1f).height(42.dp), shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)) { Text("取消", style = SettingsText.caption) }
                Button({
                    HexColor.normalizeOrNull(hex)?.let { valid ->
                        role?.let { drafts[it] = valid }
                        if (onColors != null) onColors(drafts.toMap()) else onColor(valid)
                        close()
                    }
                }, Modifier.weight(1.55f).height(42.dp), enabled = HexColor.normalizeOrNull(hex) != null,
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)) { Text("保存到草稿", style = SettingsText.caption) }
            }
            Text("保存到草稿后预览，保存主题后正式生效。", Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp, bottom = 16.dp),
                style = SettingsText.tiny.copy(fontSize = 9.sp), color = colors.inkSecondary)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ThinAppearanceSlider(value: Float, onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f, enabled: Boolean = true,
    trackColors: List<Color>? = null, compact: Boolean = false) {
    val colors = LocalLifeOsColors.current
    Slider(value, onValueChange, modifier = if (compact) Modifier.height(33.dp) else Modifier, valueRange = valueRange, enabled = enabled,
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
