package com.qq.closie.life.finance

import org.junit.Assert.*
import org.junit.Test

class FinanceReviewDeliveryTest {
    @Test fun enhancementNeverSuppressesTheModeReminderAndForegroundIsNotDuplicated() {
        FinanceReviewDeliveryMode.entries.forEach { mode ->
            listOf(false, true).forEach { enhanced -> listOf(false, true).forEach { permission ->
                val plan = FinanceReviewDeliveryPolicy.plan(false, mode, enhanced, permission)
                assertEquals(mode.takeUnless { it == FinanceReviewDeliveryMode.INBOX_ONLY }, plan.notificationMode)
                assertEquals(mode == FinanceReviewDeliveryMode.IMMEDIATE && enhanced && permission, plan.enhancedOverlay)
                val foreground = FinanceReviewDeliveryPolicy.plan(true, mode, enhanced, permission)
                assertTrue(foreground.inApp)
                assertNull(foreground.notificationMode)
                assertFalse(foreground.enhancedOverlay)
            } }
        }
        assertEquals(FinanceReviewDeliveryMode.INBOX_ONLY, FinanceReviewDeliveryMode.fromV14Background(false))
        assertEquals(FinanceReviewDeliveryMode.NORMAL, FinanceReviewDeliveryMode.fromV14Background(true))
    }
}
