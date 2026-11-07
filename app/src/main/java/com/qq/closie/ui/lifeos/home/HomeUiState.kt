package com.qq.closie.ui.lifeos.home

import com.qq.closie.ui.lifeos.media.LifeMediaUi
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

data class HomeFactUi(val sourceId: String, val title: String, val domainLabel: String)
data class HistoryItemUi(val sourceId: String, val date: LocalDate, val title: String?, val photo: LifeMediaUi? = null)
data class FutureIntentUi(val sourceId: String, val expectedDate: LocalDate?, val title: String, val note:String?=null)

data class HomeUiState(
    val date: LocalDate,
    val eligibleMedia: List<LifeMediaUi> = emptyList(),
    val todayFacts: List<HomeFactUi> = emptyList(),
    val historyItems: List<HistoryItemUi> = emptyList(),
    val futureIntents: List<FutureIntentUi> = emptyList(),
    val isLoading: Boolean = false,
    val calendarDays: Map<LocalDate, com.qq.closie.ui.lifeos.calendar.CalendarDayUi> = emptyMap(),
    val loadFailed: Boolean = false,
    val loadingSources:Set<String> = emptySet(),
    val failedSources:Set<String> = emptySet(),
    val restoreGeneration:Long = 0L
)

interface HomeStateProvider { fun stateFor(date: LocalDate): Flow<HomeUiState> }

internal fun HomeUiState.sourceStatus(source:String,loading:Boolean=false,failed:Boolean=false):HomeUiState {
    val pending=(loadingSources-source)+if(loading)setOf(source) else emptySet()
    val errors=(failedSources-source)+if(failed)setOf(source) else emptySet()
    val content=eligibleMedia.isNotEmpty() || todayFacts.isNotEmpty() || historyItems.isNotEmpty() || futureIntents.isNotEmpty()
    return copy(loadingSources=pending,failedSources=errors,isLoading=pending.isNotEmpty() && !content,
        loadFailed=errors.isNotEmpty() && pending.isEmpty() && !content)
}

/** Task 1 production provider: no seeds, media scan, or WardrobeRepository dependency. */
class DefaultHomeStateProvider : HomeStateProvider {
    override fun stateFor(date: LocalDate): Flow<HomeUiState> = flowOf(HomeUiState(date))
}
