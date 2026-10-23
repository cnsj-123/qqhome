package com.qq.closie.data.backup

import androidx.compose.runtime.*
import androidx.compose.material3.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class BackupTaskOwnerComposeTest {
    @get:Rule val compose=createComposeRule()
    @Test fun realComposeUnmountAndRecompositionDoNotCancelRestoreOwner() {
        RestoreStartupGate.resetForTesting();RestoreStartupGate.markReady()
        val app=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
        val owner=BackupTaskOwner(app);val release=CompletableDeferred<Unit>()
        var disposed=false;var completed=false;var tick by mutableIntStateOf(0)
        try {
            compose.setContent {
                val gate by RestoreStartupGate.state.collectAsState()
                if(gate.status==RestoreStartupGate.Status.READY) {
                    rememberCoroutineScope()
                    DisposableEffect(Unit){onDispose {disposed=true}}
                    Button(onClick={owner.start("restored") {
                        check(RestoreStartupGate.beginRestore());release.await();completed=true
                        RestoreStartupGate.endRestoreReady();Result.success(Unit)
                    }}) {Text("restore "+tick)}
                } else Text("restoring")
            }
            compose.onNodeWithText("restore 0").performClick()
            compose.onNodeWithText("restoring").assertExists()
            compose.runOnIdle {assertTrue(disposed);assertTrue(owner.state.value.running);tick++}
            compose.onNodeWithText("restoring").assertExists()
            compose.runOnIdle {release.complete(Unit)}
            compose.waitUntil(3000){completed && !owner.state.value.running}
            compose.onNodeWithText("restore 1").assertExists()
            assertEquals(RestoreStartupGate.Status.READY,RestoreStartupGate.state.value.status)
        } finally {release.complete(Unit);app.cancel();RestoreStartupGate.resetForTesting()}
    }
}
