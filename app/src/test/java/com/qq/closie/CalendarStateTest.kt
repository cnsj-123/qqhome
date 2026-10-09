package com.qq.closie

import com.qq.closie.ui.lifeos.calendar.*
import com.qq.closie.ui.lifeos.media.LifeMediaUi
import com.qq.closie.ui.lifeos.media.MediaSourceState
import java.time.YearMonth
import org.junit.Assert.*
import org.junit.Test

class CalendarStateTest {
    private val month = YearMonth.of(2026, 10)
    private val date = month.atDay(8)

    @Test
    fun `empty days and content outside the displayed month keep the month empty`() {
        assertFalse(CalendarUiState(month).hasContentIn(month))
        val otherDate = month.plusMonths(1).atDay(8)
        val state = CalendarUiState(month, mapOf(
            date to CalendarDayUi(date),
            otherDate to CalendarDayUi(otherDate, events = listOf(CalendarEventUi("test", "TestOnly", "Test")))
        ))
        assertFalse(state.hasContentIn(month))
        assertTrue(state.hasContentIn(month.plusMonths(1)))
    }

    @Test
    fun `each projection content kind independently suppresses empty state`() {
        val days = listOf(
            CalendarDayUi(date, photos = listOf(LifeMediaUi("test-photo", null, sourceState = MediaSourceState.SOURCE_MISSING))),
            CalendarDayUi(date, events = listOf(CalendarEventUi("test-event", "TestOnly", "Test"))),
            CalendarDayUi(date, intents = listOf(CalendarIntentUi("test-intent", "TestOnly", "Test"))),
            CalendarDayUi(date, timedContent = listOf(TimeBearingContentUi("test-content", "TestOnly", "Test")))
        )
        days.forEach { day ->
            assertTrue(day.hasContent)
            assertTrue(CalendarUiState(month, mapOf(date to day)).hasContentIn(month))
        }
        assertFalse(CalendarDayUi(date).hasContent)
    }
}
