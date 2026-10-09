package com.xiaoming.closie.ui.lifeos.calendar

import java.time.LocalDate
import java.time.YearMonth

/** Pure Sunday-first date math for the Life Calendar projection, independent of feature UI. */
object MonthGrid {
    fun cells(month: YearMonth): List<LocalDate?> {
        val offset = month.atDay(1).dayOfWeek.value % 7
        val length = month.lengthOfMonth()
        val cellCount = ((offset + length + 6) / 7) * 7
        return List(cellCount) { index ->
            val day = index - offset + 1
            if (day in 1..length) month.atDay(day) else null
        }
    }

    fun previous(month: YearMonth): YearMonth = month.minusMonths(1)
    fun next(month: YearMonth): YearMonth = month.plusMonths(1)
}
