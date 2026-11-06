package com.qq.closie.ui.lifeos.finance

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.qq.closie.life.finance.*
import com.qq.closie.ui.lifeos.theme.LifeOsTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],qualifiers="w405dp-h880dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FinanceFigmaGeometryTest:FinanceRoomTest() {
    @get:Rule val compose=createComposeRule()

    @Test fun measuredHeaderKeepsSearchOnTheAccountLineAndNetInItsOriginalSecondLine() {
        val data=runBlocking {account();finance.snapshot()}
        compose.setContent {LifeOsTheme {FinanceNetHeader(FinanceUiState(snapshot=data,loaded=true,loading=false),false,"",{},{},{}) {}}}
        val search=compose.onNodeWithTag("finance-search").fetchSemanticsNode().boundsInRoot
        val net=compose.onNodeWithTag("finance-net-value",useUnmergedTree=true).fetchSemanticsNode().boundsInRoot
        val density=compose.density.density
        assertEquals(28f,search.height/density,1f)
        assertTrue("Search must keep the Figma 110dp capsule",search.width/density in 94f..111f)
        assertTrue("Net cannot be displaced by an extra app-bar row",(net.top-search.top)/density in 20f..48f)
        assertTrue("The currency and net start at the measured 32dp gutter",net.left/density in 48f..72f)
    }

    @Test fun compactFourColumnKeypadRetainsRealMultiplicationThroughThePlusLongPress() {
        val value=mutableStateOf("")
        compose.setContent {LifeOsTheme {FinanceAmountKeypad(value.value,"CNY",true,compact=true){value.value=it}}}
        compose.onNodeWithText("2").performClick()
        compose.onNodeWithText("+").performTouchInput {longClick()}
        compose.onNodeWithText("3").performClick()
        compose.onNodeWithText("＝").performClick()
        compose.runOnIdle {assertEquals("6.00",value.value)}
        compose.onNodeWithText("×").assertDoesNotExist()
        compose.onNodeWithText("÷").assertDoesNotExist()
    }
}
