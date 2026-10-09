package com.qq.closie.life.ui.capture

import androidx.lifecycle.ViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.qq.closie.life.capture.CaptureItemEntity
import com.qq.closie.life.capture.CaptureSource
import com.qq.closie.life.repository.CaptureRepository
import com.qq.closie.life.repository.MediaRepository
import com.qq.closie.navigation.LifeOsRoute
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class CaptureDetailState(
    val record: CaptureItemEntity? = null, val loaded: Boolean = false,
    val editing: Boolean = false, val title: String = "", val note: String = "",
    val mediaPath: String? = null, val busy: Boolean = false,
    val exit: Boolean = false, val error: String? = null
)

/** User annotations stay separate from captured evidence. Cancelling a new editor writes nothing. */
class CaptureDetailViewModel(
    private val id: String, private val captures: CaptureRepository, private val media: MediaRepository,
    private val saved: SavedStateHandle = SavedStateHandle()
) : ViewModel() {
    val isNew = id == LifeOsRoute.NEW_ID
    private val _state = MutableStateFlow(CaptureDetailState(editing = isNew))
    val state = _state.asStateFlow()
    init {
        viewModelScope.launch {
            try {
                val recordId = saved.get<String>("createdCaptureId") ?: id.takeUnless { isNew }
                val record = recordId?.let { captures.getById(it) }
                val path = record?.primaryMediaAssetId?.let { media.managedImagePathFor(it) }
                _state.value = _state.value.copy(record = record, mediaPath = path, loaded = true,
                    editing = isNew && record == null)
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { _state.value = _state.value.copy(error = "记录暂时无法读取") }
        }
    }
    fun title(text: String) { _state.value = _state.value.copy(title = text) }
    fun note(text: String) { _state.value = _state.value.copy(note = text) }
    fun edit() {
        val s = _state.value
        _state.value = s.copy(editing = true, title = s.record?.displayTitle.orEmpty(), note = s.record?.note.orEmpty())
    }
    fun cancel() { _state.value = _state.value.copy(editing = false, exit = isNew && _state.value.record == null) }
    fun save() {
        val draft = _state.value
        if (draft.busy) return
        if (draft.record == null && draft.title.isBlank() && draft.note.isBlank()) { cancel(); return }
        mutate {
            val recordId = draft.record?.id ?: UUID.randomUUID().toString()
            captures.withTransaction {
                if (draft.record == null) check(captures.create(recordId, CaptureSource.MANUAL))
                check(captures.updateUserFields(recordId, draft.title, draft.note))
            }
            if (isNew) saved["createdCaptureId"] = recordId
            _state.value = _state.value.copy(record = captures.getById(recordId), editing = false)
        }
    }
    private fun mutate(block: suspend () -> Unit) {
        if (_state.value.busy) return
        _state.value = _state.value.copy(busy = true, error = null)
        viewModelScope.launch {
            try { block() }
            catch (error: CancellationException) { throw error }
            catch (_: Exception) { _state.value = _state.value.copy(error = "未能保存，请重试") }
            finally { _state.value = _state.value.copy(busy = false) }
        }
    }
}
