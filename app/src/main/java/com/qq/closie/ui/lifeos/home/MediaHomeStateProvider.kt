package com.qq.closie.ui.lifeos.home

import com.qq.closie.life.repository.HomePhotoRepository
import com.qq.closie.life.repository.HomePhotoReference
import com.qq.closie.ui.lifeos.media.LifeMediaUi
import com.qq.closie.ui.lifeos.media.MediaSourceState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.catch
import java.time.LocalDate

/** Only explicit Home links are projected. Finance purpose grants remain independent. */
class MediaHomeStateProvider(private val base: HomeStateProvider, private val photos: HomePhotoRepository) : HomeStateProvider {
    private data class Read(val photos:List<HomePhotoReference> = emptyList(),val loading:Boolean=false,val failed:Boolean=false)
    override fun stateFor(date: LocalDate): Flow<HomeUiState> = combine(base.stateFor(date), photos.observe().map {Read(it)}
        .onStart {emit(Read(loading=true))}.catch {emit(Read(failed=true))}) { state, read ->
        val selected=read.photos
        fun image(photo: HomePhotoReference) = LifeMediaUi(photo.id, photo.uri,
            sourceState = if (photo.uri == null) MediaSourceState.SOURCE_MISSING else MediaSourceState.AVAILABLE,
            aspectRatio = photo.aspectRatio, caption = photo.caption)
        state.copy(eligibleMedia = selected.filter { it.date == date }.map(::image),
            historyItems = (state.historyItems + selected.filter { it.date.isBefore(date) }
                .map { HistoryItemUi(it.id, it.date, title = null, photo = image(it)) })
                .sortedByDescending { it.date },
            calendarDays = state.calendarDays.toMutableMap().apply {
                selected.groupBy { it.date }.forEach { (date, rows) ->
                    val day = get(date) ?: com.qq.closie.ui.lifeos.calendar.CalendarDayUi(date)
                    put(date, day.copy(photos = day.photos + rows.map(::image),timedContent=day.timedContent+
                        rows.filter {!it.caption.isNullOrBlank()}.map {
                            com.qq.closie.ui.lifeos.calendar.TimeBearingContentUi(it.id,it.caption!!,"照片备注")
                        }))
                }
            }).sourceStatus("照片",read.loading,read.failed)
    }
}
