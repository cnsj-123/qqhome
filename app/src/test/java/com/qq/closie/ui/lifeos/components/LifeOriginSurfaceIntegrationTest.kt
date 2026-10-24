package com.qq.closie.ui.lifeos.components

import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.qq.closie.life.finance.*
import com.qq.closie.ui.lifeos.finance.FinanceRecordDetails
import com.qq.closie.ui.lifeos.media.LifeMediaUi
import com.qq.closie.ui.lifeos.theme.LifeOsTheme
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],qualifiers="w393dp-h852dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LifeOriginSurfaceIntegrationTest:FinanceRoomTest() {
    @get:Rule val compose=createComposeRule()
    @Test fun actualPhotoViewerDismissesOnScrimButKeepsPaperTaps() {
        var shown by mutableStateOf(true);var closed=0
        compose.setContent {LifeOsTheme {if(shown)LifePhotoViewer(listOf(LifeMediaUi("synthetic","content://synthetic/missing",title="合成")),
            "synthetic",{closed++;shown=false})}}
        compose.waitForIdle()
        compose.onNodeWithTag("life-origin-paper").performTouchInput {click(Offset(5f,50f))}
        assertEquals(0,closed)
        compose.onNodeWithTag("life-origin-scrim").performTouchInput {click(Offset(2f,2f))}
        compose.waitForIdle();assertEquals(1,closed)
    }
    @Test fun actualCanonicalDetailUsesAnimatedScrimDismissWithoutAddingAnotherEntry() {
        val data=runBlocking {val account=account();payment(account.id);finance.snapshot()}
        val row=FinanceProjection.ledger(data).single()
        val orientation=ApplicationProvider.getApplicationContext<android.content.Context>().resources.configuration.orientation
        var shown by mutableStateOf(true);var closed=0
        compose.setContent {LifeOsTheme {if(shown)FinanceRecordDetails(row,data,{},LifeSurfaceOrigin(Rect(16f,500f,370f,560f),0f,orientation)) {closed++;shown=false}}}
        compose.waitForIdle()
        compose.onNodeWithTag("life-origin-paper").performTouchInput {click(Offset(5f,50f))}
        assertEquals(0,closed)
        compose.onNodeWithTag("life-origin-scrim").performTouchInput {click(Offset(2f,2f))}
        compose.waitForIdle();assertEquals(1,closed)
        assertEquals(1,runBlocking {finance.snapshot()}.entries.size)
    }
}
