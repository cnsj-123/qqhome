package com.qq.closie.ui.lifeos.settings

import androidx.compose.runtime.mutableStateOf
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.qq.closie.ui.lifeos.theme.LifeOsTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],qualifiers="w405dp-h880dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsBackupMotionTest {
    @get:Rule val compose=createComposeRule()

    @Test fun earlyReleaseNeverRestoresAndCompletedHoldRunsExactlyOnce() {
        var restores=0
        compose.setContent {LifeOsTheme {SettingsHoldToRestore(true,"inspected-source") {restores++}}}
        compose.waitForIdle()
        compose.mainClock.autoAdvance=false
        val button=compose.onNodeWithTag("backup-restore-hold")
        button.performTouchInput {down(center)}
        compose.mainClock.advanceTimeBy(300)
        button.performTouchInput {up()}
        compose.mainClock.advanceTimeBy(700)
        compose.runOnIdle {assertEquals(0,restores)}

        // Android performClick is a touch tap: releasing it must never become an accessibility hold.
        button.performTouchInput {click()}
        compose.mainClock.advanceTimeBy(660)
        compose.runOnIdle {assertEquals(0,restores)}

        button.performTouchInput {down(center)}
        compose.mainClock.advanceTimeBy(660)
        compose.runOnIdle {assertEquals(1,restores)}
        compose.mainClock.advanceTimeBy(700)
        compose.runOnIdle {assertEquals(1,restores)}
        button.performTouchInput {up()}
        compose.mainClock.autoAdvance=true
    }

    @Test fun accessibilityUsesTheSameDelayAndSourceChangeCancelsPendingConfirmation() {
        var restores=0
        val source=mutableStateOf("inspected-a")
        val enabled=mutableStateOf(true)
        compose.setContent {LifeOsTheme {SettingsHoldToRestore(enabled.value,source.value) {restores++}}}
        compose.waitForIdle()
        compose.mainClock.autoAdvance=false
        val button=compose.onNodeWithTag("backup-restore-hold")
        button.performSemanticsAction(SemanticsActions.OnClick) {assertTrue(it())}
        compose.mainClock.advanceTimeBy(320)
        compose.runOnIdle {assertEquals(0,restores);source.value="inspected-b"}
        compose.mainClock.advanceTimeBy(700)
        compose.runOnIdle {assertEquals(0,restores)}

        button.performSemanticsAction(SemanticsActions.OnClick) {assertTrue(it())}
        compose.mainClock.advanceTimeBy(320)
        compose.runOnIdle {assertEquals(0,restores);enabled.value=false}
        compose.mainClock.advanceTimeBy(700)
        button.performSemanticsAction(SemanticsActions.OnClick) {assertFalse(it())}
        compose.mainClock.advanceTimeBy(660)
        compose.runOnIdle {assertEquals(0,restores);enabled.value=true}
        compose.waitUntil(10_000) {
            // With a paused clock, let the enabled preference reach the actual accessibility node
            // before starting another deliberate confirmation. No hold has started during this wait.
            compose.mainClock.advanceTimeByFrame()
            compose.runOnIdle {}
            !button.fetchSemanticsNode().config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled)
        }
        button.performSemanticsAction(SemanticsActions.OnClick) {assertTrue(it())}
        compose.mainClock.advanceTimeBy(320)
        compose.runOnIdle {assertEquals(0,restores)}
        compose.mainClock.advanceTimeBy(340)
        compose.runOnIdle {assertEquals(1,restores)}
        compose.mainClock.autoAdvance=true
    }

    @Test fun cancelAt400msStopsAccessibilityConfirmationBeforeExitAnimationDisposesThePrompt() {
        var restores=0
        var dismissed=0
        val shown=mutableStateOf(true)
        compose.setContent {LifeOsTheme {if(shown.value) SettingsPaperPrompt(
            paper=true,onDismissRequest={dismissed++;shown.value=false},title={Text("确认恢复范围")},text={Text("已检查的备份")},
            confirmButton={_,canConfirm->SettingsHoldToRestore(canConfirm,"inspected-source") {restores++}},
            dismissButton={close->TextButton(close) {Text("取消")}})}}
        compose.waitForIdle()
        compose.mainClock.autoAdvance=false
        compose.onNodeWithTag("backup-restore-hold").performSemanticsAction(SemanticsActions.OnClick) {assertTrue(it())}
        compose.mainClock.advanceTimeBy(400)
        compose.onNodeWithText("取消").performClick()
        compose.mainClock.advanceTimeBy(220)
        compose.runOnIdle {assertEquals(0,restores);assertEquals(0,dismissed)}
        compose.onNodeWithTag("backup-restore-hold").assertIsNotEnabled()
        compose.mainClock.advanceTimeBy(100)
        compose.runOnIdle {assertEquals(0,restores);assertEquals(1,dismissed)}
        compose.mainClock.autoAdvance=true
    }

    @Test fun systemBackAt400msStopsTouchConfirmationAndBusyOwnerRejectsClosing() {
        var restores=0
        var dismissed=0
        val shown=mutableStateOf(true)
        val busy=mutableStateOf(false)
        compose.setContent {LifeOsTheme {if(shown.value) SettingsPaperPrompt(
            paper=true,busy=busy.value,onDismissRequest={dismissed++;shown.value=false},
            title={Text("确认恢复范围")},text={Text("已检查的备份")},
            confirmButton={_,canConfirm->SettingsHoldToRestore(canConfirm,"inspected-source") {restores++}},
            dismissButton={close->TextButton(close,enabled=!busy.value) {Text("取消")}})}}
        compose.waitForIdle()
        compose.mainClock.autoAdvance=false
        compose.onNodeWithTag("backup-restore-hold").performTouchInput {down(center)}
        compose.mainClock.advanceTimeBy(400)
        compose.runOnIdle {requireNotNull(org.robolectric.shadows.ShadowDialog.getLatestDialog()).onBackPressed()}
        compose.mainClock.advanceTimeBy(220)
        compose.runOnIdle {assertEquals(0,restores);assertEquals(0,dismissed)}
        compose.onNodeWithTag("backup-restore-hold").performTouchInput {up()}
        compose.mainClock.advanceTimeBy(100)
        compose.runOnIdle {assertEquals(0,restores);assertEquals(1,dismissed);busy.value=true;shown.value=true}
        compose.mainClock.autoAdvance=true
        compose.waitForIdle()
        compose.runOnIdle {requireNotNull(org.robolectric.shadows.ShadowDialog.getLatestDialog()).onBackPressed()}
        compose.waitForIdle()
        compose.runOnIdle {assertEquals(1,dismissed);assertEquals(true,shown.value)}
        compose.onNodeWithTag("backup-restore-hold").assertIsNotEnabled()
    }
}
