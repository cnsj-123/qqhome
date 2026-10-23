package com.qq.closie.data.backup

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Test
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class BackupTaskOwnerTest {
    @Test fun settingsScopeCancellationCannotCancelApplicationOwnedRestore()=runTest {
        val page=CoroutineScope(Job()+StandardTestDispatcher(testScheduler))
        val application=CoroutineScope(SupervisorJob()+StandardTestDispatcher(testScheduler))
        val owner=BackupTaskOwner(application)
        val started=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        var completed=false
        page.launch {owner.start("restored") {started.complete(Unit);release.await();completed=true;Result.success(Unit)}}
        runCurrent();started.await();page.cancel();runCurrent()
        assertTrue(owner.state.value.running);assertFalse(completed)
        release.complete(Unit);advanceUntilIdle()
        assertTrue(completed);assertFalse(owner.state.value.running);assertEquals("restored",owner.state.value.result)
        application.cancel()
    }
    @Test fun duplicateRestoreIsRejectedAndFailureSurvivesPageReentry()=runTest {
        val application=CoroutineScope(SupervisorJob()+StandardTestDispatcher(testScheduler))
        val owner=BackupTaskOwner(application);val release=CompletableDeferred<Unit>()
        assertTrue(owner.start("success"){release.await();Result.failure(IllegalStateException("synthetic failure"))})
        assertFalse(owner.start("second"){error("must not run")})
        runCurrent();release.complete(Unit);advanceUntilIdle()
        assertFalse(owner.state.value.running);assertTrue(owner.state.value.failed);assertEquals("synthetic failure",owner.state.value.result)
        owner.acknowledge();assertNull(owner.state.value.result);application.cancel()
    }
}
