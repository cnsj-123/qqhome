package com.qq.closie.ui.lifeos.home

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.qq.closie.ui.lifeos.theme.LifeOsTheme
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeDatePopupTest {
    @get:Rule val compose = createComposeRule()

    @Test fun selectingHistoricalDayClosesPopupAndRequestsThatExactHomeDate() {
        var selected: LocalDate? = null
        compose.setContent {
            var open by remember { mutableStateOf(true) }
            LifeOsTheme {
                if (open) HomeDatePopup(LocalDate.of(2026, 10, 12), emptyMap(),
                    { selected = it }, { open = false })
            }
        }
        compose.onNodeWithContentDescription("2026-10-08").performClick()
        compose.runOnIdle { assertEquals(LocalDate.of(2026, 10, 8), selected) }
        compose.onNodeWithContentDescription("关闭日期选择").assertDoesNotExist()
    }

    @Test fun cancellingYearMonthWheelDoesNotChangeHomeSelection() {
        var selected: LocalDate? = null
        var dismissed = false
        compose.setContent {
            LifeOsTheme { HomeDatePopup(LocalDate.of(2026, 10, 12), emptyMap(),
                { selected = it }, { dismissed = true }) }
        }
        compose.onNodeWithText("2026年10月").performClick()
        compose.onNodeWithContentDescription("年份").assertExists()
        compose.onNodeWithContentDescription("月份").assertExists()
        compose.onNodeWithContentDescription("关闭日期选择").performClick()
        compose.runOnIdle { assertTrue(dismissed); assertNull(selected) }
    }
}
