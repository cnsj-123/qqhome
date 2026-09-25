package com.qq.closie.life.ui.reference

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.qq.closie.life.reference.ReferenceItemEntity
import com.qq.closie.life.reference.ReferenceStatus
import com.qq.closie.life.reference.ReferenceType
import com.qq.closie.life.repository.MediaRepository
import com.qq.closie.life.repository.ReferenceRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Filter bar state for the 资料库 list.
 *
 * [ALL] plus one entry per [ReferenceStatus] — three chips, not a faceted search panel. The point
 * of the filter is "show me what still needs filing", and anything more elaborate would invite the
 * user to spend their time organising the organiser.
 */
enum class ReferenceFilter(val label: String, val status: ReferenceStatus?) {
    ALL("全部", null),
    INBOX("待整理", ReferenceStatus.INBOX),
    ORGANIZED("已整理", ReferenceStatus.ORGANIZED),
    ARCHIVED("已归档", ReferenceStatus.ARCHIVED)
}

/**
 * State holder for the 资料库.
 *
 * This is a plain [ViewModel] over [ReferenceRepository] rather than a "read the DAO in the
 * composable" shortcut: search state, the active filter and the detail's tag flow all need to
 * survive recomposition, and putting them in [StateFlow]s here is what keeps the screens free of
 * `remember { }` bookkeeping and testable without a Compose runtime.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReferenceViewModel(
    private val repository: ReferenceRepository,
    private val mediaRepository: MediaRepository? = null
) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _filter = MutableStateFlow(ReferenceFilter.ALL)
    val filter: StateFlow<ReferenceFilter> = _filter.asStateFlow()

    /**
     * The visible list: search term applied first, then the status filter.
     *
     * `flatMapLatest` on the pair means a fast typist cancels the previous query instead of racing
     * it — the list can never settle on the results of a stale keystroke.
     */
    val items: StateFlow<List<ReferenceItemEntity>> =
        combine(_query, _filter) { q, f -> q to f }
            .flatMapLatest { (q, f) ->
                when {
                    q.isNotBlank() && f.status != null -> repository.searchByStatus(q, f.status)
                    q.isNotBlank() -> repository.search(q)
                    f.status != null -> repository.observeByStatus(f.status)
                    else -> repository.observeAll()
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Counts per filter, so each chip can show a number when it is meaningful. */
    val allCount: StateFlow<Int> = repository.observeAll()
        .map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val inboxCount: StateFlow<Int> = repository.observeInboxCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /**
     * 阅读's own list — READING + ARTICLE, archived excluded, straight from the DAO.
     *
     * Deliberately **not** derived from [items]. [items] has the 资料库 search box and status filter
     * applied, so filtering it for 阅读 meant the reading list silently inherited whatever the user
     * happened to be doing in the library: type a search there, switch to 阅读, and articles vanish
     * with no indication why. Two screens over one dataset are still two screens, and 阅读 owns its
     * own query so its contents depend on nothing but the data.
     *
     * This is the flow [com.qq.closie.life.data.database.dao.ReferenceDao.observeActiveReading] was
     * written for; before this it had no production caller at all.
     */
    val readingItems: StateFlow<List<ReferenceItemEntity>> =
        repository.observeReading()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun onQueryChange(value: String) {
        _query.value = value
    }

    fun onFilterChange(value: ReferenceFilter) {
        _filter.value = value
    }

    fun observeItem(id: String): Flow<ReferenceItemEntity?> = repository.observeById(id)

    fun observeTags(item: ReferenceItemEntity) = repository.observeTagsFor(item)

    /**
     * Thumbnails for the visible list, keyed by a reference's `lifeEntityId`.
     *
     * The 资料库 row and the detail page both render an image, and neither had one: `media = null`
     * was hardcoded in both, so a screenshot saved from 相册 or 存进资料库 showed up as a title with
     * no visible image anywhere in the app — the media was imported, copied and linked, and then
     * never drawn. The row component already took a Coil `model`; nothing supplied it.
     *
     * Resolved here rather than in the composable because the lookup is a suspend DB read per
     * reference and must not run on the composition thread. `mapLatest` cancels the previous pass so
     * a fast scroll or a filter switch cannot interleave two datasets and paint one item's photo on
     * another's row.
     *
     * Keyed by `lifeEntityId` — the owner id that [MediaRepository.observeLinksForOwner] expects;
     * `reference.id` would silently find nothing.
     *
     * Only a *managed* file path is returned. A revoked picker URI must never reach Coil, because
     * the failure mode is a permanently broken thumbnail rather than an absent one.
     */
    val thumbnails: StateFlow<Map<String, String>> =
        items
            .mapLatest { list -> list.map { it.lifeEntityId } }
            .mapLatest { ownerIds ->
                val media = mediaRepository ?: return@mapLatest emptyMap()
                val result = LinkedHashMap<String, String>(ownerIds.size)
                ownerIds.forEach { ownerEntityId ->
                    // `getLinksForOwner` (suspend, once) rather than collecting the Flow and taking
                    // its first emission: this is a snapshot read inside a `mapLatest`, not a
                    // subscription, and collecting would leave a hot collector per row alive for the
                    // lifetime of the ViewModel.
                    val assetId = media.getLinksForOwner(ownerEntityId).firstOrNull()?.mediaAssetId
                    if (assetId != null) {
                        media.managedImagePathFor(assetId)?.let { result[ownerEntityId] = it }
                    }
                }
                result
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** The managed image path for one reference's LifeEntity — the detail page's own lookup. */
    suspend fun thumbnailFor(ownerEntityId: String): String? {
        val media = mediaRepository ?: return null
        val assetId = media.getLinksForOwner(ownerEntityId).firstOrNull()?.mediaAssetId ?: return null
        return media.managedImagePathFor(assetId)
    }

    fun addTag(item: ReferenceItemEntity, name: String) {
        if (name.isBlank()) return
        viewModelScope.launch { repository.addTag(item.id, name) }
    }

    fun removeTag(item: ReferenceItemEntity, tagId: String) {
        viewModelScope.launch { repository.removeTag(item.id, tagId) }
    }

    fun archive(item: ReferenceItemEntity) {
        viewModelScope.launch { repository.archive(item.id) }
    }

    fun unarchive(item: ReferenceItemEntity) {
        viewModelScope.launch { repository.unarchive(item.id) }
    }

    fun markOrganized(item: ReferenceItemEntity) {
        viewModelScope.launch { repository.markOrganized(item.id) }
    }

    fun delete(item: ReferenceItemEntity) {
        viewModelScope.launch { repository.delete(item.id) }
    }

    fun update(
        item: ReferenceItemEntity,
        title: String,
        summary: String?,
        sourceUrl: String?,
        author: String?,
        type: ReferenceType
    ) {
        viewModelScope.launch {
            repository.update(
                id = item.id,
                title = title,
                summary = summary,
                sourceUrl = sourceUrl,
                author = author,
                referenceType = type
            )
        }
    }
}
