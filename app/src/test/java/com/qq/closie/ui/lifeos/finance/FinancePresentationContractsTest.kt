package com.qq.closie.ui.lifeos.finance

import com.qq.closie.life.finance.FinanceDateRange
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class FinancePresentationContractsTest {
    @Test fun `previous months respect leap years and differing month lengths`() {
        val zone = ZoneId.of("UTC")
        assertEquals(FinanceDateRange.month(LocalDate.of(2024, 2, 1), zone),
            previousFinanceRange(FinanceDateRange.month(LocalDate.of(2024, 3, 1), zone), zone))
        assertNull(previousFinanceRange(FinanceDateRange(), zone))
    }
    @Test fun `custom period comparison uses local days across daylight saving`() {
        val zone = ZoneId.of("America/New_York")
        val start = LocalDate.of(2024, 3, 9)
        assertEquals(FinanceDateRange.days(start.minusDays(3), start.minusDays(1), zone),
            previousFinanceRange(FinanceDateRange.days(start, start.plusDays(2), zone), zone))
    }
    @Test fun `zero and negative comparison bases never invent a percentage`() {
        assertEquals("较上期 +50.0%", periodChange(150, 100))
        assertFalse(periodChange(150, 0).contains("%"))
        assertFalse(periodChange(150, -100).contains("%"))
    }
    @Test fun `import diagnostics do not expose exception payloads containing cells`() {
        ImportPhase.entries.forEach { phase ->
            assertFalse(importFailure(phase, IllegalArgumentException("synthetic-private-cell")).contains("synthetic-private-cell"))
        }
        assertEquals("工作簿缺少必需部分", importFailure(ImportPhase.PARSE, IllegalArgumentException("工作簿缺少必需部分")))
    }
}
