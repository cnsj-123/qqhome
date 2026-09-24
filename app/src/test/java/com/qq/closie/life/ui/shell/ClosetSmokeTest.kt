package com.qq.closie.life.ui.shell

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import com.qq.closie.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v0.2.0 real-device crash regression — the full walk a user performs on the vivo device:
 *
 *   launch → 生活 tab → 衣橱 row → closet renders.
 *
 * v0.1 crashed on that exact path on a real phone while CI stayed green, and every static audit
 * of the chain (LifeModulesScreen → onOpenCloset → LifeShellNavHost → LifeDestination.Closet →
 * ClosieNavHost → ClosetScreen → LocalWardrobeRepository → ClosetPrefs → rememberClosieDimensions
 * → EmptyState/LazyGrid) came back clean. So this test does what static reading cannot: it boots
 * the real [MainActivity] on a **fresh install** (Robolectric gives an empty SharedPreferences
 * store and an empty Room-less wardrobe repo — the zero-items state the crash report described)
 * and asserts the closet actually renders its empty state instead of dying.
 *
 * If this test ever fails, the stack trace *is* the root cause — no more "CI green but device
 * dead" blind spot.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ClosetSmokeTest {

    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun freshInstall_emptyWardrobe_homeToLifeToCloset_rendersEmptyState() {
        // 1. Launch lands on the Life OS home; the bottom bar must be alive.
        compose.onNodeWithText("生活").assertExists().performClick()

        // 2. The module directory renders; 衣橱 is the single live row.
        compose.onNodeWithText("衣橱").assertExists().performClick()

        // 3. The Closie closet hosts inside the Life shell — its title proves the whole
        //    LifeDestination.Closet → ClosieNavHost → ClosetScreen chain survived.
        compose.onNodeWithText("我的衣橱").assertExists().assertIsDisplayed()
    }

    @Test
    fun freshInstall_closetIsEmpty_notBlankNotCrash() {
        compose.onNodeWithText("生活").performClick()
        compose.onNodeWithText("衣橱").performClick()

        // "Not blank" is part of the contract: the failure mode this test guards against is a
        // page that neither crashes nor shows anything. The empty wardrobe must still speak —
        // fresh install lands on the OWNED tab's empty state.
        compose.onNodeWithText("我的衣橱").assertExists()
        compose.onNodeWithText("衣橱还是空的").assertExists()
    }
}
