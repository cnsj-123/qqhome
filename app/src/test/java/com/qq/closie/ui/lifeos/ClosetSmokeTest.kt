package com.qq.closie.ui.lifeos

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.swipe
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.click
import com.qq.closie.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Cold launch → function page → real Closet, with no competing root bottom bar. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w393dp-h852dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ClosetSmokeTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private fun openDrawer() {
        compose.waitUntil(timeoutMillis = 10_000) { compose.onAllNodes(hasText("Life OS")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("生活").assertDoesNotExist()
        compose.onRoot().performTouchInput { swipeRight(startX = width * .25f, endX = width * .75f) }
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
    }

    private fun assertFinanceLedger() {
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasTestTag("finance-ledger")).fetchSemanticsNodes().size == 1 &&
                compose.onAllNodes(hasText("账簿")).fetchSemanticsNodes().size == 1
        }
        compose.onAllNodes(hasTestTag("finance-ledger")).assertCountEquals(1)
        compose.onNodeWithTag("finance-ledger").assertIsDisplayed()
        listOf("账簿", "账户", "分析").forEach {
            compose.onAllNodes(hasText(it)).assertCountEquals(1)
            compose.onNodeWithText(it).assertIsDisplayed()
        }
        compose.onNodeWithText("账簿").assertIsSelected()
        compose.onNodeWithText("总览").assertDoesNotExist()
        compose.onNodeWithText("流水").assertDoesNotExist()
    }

    private fun pressBack() {
        // Wait for composition/effects to publish enabled handlers before dispatching Back.
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
    }

    private fun awaitDrawerClosed() {
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasContentDescription("收起生活的抽屉")).fetchSemanticsNodes().isEmpty()
        }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("收起生活的抽屉").assertIsNotDisplayed()
    }

    private fun openCloset(physicalClick: Boolean = true) {
        // Bring the row to a stable visible position in the partial-width drawer.
        compose.onNodeWithTag("life-drawer")
            .performScrollToNode(hasTestTag("drawer-closet"))
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        val row = compose.onNodeWithText("衣橱").assertIsDisplayed()
        if (physicalClick) row.performClick()
        else row.performSemanticsAction(SemanticsActions.OnClick) { it() }
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodes(hasText("我的衣橱")).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun drawerHasHeaderSearchModulesAndDirectReorderAffordances() {
        openDrawer()
        compose.onNodeWithText("生活的抽屉").assertIsDisplayed()
        compose.onNodeWithText("把日子放回自己的位置").assertIsDisplayed()
        compose.onNodeWithText("找一件东西、一段经历").assertIsDisplayed()
        compose.onNodeWithTag("drawer-capture").assertIsDisplayed()
        compose.onNodeWithText("记一下").assertIsDisplayed()
        compose.onNodeWithText("收件匣").assertIsDisplayed()
        compose.onNodeWithText("日历").assertIsDisplayed()
        compose.onNodeWithText("搜索").assertIsDisplayed()
        compose.onNodeWithTag("drawer-finance").assertIsDisplayed()
        compose.onNodeWithText("长按直接移动").assertIsDisplayed()
        compose.onNodeWithTag("life-drawer").performScrollToNode(hasTestTag("drawer-restore-order"))
        compose.onNodeWithTag("drawer-restore-order").assertExists().assertIsDisplayed()

        // A real held long-press creates one lifted overlay in addition to the original row.
        compose.onNodeWithTag("life-drawer").performScrollToNode(hasText("生活的抽屉"))
        val longPress = android.view.ViewConfiguration.getLongPressTimeout().toLong()
        compose.onNodeWithTag("drawer-finance").assertIsDisplayed().performTouchInput {
            down(center)
            advanceEventTime(longPress + 1L)
            moveBy(Offset(0f, 2f))
        }
        try {
            compose.waitUntil(10_000) {
                compose.onAllNodes(hasTestTag("drawer-finance")).fetchSemanticsNodes().size == 2
            }
            pressBack()
            compose.waitUntil(10_000) {
                compose.onAllNodes(hasTestTag("drawer-finance")).fetchSemanticsNodes().size == 1
            }
            compose.onNodeWithContentDescription("收起生活的抽屉").assertIsDisplayed()
        } finally { compose.onRoot().performTouchInput { up() } }
        // The second Back closes the drawer; reopening must not retain a cancellation callback.
        pressBack()
        awaitDrawerClosed()
        compose.onNodeWithText("Life OS").assertIsDisplayed()
        openDrawer()
        pressBack()
        awaitDrawerClosed()
        compose.onNodeWithText("Life OS").assertIsDisplayed()
    }

    @Test
    fun verticalHomeScrollDoesNotOpenDrawer() {
        compose.waitUntil(timeoutMillis = 10_000) { compose.onAllNodes(hasText("Life OS")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasScrollAction() and hasAnyDescendant(hasText("Life OS")))
            .performTouchInput { swipeUp() }
        compose.onNodeWithContentDescription("收起生活的抽屉").assertIsNotDisplayed()
    }

    @Test
    fun freshInstall_emptyWardrobe_homeToLifeToCloset_rendersEmptyState() {
        openDrawer()

        openCloset()

        compose.onNodeWithText("我的衣橱").assertExists().assertIsDisplayed()
    }

    @Test
    fun freshInstall_closetIsEmpty_notBlankNotCrash() {
        openDrawer()
        openCloset(physicalClick = false)

        // "Not blank" is part of the contract: the failure mode this test guards against is a
        // page that neither crashes nor shows anything. The empty wardrobe must still speak —
        // fresh install lands on the OWNED tab's empty state.
        compose.onNodeWithText("我的衣橱").assertExists()
        compose.onNodeWithText("衣橱还是空的").assertExists()
        pressBack()
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasText("Life OS")).fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodes(hasText("我的衣橱")).fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithText("Life OS").assertIsDisplayed()
        compose.onNodeWithText("我的衣橱").assertDoesNotExist()
    }
    @Test
    fun homeContentRightSwipeOpensFunctionPageAndBackClosesIt() {
        compose.waitUntil(timeoutMillis = 10_000) { compose.onAllNodes(hasText("Life OS")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasScrollAction() and hasAnyDescendant(hasText("Life OS")))
            .performTouchInput { swipeRight(startX = width * .25f, endX = width * .75f) }
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithContentDescription("收起生活的抽屉").assertIsDisplayed()
        pressBack()
        awaitDrawerClosed()
        compose.onNodeWithText("Life OS").assertIsDisplayed()
    }

    @Test
    fun functionPageContentLeftSwipeClosesIt() {
        openDrawer()
        compose.onRoot()
            .performTouchInput { swipeLeft(startX = width * .70f, endX = width * .10f) }
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        compose.onNodeWithContentDescription("收起生活的抽屉").assertIsNotDisplayed()
    }

    @Test
    fun tappingExposedHomeClosesDrawer() {
        openDrawer()
        compose.onRoot().performTouchInput { click(androidx.compose.ui.geometry.Offset(width * .95f, height * .5f)) }
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithContentDescription("收起生活的抽屉").assertIsNotDisplayed()
    }

    @Test
    fun drawerFinanceOpensRealLedgerAndReturnsToStableHome() {
        openDrawer()
        compose.onNodeWithTag("life-drawer").performScrollToNode(hasTestTag("drawer-finance"))
        compose.onNodeWithTag("drawer-finance").performClick()
        assertFinanceLedger()
        compose.onNodeWithContentDescription("收起生活的抽屉").assertIsNotDisplayed()
        compose.onNodeWithContentDescription("返回").assertIsDisplayed().performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasText("Life OS")).fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodes(hasTestTag("finance-ledger")).fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithText("Life OS").assertIsDisplayed()
        compose.onNodeWithTag("finance-ledger").assertDoesNotExist()
        compose.onNodeWithContentDescription("收起生活的抽屉").assertIsNotDisplayed()
    }

    @Test
    fun financeRootRightSwipeOpensShellFunctionPageAndBackKeepsFinance() {
        openDrawer()
        compose.onNodeWithTag("life-drawer").performScrollToNode(hasTestTag("drawer-finance"))
        compose.onNodeWithTag("drawer-finance").performClick()
        assertFinanceLedger()
        val query = "抽屉返回保留筛选"
        compose.onNodeWithTag("finance-search").performTextReplacement(query)
        compose.onNodeWithTag("finance-ledger").assertIsDisplayed()
        compose.onNodeWithContentDescription("收起生活的抽屉").assertIsNotDisplayed()

        repeat(2) {
            // Finance reserves the inner-left shell corridor; body swipes belong to its pager.
            compose.onNodeWithTag("finance-ledger").performTouchInput {
                swipe(Offset(width * .10f, height * .12f), Offset(width * .75f, height * .12f), durationMillis = 400)
            }
            compose.onNodeWithTag("life-drawer").assertIsDisplayed()
            compose.onNodeWithContentDescription("收起生活的抽屉").assertIsDisplayed()
            pressBack()
            awaitDrawerClosed()
            assertFinanceLedger()
            compose.onNodeWithTag("finance-search").assertTextContains(query)
            compose.onNodeWithText("Life OS").assertDoesNotExist()
        }
    }

}
