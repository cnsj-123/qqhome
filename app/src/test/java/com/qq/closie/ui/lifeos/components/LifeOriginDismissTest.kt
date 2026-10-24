package com.qq.closie.ui.lifeos.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.qq.closie.ui.lifeos.theme.LifeOsTheme
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],qualifiers="w393dp-h852dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LifeOriginDismissTest {
    @get:Rule val compose=createComposeRule()
    @Test fun photoAndCompactReceiptScrimsCloseOnceWhilePaperDoesNotDismiss() {
        var shown by mutableStateOf(true);var compact by mutableStateOf(false);var dismissed=0
        compose.setContent {LifeOsTheme {if(shown)LifeOriginDialog(null,{dismissed++;shown=false},compactHeight=compact) {
            Column(Modifier.height(220.dp).fillMaxWidth()){Text("纸面内容")}
        }}}
        for(receipt in listOf(false,true)) {
            compose.runOnIdle {compact=receipt;shown=true};compose.waitForIdle()
            compose.onNodeWithTag("life-origin-paper").performTouchInput {click(center)}
            assertEquals(if(receipt)1 else 0,dismissed)
            compose.mainClock.autoAdvance=false
            compose.onNodeWithTag("life-origin-scrim").performTouchInput {click(Offset(2f,2f));click(Offset(2f,2f))}
            compose.mainClock.advanceTimeBy(260);compose.mainClock.autoAdvance=true;compose.waitForIdle()
            assertEquals(if(receipt)2 else 1,dismissed)
            compose.onNodeWithTag("life-origin-paper").assertDoesNotExist()
        }
    }
    @Test fun systemBackUsesSameSingleClosePath() {
        var shown by mutableStateOf(true);var dismissed=0
        compose.setContent {LifeOsTheme {if(shown)LifeOriginDialog(null,{dismissed++;shown=false}) {Text("内容")}}}
        compose.waitForIdle()
        val app=ApplicationProvider.getApplicationContext<android.app.Application>()
        compose.runOnIdle {requireNotNull(org.robolectric.shadows.ShadowDialog.getLatestDialog()).onBackPressed()}
        compose.waitForIdle()
        assertEquals(1,dismissed);compose.onNodeWithTag("life-origin-paper").assertDoesNotExist()
    }
}
