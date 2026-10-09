package com.qq.closie

import com.qq.closie.ui.lifeos.calendar.MonthGrid
import java.time.YearMonth
import org.junit.Assert.*
import org.junit.Test

class MonthGridTest {
    @Test
    fun `Sunday starts in the first cell without leading blanks`() {
        val month = YearMonth.of(2026, 3)
        val cells = MonthGrid.cells(month)
        assertEquals(month.atDay(1), cells.first())
        assertEquals(0, cells.indexOf(month.atDay(1)))
        assertEquals(0, cells.size % 7)
    }

    @Test
    fun `Monday and Saturday starts have correct leading offsets`() {
        for ((month, offset) in listOf(YearMonth.of(2026, 6) to 1, YearMonth.of(2026, 8) to 6)) {
            val cells = MonthGrid.cells(month)
            assertEquals(offset, cells.indexOf(month.atDay(1)))
            assertTrue(cells.take(offset).all { it == null })
            assertEquals((1..month.lengthOfMonth()).map(month::atDay), cells.filterNotNull())
            assertEquals(0, cells.size % 7)
            assertTrue(cells.drop(offset + month.lengthOfMonth()).all { it == null })
        }
    }

    @Test
    fun `leap year and century boundaries retain the correct month length`() {
        for ((year, length) in listOf(2024 to 29, 2025 to 28, 2000 to 29, 2100 to 28)) {
            val month = YearMonth.of(year, 2)
            val dates = MonthGrid.cells(month).filterNotNull()
            assertEquals(length, dates.size)
            assertEquals(month.atDay(1), dates.first())
            assertEquals(month.atDay(length), dates.last())
        }
    }

    @Test
    fun `previous and next cross year boundaries and are inverse`() {
        val january = YearMonth.of(2026, 1)
        val december = YearMonth.of(2025, 12)
        assertEquals(december, MonthGrid.previous(january))
        assertEquals(january, MonthGrid.next(december))
        assertEquals(january, MonthGrid.next(MonthGrid.previous(january)))
        assertEquals(december, MonthGrid.previous(MonthGrid.next(december)))
    }
}
