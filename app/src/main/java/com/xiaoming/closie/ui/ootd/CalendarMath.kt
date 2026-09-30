package com.xiaoming.closie.ui.ootd

import java.time.LocalDate
import java.time.YearMonth

/**
 * Pure calendar math for the OOTD month view. Sunday-first, 7 columns. No Android dependencies
 * so the mapping can be unit-tested (month starts Sunday/Monday, 28–31 days, leap years).
 */
object CalendarMath {

    /** Weekday headers, Sunday first, for a zh-CN calendar. */
    val weekdayLabels = listOf("日", "一", "二", "三", "四", "五", "六")

    /**
     * Returns a list whose length is a multiple of 7. The first element corresponds to the Sunday
     * of the week containing the 1st; days outside the month are represented as null.
     */
    fun monthCells(yearMonth: YearMonth): List<LocalDate?> {
        val first = yearMonth.atDay(1)
        // ISO dayOfWeek: Monday=1 … Sunday=7. Sunday-first offset = value % 7 (Sunday → 0).
        val offset = first.dayOfWeek.value % 7
        val rows = ((offset + yearMonth.lengthOfMonth() + 6) / 7) * 7
        return List(rows) { i ->
            val day = i - offset + 1
            if (day in 1..yearMonth.lengthOfMonth()) yearMonth.atDay(day) else null
        }
    }
}
