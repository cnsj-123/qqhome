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
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qq.closie.data.appearance.*
import com.qq.closie.life.ui.theme.BundledText as Text
import com.qq.closie.life.ui.theme.LifeType
import com.qq.closie.ui.lifeos.components.ColorSwatch
import com.qq.closie.ui.lifeos.components.reducedLifeMotion
import com.qq.closie.ui.lifeos.components.LifeSurfaceOrigin
import com.qq.closie.ui.lifeos.components.lifeSurfaceOrigin
import com.qq.closie.ui.lifeos.theme.*

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
    var colorOrigin by remember { mutableStateOf<LifeSurfaceOrigin?>(null) }
    var libraryOrigin by remember { mutableStateOf<LifeSurfaceOrigin?>(null) }
    var inkEvent by rememberSaveable { mutableLongStateOf(0L) }
    val colorOrigins = remember { mutableMapOf<ThemeField, LifeSurfaceOrigin>() }
    val orientation = LocalConfiguration.current.orientation
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
    Box(Modifier.fillMaxSize()) {
    SettingsPaperPage("主题", back, actions = {
        if (dirty && !state.isSaving) Text("未保存", Modifier.padding(end = 8.dp), style = SettingsText.detail, color = colors.accentDeep)
        FilledTonalButton(
            onSave,
            modifier = Modifier.height(28.dp), shape = RoundedCornerShape(10.dp),
            enabled = dirty && editable && state.validationError == null && state.themeName.isNotBlank(),
            contentPadding = PaddingValues(horizontal = 11.dp, vertical = 0.dp)
        ) { Text(if (state.isSaving) "保存中" else "保存主题", style = SettingsText.tiny) }
    }) {
        Text("主题与配色", style = SettingsText.caption, color = colors.inkSecondary)
        Column(Modifier.padding(top = 24.dp, bottom = 24.dp)) {
            SettingsPaletteSeal(state.saveEvent, palette, reduced) { collapse ->
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val compact = maxWidth < 320.dp || LocalDensity.current.fontScale > 1.2f
                    @Composable fun Swatches() {
                        Row(Modifier.width(205.dp)) {
                            fields.forEachIndexed { index, (field, value) ->
                                Column(Modifier.width(41.dp).height(58.dp).graphicsLayer {
                                    translationX = (maxWidth.value / 2f - (index * 41f + 17f)).dp.toPx() * collapse
                                    scaleX = 1f - collapse; scaleY = scaleX; alpha = 1f - collapse
                                }.clickable(enabled = editable) { colorOrigin = colorOrigins[field]; editingColor = field },
                                    horizontalAlignment = Alignment.Start) {
                                    SettingsColorSwatch(value, Modifier.size(34.dp).lifeSurfaceOrigin(orientation) { colorOrigins[field] = it })
                                    Text(when (field) { ThemeField.ACCENT -> "主"; ThemeField.PAPER -> "纸"; ThemeField.SECONDARY -> "辅助"; ThemeField.SOFT -> "柔和"; ThemeField.WARM -> "暖" },
                                        Modifier.width(34.dp).padding(top = 4.dp), style = SettingsText.detail, color = colors.inkSecondary, textAlign = TextAlign.Center)
                                }
                            }
                        }
                    }
                    @Composable fun PaletteName() {
                        Column(Modifier.padding(top = 1.dp)) {
                            Text(title, style = SettingsText.body, color = colors.accentDeep)
                            Text("点按色块进行编辑", Modifier.padding(top = 4.dp), style = SettingsText.detail, color = colors.inkSecondary)
                        }
                    }
                    if (compact) Column { Swatches(); PaletteName() }
                    else Row(horizontalArrangement = Arrangement.spacedBy(13.dp), verticalAlignment = Alignment.Top) { Swatches(); PaletteName() }
                }
            }
            OutlinedButton({ showLibrary = true }, enabled = editable,
                modifier = Modifier.height(34.dp).lifeSurfaceOrigin(orientation) { libraryOrigin = it }, shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 13.dp, vertical = 0.dp)) { Text("更多主题色", style = SettingsText.caption) }
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
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).clickable { showCustom = !showCustom }) {
                Text("自定义主题色", style = SettingsText.body, color = colors.ink)
                Text(if (showCustom) "主色 / 纸色 / 辅助 / 柔和 / 暖色" else "默认收起 · 主色 / 纸色 / 辅助 / 柔和 / 暖色",
                    style = SettingsText.detail, color = colors.inkSecondary)
            }
            OutlinedButton(onImage, enabled = editable, modifier = Modifier.height(34.dp), shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)) {
                Text(if (state.extracting) "正在取色…" else "从照片取色", style = SettingsText.caption)
            }
        }
        AnimatedVisibility(showCustom, enter = fadeIn(tween(150)), exit = fadeOut(tween(150))) {
            Column {
                Row(Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("主题名称", Modifier.weight(1f), style = SettingsText.body, color = colors.inkSecondary)
                    BasicTextField(state.themeName, onName, Modifier.weight(1f), singleLine = true, enabled = editable,
                        textStyle = SettingsText.body.copy(color = colors.ink, textAlign = TextAlign.End))
                }
                HorizontalDivider(color = colors.line)
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val columns = if (maxWidth >= 330.dp && LocalDensity.current.fontScale <= 1.2f) 5 else 3
                    Column {
                        fields.chunked(columns).forEach { row ->
                            Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                row.forEach { (field, value) ->
                                    Column(Modifier.width(55.dp).clickable(enabled = editable) { colorOrigin = colorOrigins[field]; editingColor = field },
                                        horizontalAlignment = Alignment.CenterHorizontally) {
                                        SettingsColorSwatch(value, Modifier.size(55.dp).lifeSurfaceOrigin(orientation) { colorOrigins[field] = it }, large = true)
                                        Text(field.shortLabel(), style = SettingsText.detail, modifier = Modifier.padding(top = 6.dp), color = colors.ink)
                                        Text(value.removePrefix("#").uppercase(), style = SettingsText.tiny.copy(fontSize = 9.sp), color = colors.inkSecondary)
                                    }
                                }
                                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                    }
                }
                Text("点色块编辑 HSV / HEX · 文字与按钮自动调整以保持可读", Modifier.padding(top = 12.dp), style = SettingsText.detail, color = colors.inkSecondary)
                TextButton(onDuplicate, enabled = editable) { Text("复制当前主题", style = SettingsText.caption) }
                TextButton({ onPreset(ThemePreset.ORIGINAL) }, enabled = editable) { Text("还原为默认主题") }
            }
        }
        state.validationError?.let { Text(it, style = LifeText.caption, color = MaterialTheme.colorScheme.error) }
        state.message?.takeUnless { it == "主题已保存" }?.let {
            Text(it, Modifier.padding(top = 10.dp), style = LifeText.caption, color = colors.inkSecondary)
        }
    }
    SettingsInkWipe(inkEvent, palette, reduced, Modifier.matchParentSize())
    }
    editingColor?.let { field ->
        LocalColorEditor(field.shortLabel(), fields.first { it.first == field }.second, origin = colorOrigin,
            roles = fields, activeRole = field, onColors = { selected -> selected.forEach { (role, hex) -> onField(role, hex) } },
            onColor = { onField(field, it) }, onDismiss = { editingColor = null })
    }
    if (showLibrary) ThemeLibraryDialog(
        selectedId = state.selectedId,
        preview = state.preview,
        savedThemes = namedThemes,
        enabled = editable,
        onChoose = { id -> ThemePreset.fromId(id)?.let(onPreset) ?: onNamed(id); inkEvent++ },
        onDelete = onDelete,
        onDismiss = { showLibrary = false }, origin = libraryOrigin, inkEvent = inkEvent
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
    val unfold by animateFloatAsState(if (expanded) 1f else 0f,
        if (reduced) tween(150) else spring(dampingRatio = .6f, stiffness = 420f), label = "settings-unfold")
    SettingsLine(title, subtitle, trailing = if (expanded) "收起" else "展开") { expanded = !expanded }
    AnimatedVisibility(expanded,
        enter = fadeIn(tween(150)) + if (reduced) androidx.compose.animation.EnterTransition.None else expandVertically(spring(dampingRatio = .6f, stiffness = 420f)),
        exit = fadeOut(tween(150)) + if (reduced) androidx.compose.animation.ExitTransition.None else shrinkVertically(spring(dampingRatio = .6f, stiffness = 420f))) {
        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp).graphicsLayer {
            transformOrigin = TransformOrigin(.5f, 0f)
            rotationX = if (reduced) 0f else 2f * (1f - unfold)
        }, content = content)
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
    onDismiss: () -> Unit,
    origin: LifeSurfaceOrigin?,
    inkEvent: Long
) {
    val colors = LocalLifeOsColors.current
    val reduced = reducedLifeMotion()
    val library = ThemePreset.entries.map { NamedTheme(it.id, it.displayName,
        CustomTheme(it.accent, it.paper, it.secondary, it.soft, it.warm)) } + savedThemes
    SettingsOriginDialog(origin, onDismiss) { _ ->
        Box {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(start = 28.dp, end = 28.dp, top = 46.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                library.chunked(2).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        row.forEach { theme ->
                            val chosen = theme.id == selectedId
                            Column(Modifier.weight(1f)) {
                                Surface(onClick = { onChoose(theme.id) }, enabled = enabled,
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp).semantics { selected = chosen },
                                    shape = RoundedCornerShape(15.dp), color = colors.paper,
                                    shadowElevation = 2.dp,
                                    border = BorderStroke(if (chosen) 1.4.dp else 1.dp,
                                        if (chosen) colors.accent else colors.accent.copy(alpha = .18f))) {
                                    Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                        Text(theme.name, style = SettingsText.caption, color = colors.ink)
                                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                            with(theme.colors) {
                                                listOf(accent, paper, secondary, soft, warm).forEach {
                                                    SettingsColorSwatch(it, Modifier.weight(1f).aspectRatio(1f), corner = 4.dp, edge = 0.dp, elevation = 1.dp)
                                                }
                                            }
                                        }
                                    }
                                }
                                if (theme.id.startsWith("palette-")) TextButton({ onDelete(theme.id) }, enabled = enabled) {
                                    Text("移出主题集", style = SettingsText.caption)
                                }
                            }
                        }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("应用预览", style = SettingsText.caption, color = colors.ink)
                    Text("首页 / 功能页", style = SettingsText.detail, color = colors.inkSecondary)
                }
                AppearanceLivePreviews(preview, sideBySide = true, compact = true)
            }
            SettingsInkWipe(inkEvent, preview.selectedColors(), reduced, Modifier.matchParentSize())
        }
    }
}
