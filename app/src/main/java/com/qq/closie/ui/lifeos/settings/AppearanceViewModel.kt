package com.qq.closie.ui.lifeos.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.qq.closie.data.appearance.AppearanceRepository
import com.qq.closie.data.appearance.AppearanceSettings
import com.qq.closie.data.appearance.CustomTheme
import com.qq.closie.data.appearance.ThemePreset
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Appearance owns its saved preferences and temporary editor; only Save persists edits. */
class AppearanceViewModel(
    private val appearanceRepository: AppearanceRepository
) : ViewModel() {
    private val _editor = MutableStateFlow<AppearanceEditorState?>(null)
    val editor = _editor.asStateFlow()
    private var editGeneration = 0
    val savedAppearance = appearanceRepository.settings
    val appearance = combine(savedAppearance, editor) { stored, draft ->
        stored?.let { draft?.preview ?: it }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    fun beginAppearanceEdit() {
        editGeneration++
        _editor.value = AppearanceEditorState.from(appearanceRepository.settings.value ?: AppearanceSettings())
    }

    fun cancelAppearanceEdit() { editGeneration++; _editor.value = null }

    fun previewPreset(preset: ThemePreset) {
        if (_editor.value == null || _editor.value?.isSaving == true) return
        val saved = appearanceRepository.settings.value ?: AppearanceSettings()
        _editor.value = AppearanceEditorState.from(saved.copy(selectedThemeId = preset.id))
    }

    fun previewSavedCustom() {
        if (_editor.value == null || _editor.value?.isSaving == true) return
        val saved = appearanceRepository.settings.value ?: return
        if (saved.custom != null) _editor.value = AppearanceEditorState.from(saved.copy(selectedThemeId = AppearanceSettings.CUSTOM_ID))
    }

    fun editThemeField(field: ThemeField, value: String) {
        _editor.value?.takeUnless { it.isSaving }?.let { _editor.value = it.edit(field, value) }
    }

    fun restoreOriginal() = previewPreset(ThemePreset.ORIGINAL)

    fun saveAppearance() {
        val draft = _editor.value ?: return
        if (draft.isSaving || draft.validationError != null) return
        val generation = editGeneration
        _editor.value = draft.copy(isSaving = true, message = null)
        viewModelScope.launch {
            runCatching {
                when {
                    draft.selectedId == AppearanceSettings.CUSTOM_ID -> appearanceRepository.saveCustom(CustomTheme(draft.accent, draft.paper, draft.secondary))
                    else -> appearanceRepository.selectPreset(requireNotNull(ThemePreset.fromId(draft.selectedId)))
                }
                appearanceRepository.read()
            }.onSuccess { saved ->
                if (generation == editGeneration && _editor.value != null) {
                    _editor.value = AppearanceEditorState.from(saved).copy(message = "主题已保存")
                }
            }.onFailure {
                if (generation == editGeneration && _editor.value != null) {
                    _editor.value = draft.copy(isSaving = false, message = "主题保存失败，请重试")
                }
            }
        }
    }

    class Factory(private val repository: AppearanceRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(AppearanceViewModel::class.java))
            return AppearanceViewModel(repository) as T
        }
    }
}
