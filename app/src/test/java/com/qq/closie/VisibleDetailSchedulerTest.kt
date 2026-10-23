package com.qq.closie

import com.qq.closie.life.finance.acquisition.VisibleDetailScheduler
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VisibleDetailSchedulerTest {
    private val first = VisibleDetailScheduler.Target("host-a", 1)
    private val second = VisibleDetailScheduler.Target("host-b", 2)

    @Test fun earlyEmptyRootGetsBoundedTrailingReadsWithoutMoreEvents() = runTest {
        val times = mutableListOf<Long>()
        val scheduler = VisibleDetailScheduler(this) { times.add(currentTime); false }
        scheduler.submit(first)
        advanceUntilIdle()
        assertEquals(listOf(300L, 1100L, 1900L), times)
        advanceTimeBy(60000)
        assertEquals(3, times.size)
    }
    @Test fun eventDuringBusyReadReplaysOnceAfterCompletion() = runTest {
        var reads = 0
        val scheduler = VisibleDetailScheduler(this) { reads++; delay(500); true }
        scheduler.submit(first)
        advanceTimeBy(400)
        scheduler.submit(first)
        advanceUntilIdle()
        assertEquals(2, reads)
    }
    @Test fun latestWindowCancelsPendingOldWindowAndCancellationStopsAllRetries() = runTest {
        val roots = mutableListOf<VisibleDetailScheduler.Target>()
        val scheduler = VisibleDetailScheduler(this) { roots.add(it); false }
        scheduler.submit(first)
        advanceTimeBy(100)
        scheduler.submit(second)
        advanceTimeBy(301)
        scheduler.cancel()
        advanceUntilIdle()
        assertEquals(listOf(second), roots)
        assertFalse(scheduler.isCurrent(first))
        assertFalse(scheduler.isCurrent(second))
    }
    @Test fun successDoesNotPollAndAnotherExplicitEventCanReadAgain() = runTest {
        var reads = 0
        val scheduler = VisibleDetailScheduler(this) { reads++; true }
        scheduler.submit(first)
        advanceUntilIdle()
        assertEquals(1, reads)
        scheduler.submit(first)
        advanceUntilIdle()
        assertEquals(2, reads)
    }
}
