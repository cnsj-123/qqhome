package com.qq.closie.life.finance

import org.junit.Assert.*
import org.junit.Test
import com.qq.closie.ui.lifeos.finance.FinanceTime

class FinanceMoneyTest {
    @Test fun decimalMoneyNeverUsesFloatingPointRounding() {
        assertEquals(1_234L, FinanceMoney.parse("12.34"))
        assertEquals(29L, FinanceMoney.parse("0.29"))
        assertEquals(Long.MAX_VALUE, FinanceMoney.parse("92233720368547758.07"))
        assertEquals("12.34", FinanceMoney.input(1_234))
        assertEquals("¥-12.34", FinanceMoney.display(-1_234))
        assertEquals(-1_234L, FinanceMoney.parse("-12.34", allowNegative = true))
        assertEquals(0L, FinanceMoney.parse("0", allowNegative = true))
    }
    @Test fun excessPrecisionInvalidAmountsAndOverflowAreRejected() {
        for (text in listOf("0", "-1", "12.345", "1.000", "1e2", "NaN", "92233720368547758.08", "")) {
            assertTrue(text, runCatching { FinanceMoney.parse(text) }.isFailure)
        }
    }
    @Test fun malformedCalendarDatesCannotBeSilentlyAdjusted() {
        assertTrue(runCatching { FinanceTime.parse("2026-02-30 12:30") }.isFailure)
        assertTrue(runCatching { FinanceTime.parse("2026-10-09 25:00") }.isFailure)
        assertEquals("2026-10-09 12:30", FinanceTime.input(FinanceTime.parse("2026-10-09 12:30")))
    }
    @Test fun untouchedMinuteDisplayPreservesExactOccurrenceWhileEditsUseExplicitTime() {
        val precise = FinanceTime.parse("2026-10-09 18:00") + 45_123
        assertEquals(precise, FinanceTime.resolve("2026-10-09 18:00", precise))
        assertEquals(FinanceTime.parse("2026-10-08 18:00"), FinanceTime.resolve("2026-10-08 18:00", precise))
        assertEquals(FinanceTime.parse("2026-10-09 18:00"), FinanceTime.resolve("2026-10-09 18:00", null))
        assertTrue(runCatching { FinanceTime.resolve("无效时间", precise) }.isFailure)
    }
}
