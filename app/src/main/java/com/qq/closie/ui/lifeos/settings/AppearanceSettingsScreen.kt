package com.qq.closie.ui.lifeos.settings

import androidx.compose.foundation.BorderStroke
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qq.closie.data.appearance.*
import com.qq.closie.ui.lifeos.components.ColorSwatch
import com.qq.closie.ui.lifeos.components.LifePage
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
    editor?.let { draft ->
        AppearanceSettingsScreen(draft, viewModel::previewPreset, viewModel::editThemeField,
            onSave = { viewModel.saveAppearance() },
            onCancel = { viewModel.cancelAppearanceEdit(); onBack() },
            onRestore = viewModel::restoreOriginal,
            functionPageStyle = stored?.functionPageStyle ?: FunctionPageStyle.LAYERED,
            onFunctionPageStyle = viewModel::selectFunctionPageStyle,
            namedThemes = stored?.namedThemes.orEmpty(), onNamed = viewModel::previewNamed, onDelete = viewModel::deleteNamed,
            onEffects = viewModel::previewFunctionEffects, onEffectsFinished = viewModel::saveFunctionEffects,
            onName = viewModel::nameTheme, onDuplicate = viewModel::duplicateTheme,
            onImage = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) })
    }
}

@Composable
fun AppearanceSettingsScreen(
    state: AppearanceEditorState,
    onPreset: (ThemePreset) -> Unit,
    onField: (ThemeField, String) -> Unit,
    onSave: () -> Unit, onCancel: () -> Unit, onRestore: () -> Unit,
    functionPageStyle: FunctionPageStyle = FunctionPageStyle.LAYERED,
    onFunctionPageStyle: (FunctionPageStyle) -> Unit = {},
    namedThemes: List<NamedTheme> = emptyList(), onNamed: (String) -> Unit = {}, onDelete: (String) -> Unit = {},
    onEffects: (FunctionPageEffects) -> Unit = {}, onEffectsFinished: () -> Unit = {},
    onName: (String) -> Unit = {}, onImage: () -> Unit = {}, onDuplicate: () -> Unit = {}
) {
    val colors = LocalLifeOsColors.current
    LifePage("主题与配色", onCancel) {
        Text("从喜欢的画面开始", style = LifeText.journal)
        OutlinedButton(onImage, enabled = !state.isSaving && !state.extracting) { Text(if (state.extracting) "正在本机取色…" else "从照片或截图取色") }
        Text("只保存配色和名称，不保存原图。取色后可预览、调整，再收进主题集。", style = LifeText.caption, color = colors.muted)
        Text("功能页", style = LifeText.title)
        Column {
            FunctionPageStyle.entries.forEach { style ->
                FilterChip(functionPageStyle == style, { onFunctionPageStyle(style) }, label = { Text(style.label) },
                    enabled = !state.isSaving && !state.extracting)
            }
        }
        Text(functionPageStyle.description, style = LifeText.caption, color = colors.muted)
        Text("样式即时保存；系统关闭动画时不使用缩放和视差。", style = LifeText.caption, color = colors.muted)
        FunctionEffectControls(state.preview.functionEffects, onEffects, onEffectsFinished,
            enabled = !state.isSaving && !state.extracting)
        Text("给日子换一种颜色", style = LifeText.title)
        Text("四份画里的灵感，还有最初的暮山紫。照片保留原来的颜色。", Modifier.padding(vertical = 12.dp), style = LifeText.body, color = colors.muted)
        val library = ThemePreset.entries.map { NamedTheme(it.id, it.displayName,
            CustomTheme(it.accent, it.paper, it.secondary, it.soft, it.warm)) } + namedThemes
        library.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { theme ->
                    val chosen = state.selectedId == theme.id
                    Surface(onClick = { ThemePreset.fromId(theme.id)?.let(onPreset) ?: onNamed(theme.id) },
                        enabled = !state.isSaving && !state.extracting, modifier = Modifier.weight(1f).semantics { selected = chosen },
                        color = colors.card, shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(if (chosen) 2.dp else 1.dp, if (chosen) colors.accent else colors.line)) {
                        Column(Modifier.padding(10.dp)) {
                            Text(theme.name, style = LifeText.body)
                            Text(if (chosen) "已选" else "试试这份", style = LifeText.caption)
                            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                                with(theme.colors) { listOf(accent, paper, secondary, soft, warm).forEach {
                                    ColorSwatch(it, Modifier.weight(1f))
                                } }
                            }
                            if (theme.id.startsWith("palette-")) TextButton({ onDelete(theme.id) },
                                enabled = !state.isSaving && !state.extracting) { Text("删除", style = LifeText.caption) }
                        }
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        Surface(Modifier.fillMaxWidth().padding(vertical = 16.dp), color = colors.paperSecondary, shape = RoundedCornerShape(12.dp)) {
            Column(Modifier.padding(18.dp)) {
                Text("实时试色", style = LifeText.caption, color = colors.muted)
                Text("今天，也值得收好。", Modifier.padding(vertical = 10.dp), style = LifeText.title)
                Text("给自己留一句很轻的话。", style = LifeText.body, color = colors.inkSecondary)
                Text("正文与边线会随配色调整。", Modifier.padding(top = 10.dp), style = LifeText.caption, color = colors.muted)
            }
        }
        Text("自定义主题色", style = LifeText.title)
        if (namedThemes.any { it.id == state.selectedId }) {
            TextButton(onDuplicate, enabled = !state.isSaving && !state.extracting) { Text("另存为新主题") }
        }
        OutlinedTextField(state.themeName, onName, Modifier.fillMaxWidth(), label = { Text("主题名称") }, singleLine = true,
            enabled = !state.isSaving && !state.extracting)
        var editingColor by remember { mutableStateOf<ThemeField?>(null) }
        val fields = listOf(ThemeField.ACCENT to state.accent, ThemeField.PAPER to state.paper,
            ThemeField.SECONDARY to state.secondary, ThemeField.SOFT to state.soft, ThemeField.WARM to state.warm)
        fields.forEach { (field, value) ->
            TextButton({ editingColor = field }, enabled = !state.isSaving && !state.extracting,
                modifier = Modifier.fillMaxWidth()) {
                ColorSwatch(value); Text(field.label, Modifier.weight(1f).padding(start = 12.dp))
                Text(value, style = LifeText.caption)
            }
        }
        editingColor?.let { field ->
            LocalColorEditor(field.label, fields.first { it.first == field }.second,
                onColor = { onField(field, it) }, onDismiss = { editingColor = null })
        }
        Text(state.validationError ?: "文字和按钮会自动调整，以保持可读。", style = LifeText.caption, color = colors.inkSecondary)
        state.message?.let { Text(it, Modifier.padding(top = 10.dp), style = LifeText.body) }
        Button(onClick = onSave, enabled = !state.isSaving && !state.extracting && state.validationError == null && state.themeName.isNotBlank(), modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
            Text(if (state.isSaving) "正在保存…" else if (state.editingNamedId != null) "保存主题修改" else if (state.selectedId == AppearanceSettings.CUSTOM_ID) "保存为新主题" else "使用当前主题")
        }
        OutlinedButton(onClick = onCancel, enabled = !state.isSaving && !state.extracting, modifier = Modifier.fillMaxWidth()) { Text("取消试色") }
        TextButton(onClick = onRestore, enabled = !state.isSaving && !state.extracting, modifier = Modifier.fillMaxWidth()) { Text("恢复暮山紫原色") }
    }
}

@Preview(name = "Appearance · PreviewOnly", showBackground = true)
@Composable
private fun AppearanceSettingsPreview() {
    LifeOsTheme { AppearanceSettingsScreen(AppearanceEditorState.from(AppearanceSettings()), {}, { _, _ -> }, {}, {}, {}) }
}
