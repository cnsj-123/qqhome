package com.xiaoming.closie.ui.lifeos

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xiaoming.closie.ui.lifeos.home.DefaultHomeStateProvider
import com.xiaoming.closie.ui.lifeos.home.HomeStateProvider
import com.xiaoming.closie.ui.lifeos.home.HomeUiState
import java.time.LocalDate
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn

/** Shell wiring for Home's date projection only. Domain state belongs to each module. */
@OptIn(ExperimentalCoroutinesApi::class)
class LifeOsShellViewModel(
    provider: HomeStateProvider = DefaultHomeStateProvider()
) : ViewModel() {
    private val selectedDate = MutableStateFlow(LocalDate.now())
    val home = selectedDate.flatMapLatest(provider::stateFor).stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000),
        HomeUiState(selectedDate.value, isLoading = true)
    )

    fun selectDate(date: LocalDate) { selectedDate.value = date }
}
