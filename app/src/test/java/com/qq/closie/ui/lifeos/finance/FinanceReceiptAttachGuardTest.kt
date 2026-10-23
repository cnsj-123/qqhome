package com.qq.closie.ui.lifeos.finance

import com.qq.closie.data.backup.RestoreStartupGate
import org.junit.*
import org.junit.Assert.*

class FinanceReceiptAttachGuardTest {
    @Before fun setup(){RestoreStartupGate.resetForTesting();RestoreStartupGate.markReady()}
    @After fun cleanup(){RestoreStartupGate.resetForTesting()}
    @Test fun changingHostBeforeNativeAttachRejectsLateReceipt() {
        var host="original";val generation=RestoreStartupGate.state.value.restoreGeneration
        val canPresent={host=="original"}
        assertTrue(canPresent()) // Initial validation before loading the review session.
        host="another";var attached=false
        assertFalse(attachFinanceReceipt(generation,canPresent,{true}){attached=true})
        assertFalse(attached)
    }
    @Test fun completedRestoreCannotReuseAnEarlierReadyState() {
        val original=RestoreStartupGate.state.value.restoreGeneration
        assertTrue(RestoreStartupGate.beginRestore());RestoreStartupGate.endRestoreReady()
        assertTrue(RestoreStartupGate.isReady);var attached=false
        assertFalse(attachFinanceReceipt(original,{true},{true}){attached=true});assertFalse(attached)
    }
    @Test fun revocationAndLockAreRecheckedAtAttachAndLeaseExcludesRestoreStart() {
        val generation=RestoreStartupGate.state.value.restoreGeneration
        var attached=false
        assertFalse(attachFinanceReceipt(generation,{true},{false}){attached=true});assertFalse(attached)
        assertTrue(attachFinanceReceipt(generation,{true},{true}) {
            assertFalse(RestoreStartupGate.beginRestore());attached=true
        })
        assertTrue(attached);assertTrue(RestoreStartupGate.beginRestore());RestoreStartupGate.endRestoreReady()
    }
}
