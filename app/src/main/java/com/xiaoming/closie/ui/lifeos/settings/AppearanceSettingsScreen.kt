package com.xiaoming.closie.ui.lifeos.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xiaoming.closie.data.appearance.*
import com.xiaoming.closie.ui.lifeos.components.ColorSwatch
import com.xiaoming.closie.ui.lifeos.components.LifePage
import com.xiaoming.closie.ui.lifeos.theme.*

@Composable
fun AppearanceSettingsRoute(viewModel: AppearanceViewModel, onBack: () -> Unit) {
    val editor by viewModel.editor.collectAsStateWithLifecycle()
    val stored by viewModel.savedAppearance.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) { viewModel.beginAppearanceEdit() }
    DisposableEffect(viewModel) { onDispose { viewModel.cancelAppearanceEdit() } }
    editor?.let { draft ->
        AppearanceSettingsScreen(draft, stored?.custom != null, viewModel::previewPreset,
            viewModel::previewSavedCustom, viewModel::editThemeField,
            onSave = { viewModel.saveAppearance() },
            onCancel = { viewModel.cancelAppearanceEdit(); onBack() },
            onRestore = viewModel::restoreOriginal)
    }
}

@Composable
fun AppearanceSettingsScreen(
    state: AppearanceEditorState,
    hasSavedCustom: Boolean,
    onPreset: (ThemePreset) -> Unit, onCustom: () -> Unit,
    onField: (ThemeField, String) -> Unit,
    onSave: () -> Unit, onCancel: () -> Unit, onRestore: () -> Unit
) {
    val colors = LocalLifeOsColors.current
    LifePage("主题与配色", onCancel) {
        Text("给日子换一种颜色", style = LifeText.title)
        Text("四份画里的灵感，还有最初的暮山紫。照片保留原来的颜色。", Modifier.padding(vertical = 12.dp), style = LifeText.body, color = colors.muted)
        ThemePreset.entries.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { preset ->
                    val chosen = state.selectedId == preset.id
                    Surface(onClick = { onPreset(preset) }, enabled = !state.isSaving,
                        modifier = Modifier.weight(1f).semantics { selected = chosen },
                        color = colors.card, shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(if (chosen) 2.dp else 1.dp, if (chosen) colors.accent else colors.line)) {
                        Column(Modifier.padding(12.dp)) {
                            Text(preset.displayName, style = LifeText.body)
                            Text(if (chosen) "已选" else "试试这份", style = LifeText.caption, color = colors.inkSecondary)
                            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                ColorSwatch(preset.accent); ColorSwatch(preset.paper); ColorSwatch(preset.secondary)
                            }
                        }
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        if (hasSavedCustom) OutlinedButton(onClick = onCustom, enabled = !state.isSaving) {
            Text(if (state.selectedId == AppearanceSettings.CUSTOM_ID) "我的配色 · 已选" else "恢复上次的我的配色")
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
        listOf(ThemeField.ACCENT to state.accent, ThemeField.PAPER to state.paper, ThemeField.SECONDARY to state.secondary).forEach { (field, value) ->
            OutlinedTextField(value, { onField(field, it) }, Modifier.fillMaxWidth().padding(vertical = 5.dp),
                label = { Text(field.label) }, leadingIcon = { ColorSwatch(value) }, singleLine = true,
                enabled = !state.isSaving, isError = HexColor.normalizeOrNull(value) == null,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters))
        }
        Text(state.validationError ?: "文字和按钮会自动调整，以保持可读。", style = LifeText.caption, color = colors.inkSecondary)
        state.message?.let { Text(it, Modifier.padding(top = 10.dp), style = LifeText.body) }
        Button(onClick = onSave, enabled = !state.isSaving && state.validationError == null, modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
            Text(if (state.isSaving) "正在保存…" else "保存当前主题")
        }
        OutlinedButton(onClick = onCancel, enabled = !state.isSaving, modifier = Modifier.fillMaxWidth()) { Text("取消试色") }
        TextButton(onClick = onRestore, enabled = !state.isSaving, modifier = Modifier.fillMaxWidth()) { Text("恢复暮山紫原色") }
    }
}

@Preview(name = "Appearance · PreviewOnly", showBackground = true)
@Composable
private fun AppearanceSettingsPreview() {
    LifeOsTheme { AppearanceSettingsScreen(AppearanceEditorState.from(AppearanceSettings()), false, {}, {}, { _, _ -> }, {}, {}, {}) }
}
