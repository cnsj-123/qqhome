package com.qq.closie.ui.lifeos.home

import com.qq.closie.life.repository.HomePhotoRepository
import com.qq.closie.ui.lifeos.media.LifeMediaUi
import com.qq.closie.ui.lifeos.media.MediaSourceState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import java.time.LocalDate

/** Only explicit Home links are projected. Finance purpose grants remain independent. */
class MediaHomeStateProvider(private val base: HomeStateProvider, private val photos: HomePhotoRepository) : HomeStateProvider {
    override fun stateFor(date: LocalDate): Flow<HomeUiState> = combine(base.stateFor(date), photos.observe()) { state, selected ->
        fun image(photo: com.qq.closie.life.repository.HomePhotoReference) = LifeMediaUi(photo.id, photo.uri,
            title = photo.name ?: "生活照片",
            sourceState = if (photo.uri == null) MediaSourceState.SOURCE_MISSING else MediaSourceState.AVAILABLE)
        state.copy(eligibleMedia = selected.filter { it.date == date }.map(::image),
            historyItems = (state.historyItems + selected.filter { it.date.isBefore(date) }
                .take(6).map { HistoryItemUi(it.id, it.date, it.name ?: "收好的一张照片", image(it)) })
                .sortedByDescending { it.date },
            calendarDays = state.calendarDays.toMutableMap().apply {
                selected.groupBy { it.date }.forEach { (date, rows) ->
                    val day = get(date) ?: com.qq.closie.ui.lifeos.calendar.CalendarDayUi(date)
                    put(date, day.copy(photos = day.photos + rows.map(::image)))
                }
            })
    }
}
