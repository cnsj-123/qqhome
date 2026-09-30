package com.xiaoming.closie

import com.xiaoming.closie.ui.ootd.CalendarMath
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CalendarMathTest {

    private fun nonNull(cells: List<java.time.LocalDate?>) = cells.filterNotNull()

    @Test
    fun `every month grid is a multiple of 7 cells`() {
        for (ym in listOf(YearMonth.of(2024, 9), YearMonth.of(2024, 4), YearMonth.of(2023, 2))) {
            assertTrue("${ym} should be multiple of 7", CalendarMath.monthCells(ym).size % 7 == 0)
        }
    }

    @Test
    fun `grid contains exactly the days of the month`() {
        assertEquals(31, nonNull(CalendarMath.monthCells(YearMonth.of(2024, 5))).size)
        assertEquals(30, nonNull(CalendarMath.monthCells(YearMonth.of(2024, 4))).size)
        assertEquals(28, nonNull(CalendarMath.monthCells(YearMonth.of(2023, 2))).size)
    }

    @Test
    fun `leap year february has 29 days`() {
        assertEquals(29, nonNull(CalendarMath.monthCells(YearMonth.of(2024, 2))).size)
    }

    @Test
    fun `month starting on sunday has no leading blanks`() {
        // 2024-09-01 is a Sunday.
        val cells = CalendarMath.monthCells(YearMonth.of(2024, 9))
        assertNotNull(cells[0])
        assertEquals(java.time.LocalDate.of(2024, 9, 1), cells[0])
    }

    @Test
    fun `month starting on monday has one leading blank`() {
        // 2024-04-01 is a Monday.
        val cells = CalendarMath.monthCells(YearMonth.of(2024, 4))
        assertNull(cells[0])
        assertEquals(java.time.LocalDate.of(2024, 4, 1), cells[1])
    }

    @Test
    fun `first non-null cell is always the first of the month`() {
        for (ym in listOf(YearMonth.of(2024, 1), YearMonth.of(2024, 6), YearMonth.of(2025, 3))) {
            assertEquals(ym.atDay(1), nonNull(CalendarMath.monthCells(ym)).first())
        }
    }
}
