package com.qq.closie.ui.lifeos.finance

import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.qq.closie.ui.lifeos.theme.LifeOsTheme
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
class FinancePaperTearTest {
    @get:Rule val compose=createComposeRule()

    @Test fun tearUnzipsAtTheCentreBeforeTheEdgesAndNeverEscapesThePaper() {
        val beginning=manualTearEdge(405f,8f,.08f)
        assertEquals(0f,beginning.first().y,0f)
        assertEquals(0f,beginning.last().y,0f)
        assertTrue(beginning[beginning.size/2].y>0f)
        for(step in 0..100) {
            val edge=manualTearEdge(405f,8f,step/100f)
            assertTrue(edge.zipWithNext().all {(a,b)->b.x>a.x})
            assertTrue(edge.all {it.x in 0f..405f && it.y in 0f..8f})
        }
        assertTrue(manualTearEdge(405f,8f,0f).all {it.y==0f})
        assertTrue(manualTearEdge(405f,8f,1f).all {it.y>0f})
    }

    @Test fun realBackdropRetainsTheSameTerminalGeometryAndFallsBackWithoutBlur() {
        val open=financePaperBackdrop(1f,false,34,false)
        assertEquals(.97f,open.scale,.0001f)
        assertEquals(.08f,open.shade,.0001f)
        assertEquals(2f,open.blur,.0001f)
        assertEquals(0f,financePaperBackdrop(1f,false,30,false).blur,0f)
        assertEquals(0f,financePaperBackdrop(1f,false,34,true).blur,0f)
        val reduced=financePaperBackdrop(1f,true,34,false)
        assertEquals(1f,reduced.scale,0f)
        assertEquals(0f,reduced.blur,0f)
        val closed=financePaperBackdrop(0f,false,34,false)
        assertEquals(1f,closed.scale,0f)
        assertEquals(0f,closed.shade,0f)
        assertEquals(0f,closed.blur,0f)
    }

    @Test fun reducedPaperKeepsItsGeometryWhileFadingAndWaitsForExitBeforeClosing() {
        val open=mutableStateOf(true)
        var closed=0
        var reported=0f
        compose.mainClock.autoAdvance=false
        compose.setContent {LifeOsTheme {
            if(open.value) FinanceEntryContainer("合成小票",false,null,{closed++;open.value=false},
                manualPaper=true,reducedMotion=true,onPaperMotion={reported=it},actions={}) {Text("合成内容")}
        }}
        compose.mainClock.advanceTimeBy(80)
        val appearing=compose.onNodeWithTag("finance-receipt").fetchSemanticsNode().boundsInRoot
        compose.mainClock.advanceTimeBy(120)
        val opened=compose.onNodeWithTag("finance-receipt").fetchSemanticsNode().boundsInRoot
        assertEquals(appearing,opened)
        compose.onNodeWithContentDescription("收起").performClick()
        compose.mainClock.advanceTimeBy(80)
        compose.runOnIdle {assertEquals(0,closed)}
        compose.mainClock.advanceTimeBy(120)
        compose.runOnIdle {assertEquals(1,closed);assertEquals(0f,reported,0f)}
        compose.onNodeWithTag("finance-receipt").assertDoesNotExist()
    }
}
