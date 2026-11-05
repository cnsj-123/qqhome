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

/** Shell wiring for Home's date projection only. Domain state belongs to each module. */
@OptIn(ExperimentalCoroutinesApi::class)
class LifeOsShellViewModel(
    provider: HomeStateProvider = DefaultHomeStateProvider()
) : ViewModel() {
    private val selectedDate = MutableStateFlow(LocalDate.now())
    val home = selectedDate.flatMapLatest { date ->
        provider.stateFor(date).onStart { emit(HomeUiState(date, isLoading = true)) }
            .catch { emit(HomeUiState(date, loadFailed = true)) }
    }.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000),
        HomeUiState(selectedDate.value, isLoading = true)
    )

    fun selectDate(date: LocalDate) { selectedDate.value = date }
}
