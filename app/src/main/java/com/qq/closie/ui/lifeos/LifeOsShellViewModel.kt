package com.qq.closie.ui.lifeos

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.qq.closie.ui.lifeos.home.DefaultHomeStateProvider
import com.qq.closie.ui.lifeos.home.HomeStateProvider
import com.qq.closie.ui.lifeos.home.HomeUiState
import java.time.LocalDate
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import com.qq.closie.data.backup.RestoreStartupGate

/** Shell wiring for Home's date projection only. Domain state belongs to each module. */
@OptIn(ExperimentalCoroutinesApi::class)
class LifeOsShellViewModel(
    provider: HomeStateProvider = DefaultHomeStateProvider()
) : ViewModel() {
    private val selectedDate = MutableStateFlow(LocalDate.now())
    val home = combine(selectedDate,RestoreStartupGate.state.map {it.status to it.restoreGeneration}.distinctUntilChanged()) {date,gate->date to gate}
        .flatMapLatest { (date,gate) ->
        val (status,generation)=gate
        if(status!=RestoreStartupGate.Status.READY) flowOf(HomeUiState(date,isLoading=true,restoreGeneration=generation))
        else provider.stateFor(date).map {it.copy(restoreGeneration=generation)}
            .onStart { emit(HomeUiState(date, isLoading = true,restoreGeneration=generation)) }
            .catch { emit(HomeUiState(date, loadFailed = true,failedSources=setOf("首页"),restoreGeneration=generation)) }
    }.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000),
        HomeUiState(selectedDate.value, isLoading = true)
    )

    fun selectDate(date: LocalDate) { selectedDate.value = date }
}
