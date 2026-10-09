package com.qq.closie.navigation

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel

/** Keeps pending/consumed status across recreation, rather than replaying Activity.intent. */
class ExternalCommandViewModel(private val saved: SavedStateHandle) : ViewModel() {
    val command = saved.getStateFlow<ExternalNavCommand?>("pendingExternal", null)
    fun receiveInitial(action: String?, text: String?, editId: String?, openAdd: Boolean) {
        if (saved.get<Boolean>("initialReceived") == true) return
        saved["initialReceived"] = true
        receive(action, text, editId, openAdd)
    }
    fun receive(action: String?, text: String?, editId: String?, openAdd: Boolean) {
        ExternalCommandResolver.parse(action, text, editId, openAdd, System.nanoTime())?.let {
            saved["pendingExternal"] = it
        }
    }
    fun consume(nonce: Long) {
        if (command.value?.nonce == nonce) saved["pendingExternal"] = null
    }
}
