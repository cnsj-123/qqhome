package com.qq.closie.ui.lifeos

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.qq.closie.ui.lifeos.components.lifeHorizontalSwipe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import androidx.compose.ui.geometry.Offset
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w393dp-h852dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LifeSwipeOwnershipTest {
    @get:Rule val compose = createComposeRule()

    @Test fun photoChildKeepsItsDragAndHomeContainerOwnsOtherContent() {
        var opened = 0
        var photoDrags = 0
        compose.setContent {
            Box(Modifier.fillMaxSize().testTag("home").lifeHorizontalSwipe(onLeft = { opened++ })) {
                Box(Modifier.size(240.dp, 120.dp).testTag("photo")
                    .pointerInput(Unit) { detectHorizontalDragGestures { change, _ -> change.consume(); photoDrags++ } })
            }
        }
        compose.onNodeWithTag("photo").performTouchInput { swipeLeft(startX = width * .8f, endX = width * .2f) }
        compose.runOnIdle { assertEquals(0, opened); assertTrue(photoDrags > 0) }
        compose.onNodeWithTag("home").performTouchInput { swipe(Offset(width * .8f, height * .7f), Offset(width * .2f, height * .7f)) }
        compose.runOnIdle { assertEquals(1, opened) }
    }

    @Test fun systemBackEdgesAreNotOwnedByHome() {
        var opened = 0
        compose.setContent { Box(Modifier.fillMaxSize().testTag("home").lifeHorizontalSwipe(onLeft = { opened++ })) }
        compose.onNodeWithTag("home").performTouchInput { swipeLeft(startX = width - 1f, endX = width * .2f) }
        compose.runOnIdle { assertEquals(0, opened) }
    }
}
