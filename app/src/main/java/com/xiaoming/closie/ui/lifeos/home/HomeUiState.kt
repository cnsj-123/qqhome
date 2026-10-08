package com.xiaoming.closie.ui.lifeos.home

import com.xiaoming.closie.ui.lifeos.media.LifeMediaUi
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

data class HomeFactUi(val sourceId: String, val title: String, val domainLabel: String)
data class HistoryItemUi(val sourceId: String, val date: LocalDate, val title: String)
data class FutureIntentUi(val sourceId: String, val expectedDate: LocalDate?, val title: String)

data class HomeUiState(
    val date: LocalDate,
    val eligibleMedia: List<LifeMediaUi> = emptyList(),
    val todayFacts: List<HomeFactUi> = emptyList(),
    val historyItems: List<HistoryItemUi> = emptyList(),
    val futureIntents: List<FutureIntentUi> = emptyList(),
    val isLoading: Boolean = false
)

interface HomeStateProvider { fun stateFor(date: LocalDate): Flow<HomeUiState> }

/** Task 1 production provider: no seeds, media scan, or WardrobeRepository dependency. */
class DefaultHomeStateProvider : HomeStateProvider {
    override fun stateFor(date: LocalDate): Flow<HomeUiState> = flowOf(HomeUiState(date))
}
