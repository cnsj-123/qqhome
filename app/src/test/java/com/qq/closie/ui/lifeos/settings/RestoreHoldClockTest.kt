package com.qq.closie.ui.lifeos.settings

import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.ui.MotionDurationScale
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RestoreHoldClockTest {
    @Test fun disablingOrSpeedingAnimationsNeverShortensThe600msSafetyGate() = runTest {
        for(scale in listOf(0f,.5f,1f,2f)) {
            val frames=BroadcastFrameClock()
            val durationScale=object:MotionDurationScale {override val scaleFactor=scale}
            var confirmations=0
            val hold=launch(frames+durationScale) {awaitRestoreHold {};confirmations++}
            runCurrent()
            frames.sendFrame(0L)
            runCurrent()
            frames.sendFrame(599_000_000L)
            runCurrent()
            assertEquals("Animation scale $scale must not authorize before 600ms",0,confirmations)
            frames.sendFrame(600_000_000L)
            runCurrent()
            assertEquals("Animation scale $scale must keep the same deliberate hold",1,confirmations)
            hold.join()
        }
    }
}
