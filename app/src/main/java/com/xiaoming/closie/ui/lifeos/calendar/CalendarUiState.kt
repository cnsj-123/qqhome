package com.xiaoming.closie.ui.lifeos.calendar

import com.xiaoming.closie.ui.lifeos.media.LifeMediaUi
import java.time.LocalDate
import java.time.YearMonth

data class CalendarEventUi(val sourceId: String, val title: String, val domainLabel: String)
data class CalendarIntentUi(val sourceId: String, val title: String, val domainLabel: String)
data class TimeBearingContentUi(val sourceId: String, val title: String, val kindLabel: String)
data class CalendarDayUi(
    val date: LocalDate,
    val photos: List<LifeMediaUi> = emptyList(),
    val events: List<CalendarEventUi> = emptyList(),
    val intents: List<CalendarIntentUi> = emptyList(),
    val timedContent: List<TimeBearingContentUi> = emptyList()
) {
    val hasContent: Boolean
        get() = photos.isNotEmpty() || events.isNotEmpty() || intents.isNotEmpty() || timedContent.isNotEmpty()
}
/** Query projection contract: this screen never creates events or owns a calendar database. */
data class CalendarUiState(val month: YearMonth, val days: Map<LocalDate, CalendarDayUi> = emptyMap()) {
    fun hasContentIn(displayedMonth: YearMonth): Boolean =
        days.any { (date, day) -> YearMonth.from(date) == displayedMonth && day.hasContent }
}
