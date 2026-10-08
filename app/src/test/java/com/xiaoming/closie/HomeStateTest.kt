package com.xiaoming.closie

import com.xiaoming.closie.ui.lifeos.home.*
import com.xiaoming.closie.ui.lifeos.media.*
import com.xiaoming.closie.ui.lifeos.calendar.CalendarUiState
import java.time.LocalDate
import java.time.YearMonth
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class HomeStateTest {
    @Test
    fun `production provider has no demo media facts history or future`() = runBlocking {
        val provider = DefaultHomeStateProvider()
        for (date in listOf(LocalDate.of(2026, 10, 8), LocalDate.of(2024, 2, 29), LocalDate.of(2030, 1, 1))) {
            val state = provider.stateFor(date).first()
            assertEquals(date, state.date)
            assertFalse(state.isLoading)
            assertTrue(state.eligibleMedia.isEmpty())
            assertTrue(state.todayFacts.isEmpty())
            assertTrue(state.historyItems.isEmpty())
            assertTrue(state.futureIntents.isEmpty())
        }
        assertTrue(CalendarUiState(YearMonth.of(2026, 10)).days.isEmpty())
    }

    @Test
    fun `missing source keeps presentation identity and motion contract`() {
        val photo = LifeMediaUi("source-id", null, mediaKind = LifeMediaKind.MOTION_PHOTO,
            motionAvailable = true, pairedVideoUri = "content://paired/video", sourceState = MediaSourceState.SOURCE_MISSING)
        val state = HomeUiState(LocalDate.of(2026, 10, 8), eligibleMedia = listOf(photo),
            futureIntents = listOf(FutureIntentUi("intent-id", null, "待定的打算")))
        assertEquals("source-id", state.eligibleMedia.single().id)
        assertEquals("content://paired/video", state.eligibleMedia.single().pairedVideoUri)
        assertTrue(state.todayFacts.isEmpty())
        assertTrue(state.historyItems.isEmpty())
        assertEquals("intent-id", state.futureIntents.single().sourceId)
    }
}
