package com.qq.closie.ui.lifeos.settings

import com.qq.closie.data.appearance.*

enum class ThemeField(val label: String) { ACCENT("交互主色"), PAPER("纸张色"), SECONDARY("冷调层次"), SOFT("柔和选区"), WARM("暖调点缀") }

/** Unsaved edits never reach DataStore. Invalid input preserves the last valid live preview. */
data class AppearanceEditorState(
    val selectedId: String,
    val accent: String, val paper: String, val secondary: String,
    val preview: AppearanceSettings,
    val validationError: String? = null,
    val isSaving: Boolean = false,
    val message: String? = null,
    val soft: String = secondary, val warm: String = paper
) {
    fun edit(field: ThemeField, text: String): AppearanceEditorState {
        val changed = when (field) {
            ThemeField.ACCENT -> copy(accent = text)
            ThemeField.PAPER -> copy(paper = text)
            ThemeField.SECONDARY -> copy(secondary = text)
            ThemeField.SOFT -> copy(soft = text)
            ThemeField.WARM -> copy(warm = text)
        }
        val custom = runCatching { CustomTheme(changed.accent, changed.paper, changed.secondary, changed.soft, changed.warm).normalized() }.getOrNull()
        return changed.copy(selectedId = AppearanceSettings.CUSTOM_ID,
            preview = custom?.let { preview.copy(selectedThemeId = AppearanceSettings.CUSTOM_ID, custom = it) } ?: preview,
            validationError = if (custom == null) "颜色请填写六位 HEX，例如 #6F67A8" else null, message = null)
    }

    companion object {
        fun from(settings: AppearanceSettings): AppearanceEditorState {
            val c = settings.selectedColors()
            return AppearanceEditorState(settings.selectedThemeId, c.accent, c.paper, c.secondary, settings, soft = c.soft, warm = c.warm)
        }
    }
}
