package com.qq.closie.ui.lifeos.finance

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class DeferredReceiptQueueTest {
    @Test fun duplicateCandidateDoesNotReplaceCurrentEditingAndOnlyOneNextSurfaceOpens() = runTest {
        val queue = DeferredReceiptQueue()
        val owner = Any()
        val shown = mutableListOf<String>()
        queue.defer("a", owner, { true }) { shown += "obsolete"; true }
        queue.defer("a", owner, { true }) { shown += "a"; true }
        queue.defer("b", owner, { true }) { shown += "b"; true }
        assertTrue(shown.isEmpty())
        queue.advance()
        assertEquals(listOf("a"), shown)
        queue.advance()
        assertEquals(listOf("a", "b"), shown)
    }
    @Test fun staleHostAndRevokedSourceCannotSummonQueuedSurface() = runTest {
        val queue = DeferredReceiptQueue()
        val shown = mutableListOf<String>()
        queue.defer("left-page", Any(), { false }) { shown += "stale"; true }
        queue.defer("blocked", Any(), { true }) { false }
        queue.defer("current", Any(), { true }) { shown += "current"; true }
        queue.advance()
        assertEquals(listOf("current"), shown)
    }
    @Test fun teardownRemovesOnlyThatAcquisitionOwnersCallbacks() = runTest {
        val queue = DeferredReceiptQueue(2)
        val stopped = Any()
        val shown = mutableListOf<String>()
        queue.defer("old", Any(), { true }) { shown += "old"; true }
        queue.defer("stopped", stopped, { true }) { shown += "stopped"; true }
        queue.defer("live", Any(), { true }) { shown += "live"; true }
        queue.removeOwner(stopped)
        queue.advance()
        assertEquals(listOf("live"), shown)
    }
}
