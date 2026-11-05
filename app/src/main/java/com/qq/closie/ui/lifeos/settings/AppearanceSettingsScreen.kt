package com.qq.closie.ui.lifeos.settings

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qq.closie.data.appearance.*
import com.qq.closie.life.ui.theme.BundledText as Text
import com.qq.closie.life.ui.theme.LifeType
import com.qq.closie.ui.lifeos.components.ColorSwatch
import com.qq.closie.ui.lifeos.components.reducedLifeMotion
import com.qq.closie.ui.lifeos.theme.*
import kotlinx.coroutines.delay

@Composable
fun AppearanceSettingsRoute(viewModel: AppearanceViewModel, onBack: () -> Unit) {
    val editor by viewModel.editor.collectAsStateWithLifecycle()
    val stored by viewModel.savedAppearance.collectAsStateWithLifecycle()
    val context = LocalContext.current.applicationContext
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { viewModel.extractImage(context, it) }
    }
    LaunchedEffect(viewModel) { viewModel.beginAppearanceEdit() }
    DisposableEffect(viewModel) { onDispose { viewModel.cancelAppearanceEdit() } }
    val draft = editor
    val saved = stored
    if (draft == null || saved == null) {
        SettingsPaperPage("主题", onBack) { Text("正在读取主题…", style = LifeText.caption) }
        return
    }
    AppearanceSettingsScreen(
        state = draft,
        saved = viewModel.savedEditor(saved),
        onPreset = viewModel::previewPreset,
        onField = viewModel::editThemeField,
        onSave = viewModel::saveAppearance,
        onCancel = { viewModel.cancelAppearanceEdit(); onBack() },
        namedThemes = saved.namedThemes.filterNot { it.id in draft.removedThemeIds },
        onNamed = viewModel::previewNamed,
        onDelete = viewModel::deleteNamed,
        onFunctionPageStyle = viewModel::selectFunctionPageStyle,
        onEffects = viewModel::previewFunctionEffects,
        onName = viewModel::nameTheme,
        onImage = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
        onDuplicate = viewModel::duplicateTheme
    )
}

@Composable
private fun AppearanceSettingsScreen(
    state: AppearanceEditorState,
    saved: AppearanceEditorState,
    onPreset: (ThemePreset) -> Unit,
    onField: (ThemeField, String) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    namedThemes: List<NamedTheme>,
    onNamed: (String) -> Unit,
    onDelete: (String) -> Unit,
    onFunctionPageStyle: (FunctionPageStyle) -> Unit,
    onEffects: (FunctionPageEffects) -> Unit,
    onName: (String) -> Unit,
    onImage: () -> Unit,
    onDuplicate: () -> Unit
) {
    val colors = LocalLifeOsColors.current
    val reduced = reducedLifeMotion()
    val dirty = state.hasUnsavedChanges(saved)
    val editable = !state.isSaving && !state.extracting
    var showLibrary by rememberSaveable { mutableStateOf(false) }
    var showCustom by rememberSaveable { mutableStateOf(false) }
    var discard by rememberSaveable { mutableStateOf(false) }
    var editingColor by rememberSaveable { mutableStateOf<ThemeField?>(null) }
    val palette = state.preview.selectedColors()
    val fields = ThemeField.entries.zip(listOf(state.accent, state.paper, state.secondary, state.soft, state.warm))
    val title = ThemePreset.fromId(state.selectedId)?.displayName ?: state.themeName.ifBlank { "未命名主题" }
    val back = {
        when {
            state.isSaving -> Unit
            dirty -> discard = true
            else -> onCancel()
        }
    }
    BackHandler(onBack = back)
    SettingsPaperPage("主题", back, actions = {
        if (dirty && !state.isSaving) Text("未保存", Modifier.padding(end = 8.dp), style = LifeText.caption, color = colors.accentDeep)
        FilledTonalButton(
            onSave,
            enabled = dirty && editable && state.validationError == null && state.themeName.isNotBlank(),
            contentPadding = PaddingValues(horizontal = 12.dp)
        ) { Text(if (state.isSaving) "保存中" else "保存主题", style = LifeText.caption) }
    }) {
        Text("主题与配色", style = LifeText.caption, color = colors.muted)
        Column(Modifier.padding(top = 14.dp, bottom = 8.dp)) {
            PaletteInk(palette, reduced) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    fields.forEach { (field, value) ->
                        TextButton(
                            { editingColor = field },
                            enabled = editable,
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(0.dp)
                        ) { ColorSwatch(value, Modifier.size(32.dp)) }
                    }
                }
            }
            Text(title, style = LifeText.body, color = colors.ink, modifier = Modifier.padding(top = 4.dp))
            Text("长按以外的点击可编辑对应颜色", style = LifeText.caption, color = colors.muted)
            OutlinedButton({ showLibrary = true }, enabled = editable) { Text("更多主题色", style = LifeText.caption) }
            SettingsSaveStamp(state.saveEvent, reduced)
        }
        AppearanceGroup("首页背景", "背景虚化 · 渐变强度") {
            AppearanceLivePreviews(state.preview, home = true, function = false)
            FunctionEffectControls(state.preview.functionEffects, onEffects, editable, home = true)
        }
        AppearanceGroup("功能页风格与背景", "翻页风格 · 色彩氛围 · 不透明度 · 虚化") {
            AppearanceLivePreviews(state.preview, home = false, function = true)
            FunctionPageStyle.entries.chunked(2).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { style ->
                        FilterChip(
                            state.preview.functionPageStyle == style, { onFunctionPageStyle(style) },
                            modifier = Modifier.weight(1f), label = { Text(style.label, style = LifeText.caption) }, enabled = editable
                        )
                    }
                }
            }
            FunctionEffectControls(state.preview.functionEffects, onEffects, editable)
        }
        SettingsLine("自定义主题色", "默认收起 · 主色 / 纸色 / 辅助 / 柔和 / 暖色", trailing = if (showCustom) "收起" else "展开") {
            showCustom = !showCustom
        }
        AnimatedVisibility(showCustom, enter = fadeIn(tween(if (reduced) 0 else 150)), exit = fadeOut(tween(if (reduced) 0 else 100))) {
            Column {
                OutlinedButton(onImage, enabled = editable) { Text(if (state.extracting) "正在取色…" else "从照片取色", style = LifeText.caption) }
                TextButton(onDuplicate, enabled = editable) { Text("复制当前主题", style = LifeText.caption) }
                OutlinedTextField(
                    state.themeName, onName, Modifier.fillMaxWidth(), label = { Text("主题名称") }, singleLine = true,
                    enabled = editable, textStyle = LifeType.inputFor(state.themeName)
                )
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val columns = if (maxWidth >= 330.dp && LocalDensity.current.fontScale <= 1.2f) 5 else 3
                    Column {
                        fields.chunked(columns).forEach { row ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                row.forEach { (field, value) ->
                                    TextButton(
                                        { editingColor = field }, enabled = editable, modifier = Modifier.weight(1f),
                                        contentPadding = PaddingValues(horizontal = 2.dp, vertical = 8.dp)
                                    ) {
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            ColorSwatch(value)
                                            Text(field.label, style = LifeText.caption, modifier = Modifier.padding(top = 6.dp))
                                        }
                                    }
                                }
                                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                    }
                }
                TextButton({ onPreset(ThemePreset.ORIGINAL) }, enabled = editable) { Text("还原为默认主题") }
            }
        }
        state.validationError?.let { Text(it, style = LifeText.caption, color = MaterialTheme.colorScheme.error) }
        state.message?.takeUnless { state.saveEvent != 0L }?.let {
            Text(it, Modifier.padding(top = 10.dp), style = LifeText.caption, color = colors.inkSecondary)
        }
    }
    editingColor?.let { field ->
        LocalColorEditor(field.label, fields.first { it.first == field }.second,
            onColor = { onField(field, it) }, onDismiss = { editingColor = null })
    }
    if (showLibrary) ThemeLibraryDialog(
        selectedId = state.selectedId,
        preview = state.preview,
        savedThemes = namedThemes,
        enabled = editable,
        onChoose = { id -> ThemePreset.fromId(id)?.let(onPreset) ?: onNamed(id) },
        onDelete = onDelete,
        onDismiss = { showLibrary = false }
    )
    if (discard) AlertDialog(
        onDismissRequest = { discard = false },
        title = { Text("舍弃主题草稿？") },
        text = { Text("离开后会恢复已保存的配色和背景效果。") },
        confirmButton = { TextButton(onCancel) { Text("舍弃草稿") } },
        dismissButton = { TextButton({ discard = false }) { Text("继续编辑") } }
    )
}

@Composable
private fun AppearanceGroup(title: String, subtitle: String, content: @Composable ColumnScope.() -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val reduced = reducedLifeMotion()
    SettingsLine(title, subtitle, trailing = if (expanded) "收起" else "展开") { expanded = !expanded }
    AnimatedVisibility(expanded, enter = fadeIn(tween(if (reduced) 0 else 170)), exit = fadeOut(tween(if (reduced) 0 else 120))) {
        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), content = content)
    }
}

@Composable
private fun ThemeLibraryDialog(
    selectedId: String,
    preview: AppearanceSettings,
    savedThemes: List<NamedTheme>,
    enabled: Boolean,
    onChoose: (String) -> Unit,
    onDelete: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val colors = LocalLifeOsColors.current
    val library = ThemePreset.entries.map { NamedTheme(it.id, it.displayName,
        CustomTheme(it.accent, it.paper, it.secondary, it.soft, it.warm)) } + savedThemes
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(24.dp), color = colors.paper, modifier = Modifier.widthIn(max = 480.dp)) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("更多主题色", Modifier.weight(1f), style = LifeText.title, color = colors.ink)
                    TextButton(onDismiss) { Text("完成", style = LifeText.caption) }
                }
                library.chunked(2).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { theme ->
                            val chosen = theme.id == selectedId
                            Column(Modifier.weight(1f)) {
                                Surface(
                                    onClick = { onChoose(theme.id) }, enabled = enabled,
                                    modifier = Modifier.fillMaxWidth().semantics { selected = chosen },
                                    shape = RoundedCornerShape(12.dp), color = colors.card,
                                    border = BorderStroke(1.dp, if (chosen) colors.accent else colors.line)
                                ) {
                                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                        Text(theme.name, style = LifeText.caption, color = colors.ink)
                                        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                            with(theme.colors) {
                                                listOf(accent, paper, secondary, soft, warm).forEach {
                                                    ColorSwatch(it, Modifier.weight(1f).height(16.dp))
                                                }
                                            }
                                        }
                                    }
                                }
                                if (theme.id.startsWith("palette-")) TextButton({ onDelete(theme.id) }, enabled = enabled) {
                                    Text("移出主题集", style = LifeText.caption)
                                }
                            }
                        }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
                Text("应用预览", style = LifeText.caption, color = colors.muted)
                AppearanceLivePreviews(preview, sideBySide = true)
            }
        }
    }
}

@Composable
private fun PaletteInk(palette: CustomTheme, reduced: Boolean, content: @Composable () -> Unit) {
    val progress = remember { Animatable(1f) }
    var previous by remember { mutableStateOf(palette) }
    var from by remember { mutableStateOf(palette) }
    LaunchedEffect(palette, reduced) {
        val changed = palette != previous
        if (changed) {
            from = previous
            previous = palette
        }
        if (!changed || reduced) progress.snapTo(1f)
        else {
            progress.snapTo(0f)
            progress.animateTo(1f, tween(620))
        }
    }
    Box(Modifier.clipToBounds()) {
        content()
        val ink = remember(from) {
            with(from) { listOf(accent, paper, secondary, soft, warm).map { Color(0xFF000000L or it.substring(1).toLong(16)) } }
        }
        if (!reduced) Canvas(Modifier.matchParentSize()) {
            val step = size.width / ink.size
            val height = 32.dp.toPx().coerceAtMost(size.height)
            ink.forEachIndexed { index, color ->
                val reveal = ((progress.value - index * .055f) / .78f).coerceIn(0f, 1f)
                if (reveal < 1f) drawRoundRect(
                    color = color.copy(alpha = (1f - reveal) * .78f),
                    topLeft = Offset(step * (index + reveal * .78f) + 4.dp.toPx(), (size.height - height) / 2),
                    size = Size((step - 8.dp.toPx()).coerceAtLeast(0f) * (1f - reveal), height),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(7.dp.toPx())
                )
            }
        }
    }
}

@Composable
private fun SettingsSaveStamp(event: Long, reduced: Boolean) {
    var consumed by rememberSaveable { mutableLongStateOf(0L) }
    var visible by remember { mutableStateOf(false) }
    val scale = remember { Animatable(1f) }
    LaunchedEffect(event, reduced) {
        if (event == 0L || event <= consumed) return@LaunchedEffect
        consumed = event
        visible = true
        if (!reduced) {
            scale.snapTo(1.12f)
            scale.animateTo(1f, tween(220))
        }
        delay(700)
        visible = false
    }
    AnimatedVisibility(visible, enter = fadeIn(tween(if (reduced) 0 else 120)), exit = fadeOut(tween(if (reduced) 0 else 120))) {
        val colors = LocalLifeOsColors.current
        Surface(
            modifier = Modifier.padding(top = 4.dp).graphicsLayer {
                scaleX = if (reduced) 1f else scale.value
                scaleY = scaleX
                rotationZ = if (reduced) 0f else -5f
            },
            shape = RoundedCornerShape(8.dp), color = colors.paper,
            border = BorderStroke(1.dp, colors.accent)
        ) { Text("主题已保存", Modifier.padding(horizontal = 9.dp, vertical = 4.dp), style = LifeText.caption, color = colors.accentDeep) }
    }
}
