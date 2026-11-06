package com.qq.closie.ui.lifeos.finance

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.qq.closie.life.finance.*
import com.qq.closie.ui.lifeos.components.LifeSurfaceOrigin
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
class FinanceReceiptPrintTest:FinanceRoomTest() {
    @get:Rule val compose=createComposeRule()

    @Test fun completedTicketShowsCommittedFactsAndClosesWithoutAnotherWrite() {
        val data=runBlocking {
            val account=account("真实事务合成账户")
            payment(account.id,1_280)
            finance.snapshot()
        }
        val row=FinanceProjection.ledger(data).single()
        val before=runBlocking {finance.portableSnapshot()}
        val open=mutableStateOf(true)
        var completions=0
        compose.mainClock.autoAdvance=false
        compose.setContent {LifeOsTheme {
            if(open.value) FinancePrintedReceipt(row,destination={null},reducedMotion=false) {completions++;open.value=false}
        }}
        compose.mainClock.advanceTimeBy(350)
        compose.onNodeWithText("真实事务合成账户").assertExists()
        compose.onNodeWithText("衣服").assertExists()
        compose.onNodeWithText("合成付款").assertExists()
        compose.onNodeWithText("-"+FinanceMoney.display(1_280,row.currencyCode)).assertExists()
        compose.runOnIdle {assertEquals(0,completions)}
        compose.mainClock.advanceTimeBy(300)
        compose.runOnIdle {assertEquals(1,completions)}
        compose.onNodeWithTag("finance-printed-receipt").assertDoesNotExist()
        assertEquals(before,runBlocking {finance.portableSnapshot()})
    }

    @Test fun returnOnlyUsesAnActualVisibleNonTransferDestination() {
        val data=runBlocking {val account=account();payment(account.id);finance.snapshot()}
        val row=FinanceProjection.ledger(data).single()
        val viewport=Rect(0f,0f,405f,880f)
        val visible=LifeSurfaceOrigin(Rect(32f,300f,373f,348f),0f,1)
        assertEquals(visible.rect,financePrintDestination(row,visible,1,viewport))
        assertNull(financePrintDestination(row,null,1,viewport))
        assertNull(financePrintDestination(null,visible,1,viewport))
        assertNull(financePrintDestination(row,visible,2,viewport))
        assertNull(financePrintDestination(row,visible.copy(rect=Rect(32f,900f,373f,948f)),1,viewport))
        val transfer=runBlocking {
            val second=account("合成转入")
            finance.saveTransfer(null,row.account.id,second.id,100,"合成转账",2_000)
            FinanceProjection.ledger(finance.snapshot()).single {it.transfer!=null}
        }
        assertNull(financePrintDestination(transfer,visible,1,viewport))
    }
}
