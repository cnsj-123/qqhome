package com.qq.closie.ui.lifeos

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
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

/** Cold launch → V11 drawer → real Closet, with no competing root bottom bar. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w393dp-h852dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ClosetSmokeTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private fun openDrawer() {
        compose.waitUntil(timeoutMillis = 10_000) { compose.onAllNodes(hasText("Life OS")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("生活").assertDoesNotExist()
        compose.onNodeWithContentDescription("打开生活的抽屉").performClick()
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
    }

    private fun openCloset(physicalClick: Boolean = true) {
        // Bring the row to a stable visible position in the partial-width drawer.
        compose.onNodeWithTag("life-drawer")
            .performScrollToIndex(5)
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
    fun drawerHasRestoredHeaderSearchAndQuickActions() {
        openDrawer()
        compose.onNodeWithText("生活的抽屉").assertIsDisplayed()
        compose.onNodeWithText("水母来信 · 把日子放回自己的位置").assertIsDisplayed()
        compose.onNodeWithText("搜一件东西、一段经历……").assertIsDisplayed()
        compose.onNodeWithTag("drawer-capture").assertIsDisplayed()
        compose.onNodeWithText("收件匣").assertIsDisplayed()
        compose.onNodeWithText("日历").assertIsDisplayed()
        compose.onNodeWithText("搜索").assertIsDisplayed()
        val capture = compose.onNodeWithTag("drawer-capture").fetchSemanticsNode().boundsInRoot
        val inbox = compose.onNodeWithText("收件匣").fetchSemanticsNode().boundsInRoot
        val calendar = compose.onNodeWithText("日历").fetchSemanticsNode().boundsInRoot
        val search = compose.onNodeWithText("搜索").fetchSemanticsNode().boundsInRoot
        org.junit.Assert.assertTrue(inbox.left > capture.right)
        org.junit.Assert.assertEquals(capture.top, inbox.top, 2f)
        org.junit.Assert.assertEquals(calendar.top, search.top, 2f)
        org.junit.Assert.assertTrue(calendar.top > capture.bottom)
        compose.onNodeWithText("收件匣 0").assertDoesNotExist()
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
    }
    @Test
    fun homeContentLeftSwipeOpensDrawerAndBackClosesIt() {
        compose.waitUntil(timeoutMillis = 10_000) { compose.onAllNodes(hasText("Life OS")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasScrollAction() and hasAnyDescendant(hasText("Life OS")))
            .performTouchInput { swipeLeft(startX = width * .75f, endX = width * .25f) }
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithContentDescription("收起生活的抽屉").assertIsDisplayed()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithContentDescription("收起生活的抽屉").assertIsNotDisplayed()
        compose.onNodeWithText("Life OS").assertIsDisplayed()
    }

    @Test
    fun drawerContentRightSwipeClosesIt() {
        openDrawer()
        compose.onNodeWithTag("life-drawer")
            .performTouchInput { swipeRight(startX = width * .25f, endX = width * .75f) }
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithContentDescription("收起生活的抽屉").assertIsNotDisplayed()
    }

    @Test
    fun tappingExposedHomeClosesDrawer() {
        openDrawer()
        compose.onRoot().performTouchInput { click(androidx.compose.ui.geometry.Offset(width * .95f, height * .5f)) }
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithContentDescription("收起生活的抽屉").assertIsNotDisplayed()
    }

}
