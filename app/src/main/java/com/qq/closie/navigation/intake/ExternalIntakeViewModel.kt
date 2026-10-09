package com.qq.closie.navigation.intake

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.qq.closie.navigation.ExternalNavCommand
import com.qq.closie.life.capture.CaptureSource
import com.qq.closie.life.repository.CaptureRepository
import com.qq.closie.navigation.LifeOsRoute
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class IntakeNavigation(val route: String, val externalNonce: Long? = null)

/** Async intake orchestration has one lifecycle owner; navigation only handles results. */
class ExternalIntakeViewModel(
    private val coordinator: ExternalIntakeCoordinator,
    private val captures: CaptureRepository
) : ViewModel() {
    private val _navigation = MutableStateFlow<IntakeNavigation?>(null)
    val navigation = _navigation.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()
    private var activeNonce: Long? = null
    private var generation = 0L
    fun supersede() {
        generation++
        activeNonce = null
        _navigation.value = null
        _error.value = null
    }
    fun accept(command: ExternalNavCommand) {
        if (activeNonce == command.nonce) return
        activeNonce = command.nonce
        perform(command.nonce) { coordinator.intake(command) }
    }
    fun paste(text: String, asLink: Boolean) {
        if (text.isBlank()) { _error.value = "剪贴板没有可保存的文字"; return }
        perform {
            val id = UUID.randomUUID().toString()
            val url = if (asLink) com.qq.closie.life.capture.ShareRouter.urlIn(text) else null
            captures.create(id, CaptureSource.CLIPBOARD, rawText = text, sourceUrl = url)
            if (url != null) coordinator.fileToLibrary(id) else LifeOsRoute.capture(id)
        }
    }
    fun fileToLibrary(id: String) = perform { coordinator.fileToLibrary(id) }
    fun acknowledge(result: IntakeNavigation) { if (_navigation.value == result) _navigation.value = null }
    fun clearError() { _error.value = null }
    private fun perform(nonce: Long? = null, action: suspend () -> String) {
        val operation = ++generation
        _error.value = null
        viewModelScope.launch {
            try {
                val route = action()
                // A newly received external command takes precedence over an older completion.
                if (operation == generation) _navigation.value = IntakeNavigation(route, nonce)
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) {
                if (operation == generation) {
                    _error.value = "内容尚未保存，请重试"
                    if (activeNonce == nonce) activeNonce = null
                }
            }
        }
    }
}
