package com.qq.closie.life.ui.plan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.qq.closie.life.plan.PlanItemEntity
import com.qq.closie.life.repository.PlanRepository
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The four sections of 计划.
 *
 * 此前 exists because of a real hole in the first pass: an open item whose date had passed matched
 * neither 今天 (not today's date) nor 接下来 (`dueAt >= endOfDay` excluded it), so it vanished from
 * the app the day after it was due. The section is named 此前 — "before now" — rather than 逾期 on
 * purpose. 逾期 is an accusation; 此前 is a fact. Life OS records what the user meant to do, and a
 * late intention is still an intention.
 *
 * There is still no red anywhere: the section is ordered by date, carries no badge and no count
 * badge, and its rows look exactly like 接下来's.
 */
enum class PlanSection(val label: String) {
    TODAY("今天"),
    OVERDUE("此前"),
    UPCOMING("接下来"),
    COMPLETED("已完成")
}

/**
 * State holder for the Plans module only. Global Home queries have a separate projection owner.
 *
 * Like [com.qq.closie.life.ui.reference.ReferenceViewModel], this is a real ViewModel over
 * [PlanRepository] so no composable has to touch a DAO and the day-window logic stays in one place.
 */
class PlanViewModel(
    private val repository: PlanRepository,
    private val zone: ZoneId = ZoneId.systemDefault()
) : ViewModel() {

    /** 今天 — everything due today, finished ones included so completing does not make a row vanish. */
    val today: StateFlow<List<PlanItemEntity>> =
        repository.observeTodayIncludingDone(zone)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 接下来 — open items due later, plus undated "someday" items. */
    val upcoming: StateFlow<List<PlanItemEntity>> =
        repository.observeUpcoming(zone)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * 此前 — open items that fell due before today.
     *
     * A separate flow rather than a filter over [upcoming], because [upcoming]'s SQL deliberately
     * excludes them — the fix has to come from the query, not from re-filtering a list that never
     * contained them.
     */
    val overdue: StateFlow<List<PlanItemEntity>> =
        repository.observeOverdue(zone)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val completed: StateFlow<List<PlanItemEntity>> =
        repository.observeCompleted()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * True when the whole page is empty, so the screen can show one honest empty state instead of
     * four empty sections stacked on top of each other.
     */
    val isEmpty: StateFlow<Boolean> =
        combine(today, overdue, upcoming, completed) { t, o, u, c ->
            t.isEmpty() && o.isEmpty() && u.isEmpty() && c.isEmpty()
        }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    fun create(title: String, note: String?, dueAt: Long?) {
        if (title.isBlank()) return
        viewModelScope.launch { repository.create(title = title, note = note, dueAt = dueAt) }
    }

    fun update(item: PlanItemEntity, title: String, note: String?, dueAt: Long?, clearDue: Boolean) {
        viewModelScope.launch {
            if (clearDue) {
                // Clear the date first, then apply the rest — clearDueAt is the only path that can
                // distinguish "remove the date" from "leave it alone".
                repository.clearDueAt(item.id)
                repository.update(id = item.id, title = title, note = note)
            } else {
                repository.update(id = item.id, title = title, note = note, dueAt = dueAt)
            }
        }
    }

    fun complete(item: PlanItemEntity) {
        viewModelScope.launch { repository.complete(item.id) }
    }

    fun uncomplete(item: PlanItemEntity) {
        viewModelScope.launch { repository.uncomplete(item.id) }
    }

    fun delete(item: PlanItemEntity) {
        viewModelScope.launch { repository.delete(item.id) }
    }

    fun observeItem(id: String): Flow<PlanItemEntity?> = repository.observeById(id)
}
