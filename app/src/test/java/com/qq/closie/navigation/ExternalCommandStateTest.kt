package com.qq.closie.navigation

import androidx.lifecycle.SavedStateHandle
import org.junit.Assert.*
import org.junit.Test

class ExternalCommandStateTest {
    @Test fun recreationDoesNotReplayConsumedActivityIntent() {
        val saved = SavedStateHandle()
        val model = ExternalCommandViewModel(saved)
        model.receiveInitial("android.intent.action.SEND", "hello", null, false)
        val command = model.command.value!!
        model.consume(command.nonce)
        val restored = ExternalCommandViewModel(SavedStateHandle(mapOf("initialReceived" to true)))
        restored.receiveInitial("android.intent.action.SEND", "hello", null, false)
        assertNull(restored.command.value)
    }
    @Test fun consumedOldCommandCannotClearANewerShare() {
        val model = ExternalCommandViewModel(SavedStateHandle())
        model.receive(null, null, null, true)
        val old = model.command.value!!
        model.receive("android.intent.action.SEND", "new text", null, false)
        val latest = model.command.value!!
        model.consume(old.nonce)
        assertEquals(latest, model.command.value)
        model.consume(latest.nonce)
        assertNull(model.command.value)
    }
    @Test fun pendingRequestSurvivesStateRestoration() {
        val pending = ExternalNavCommand.ReferenceLink("page", "https://example.com", 10)
        val model = ExternalCommandViewModel(SavedStateHandle(mapOf("pendingExternal" to pending, "initialReceived" to true)))
        model.receiveInitial(null, null, null, true)
        assertEquals(pending, model.command.value)
    }
}
