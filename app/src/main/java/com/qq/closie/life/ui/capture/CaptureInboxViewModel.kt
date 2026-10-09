package com.qq.closie.life.ui.capture

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.qq.closie.life.repository.CaptureRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.asStateFlow

@OptIn(ExperimentalCoroutinesApi::class)
class CaptureInboxViewModel(private val repository: CaptureRepository) : ViewModel() {
    private val _query = MutableStateFlow("")
    val query = _query.asStateFlow()
    val records = query.flatMapLatest { if (it.isBlank()) repository.observeAll() else repository.search(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    fun search(text: String) { _query.value = text }
}
