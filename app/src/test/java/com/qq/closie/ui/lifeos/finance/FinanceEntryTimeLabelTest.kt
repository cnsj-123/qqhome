package com.qq.closie.ui.lifeos.finance

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class FinanceEntryTimeLabelTest {
    @Test fun compactTimeKeepsTodayYesterdayAndHistoricalYearsDistinct() {
        val today=LocalDate.of(2026,11,6)
        assertEquals("今天 17:30",financeEntryTimeLabel("2026-11-06 17:30",today))
        assertEquals("昨天 23:59",financeEntryTimeLabel("2026-11-05 23:59",today))
        assertEquals("11月4日 00:01",financeEntryTimeLabel("2026-11-04 00:01",today))
        assertEquals("2025年11月6日 17:30",financeEntryTimeLabel("2025-11-06 17:30",today))
        assertEquals("昨天 23:59",financeEntryTimeLabel("2025-12-31 23:59",LocalDate.of(2026,1,1)))
    }

    @Test fun invalidRestoredDraftTimeStaysVisibleForCorrection() {
        val invalid="2026-02-30 12:00"
        assertEquals(invalid,financeEntryTimeLabel(invalid,LocalDate.of(2026,3,1)))
    }
}
