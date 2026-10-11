package com.qq.closie.ui.lifeos.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.qq.closie.data.appearance.AppearanceRepository
import com.qq.closie.data.appearance.AppearanceSettings
import com.qq.closie.data.appearance.CustomTheme
import com.qq.closie.data.appearance.ThemePreset
import com.qq.closie.data.appearance.FunctionPageStyle
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
        _editor.value = savedEditor(appearanceRepository.settings.value ?: AppearanceSettings())
    }

    fun cancelAppearanceEdit() { editGeneration++; _editor.value = null }

    fun previewPreset(preset: ThemePreset) {
        if (_editor.value == null || _editor.value?.isSaving == true) return
        val saved = appearanceRepository.settings.value ?: AppearanceSettings()
        _editor.value = AppearanceEditorState.from(saved.copy(selectedThemeId = preset.id))
    }

    fun editThemeField(field: ThemeField, value: String) {
        _editor.value?.takeUnless { it.isSaving }?.let { _editor.value = it.edit(field, value) }
    }

    fun restoreOriginal() = previewPreset(ThemePreset.ORIGINAL)

    fun previewNamed(id: String) {
        if (_editor.value == null || _editor.value?.isSaving == true || _editor.value?.extracting == true) return
        val saved = savedAppearance.value ?: return
        if (saved.namedThemes.none { it.id == id }) return
        _editor.value = AppearanceEditorState.from(saved.copy(selectedThemeId = id))
    }

    fun duplicateTheme() {
        _editor.value?.takeUnless { it.isSaving || it.extracting }?.let {
            _editor.value = it.copy(selectedId = AppearanceSettings.CUSTOM_ID,
                themeName = (it.themeName.take(35) + " · 副本"), message = "保存后会新增一份主题")
        }
    }

    // Old CUSTOM means an already saved palette, not an unsaved palette to append on Apply.
    private fun savedEditor(saved: AppearanceSettings): AppearanceEditorState {
        val selected = if (saved.selectedThemeId == AppearanceSettings.CUSTOM_ID &&
            saved.namedThemes.any { it.id == "legacy-custom" }) saved.copy(selectedThemeId = "legacy-custom") else saved
        return AppearanceEditorState.from(selected)
    }

    fun nameTheme(value: String) {
        _editor.value?.takeUnless { it.isSaving }?.let {
            _editor.value = it.copy(themeName = value.take(40), selectedId = AppearanceSettings.CUSTOM_ID)
        }
    }

    fun deleteNamed(id: String) {
        val draft = _editor.value ?: return
        if (draft.isSaving || draft.extracting) return
        val generation = editGeneration
        _editor.value = draft.copy(isSaving = true)
        viewModelScope.launch {
            try {
                appearanceRepository.deleteNamed(id)
                val saved = appearanceRepository.read()
                if (generation == editGeneration && _editor.value != null)
                    _editor.value = savedEditor(saved).copy(message = "主题已删除")
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { if (generation == editGeneration) _editor.value = draft.copy(message = "删除失败，请重试") }
        }
    }

    fun extractImage(context: android.content.Context, uri: android.net.Uri) {
        val initial = _editor.value ?: return
        if (initial.isSaving || initial.extracting) return
        val generation = editGeneration
        _editor.value = initial.copy(extracting = true, message = "正在从图片中取色…")
        viewModelScope.launch {
            try {
                val palette = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    com.qq.closie.data.appearance.themeFromImage(context, uri)
                }
                if (generation == editGeneration && _editor.value != null) {
                    val preview = (_editor.value ?: initial).preview.copy(selectedThemeId = AppearanceSettings.CUSTOM_ID, custom = palette)
                    _editor.value = AppearanceEditorState.from(preview).copy(themeName = "图片里的颜色", message = "已在本机取色，可继续调整和命名；保存后可再次使用。")
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (generation == editGeneration) _editor.value = _editor.value?.copy(extracting = false, message = error.message ?: "图片取色失败，请重试")
            }
        }
    }

    fun selectFunctionPageStyle(style: FunctionPageStyle) {
        viewModelScope.launch {
            try {
                appearanceRepository.selectFunctionPageStyle(style)
                _editor.value?.let { _editor.value = it.copy(preview = it.preview.copy(functionPageStyle = style)) }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { _editor.value = _editor.value?.copy(message = "功能页样式保存失败，请重试") }
        }
    }

    fun saveModuleOrder(ids: List<String>, done: () -> Unit, failed: (String) -> Unit) {
        viewModelScope.launch {
            try { appearanceRepository.saveModuleOrder(ids); done() }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { failed("顺序未能保存，请重试") }
        }
    }

    fun saveAppearance() {
        val draft = _editor.value ?: return
        if (draft.isSaving || draft.extracting || draft.validationError != null) return
        val generation = editGeneration
        _editor.value = draft.copy(isSaving = true, message = null)
        viewModelScope.launch {
            runCatching {
                when {
                    draft.selectedId == AppearanceSettings.CUSTOM_ID -> appearanceRepository.saveNamed(draft.themeName, CustomTheme(draft.accent, draft.paper, draft.secondary, draft.soft, draft.warm))
                    draft.preview.namedThemes.any { it.id == draft.selectedId } -> appearanceRepository.selectNamed(draft.selectedId)
                    else -> appearanceRepository.selectPreset(requireNotNull(ThemePreset.fromId(draft.selectedId)))
                }
                appearanceRepository.read()
            }.onSuccess { saved ->
                if (generation == editGeneration && _editor.value != null) {
                    _editor.value = savedEditor(saved).copy(message = "主题已保存")
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
