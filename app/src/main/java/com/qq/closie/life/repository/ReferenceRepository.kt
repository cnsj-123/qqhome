package com.qq.closie.life.repository

import androidx.room.withTransaction
import com.qq.closie.life.core.TagEntity
import com.qq.closie.life.data.database.LifeDatabase
import com.qq.closie.life.data.database.dao.ReferenceDao
import com.qq.closie.life.repository.MediaRepository
import com.qq.closie.life.reference.ReferenceEntityType
import com.qq.closie.life.reference.ReferenceItemEntity
import com.qq.closie.life.reference.ReferenceStatus
import com.qq.closie.life.reference.ReferenceType
import java.util.UUID
import kotlinx.coroutines.flow.Flow

/**
 * The 资料库 repository — where a screenshot or a link becomes information a user can find again.
 *
 * Two rules define this class:
 *
 *  1. **Every reference is a real LifeEntity.** [create] writes the [ReferenceItemEntity] *and* its
 *     backing LifeEntity row in one transaction, so a reference is always a first-class citizen of
 *     the life graph — taggable, relatable, and able to carry media through the existing
 *     infrastructure. A reference that existed only in `reference_items` would be invisible to tags,
 *     relations and media links, which would make the whole "one graph" design a lie.
 *
 *  2. **Deleting a reference follows LifeEntity soft delete.** [delete] marks the LifeEntity's
 *     `deletedAt` and removes the strongly-typed row. It never cascades into media: a photo may be
 *     linked to a trip and a plant as well, so erasing it here could destroy someone else's content.
 *     Orphaned media is left for a future collector — see the class docs on [MediaRepository].
 *
 * UI must never touch [ReferenceDao] directly; this is the only gateway.
 */
class ReferenceRepository(
    private val database: LifeDatabase,
    private val lifeRepository: LifeRepository,
    private val mediaRepository: MediaRepository,
) {

    private val dao: ReferenceDao = database.referenceDao()

    // ------------------------------------------------------------------
    //  Create
    // ------------------------------------------------------------------

    /**
     * Creates a reference together with its LifeEntity.
     *
     * @param originalCaptureId the capture this was distilled from, if any. It is verified to exist
     *   before being stored, because there is no physical foreign key on that column and a dangling
     *   id would be permanent — captures are never cascade-deleted. An unknown id is stored as null
     *   rather than rejected: the reference content is what matters, and losing the provenance link
     *   is strictly better than losing the user's saved article.
     */
    suspend fun create(
        title: String,
        referenceType: ReferenceType,
        summary: String? = null,
        ocrText: String? = null,
        sourceUrl: String? = null,
        sourceName: String? = null,
        author: String? = null,
        originalCaptureId: String? = null,
        status: ReferenceStatus = ReferenceStatus.INBOX,
        id: String = UUID.randomUUID().toString(),
        now: Long = System.currentTimeMillis()
    ): ReferenceItemEntity = database.withTransaction {
        val entity = lifeRepository.createEntity(
            entityType = ReferenceEntityType.REFERENCE,
            timestamp = now
        )
        val verifiedCaptureId = originalCaptureId
            ?.takeIf { database.captureDao().getById(it) != null }
        val item = ReferenceItemEntity(
            id = id,
            lifeEntityId = entity.id,
            title = title.trim(),
            referenceType = referenceType,
            summary = summary?.trim()?.takeIf { it.isNotEmpty() },
            ocrText = ocrText?.takeIf { it.isNotBlank() },
            sourceUrl = sourceUrl?.trim()?.takeIf { it.isNotEmpty() },
            sourceName = sourceName?.trim()?.takeIf { it.isNotEmpty() },
            author = author?.trim()?.takeIf { it.isNotEmpty() },
            originalCaptureId = verifiedCaptureId,
            status = status,
            createdAt = now,
            updatedAt = now,
            // A reference created directly as ORGANIZED (e.g. from a confirmed review screen) is
            // already filed, so it gets its organisedAt stamp up front.
            organizedAt = if (status == ReferenceStatus.INBOX) null else now
        )
        dao.insert(item)
        item
    }

    // ------------------------------------------------------------------
    //  Read
    // ------------------------------------------------------------------

    suspend fun getById(id: String): ReferenceItemEntity? = dao.getById(id)

    fun observeById(id: String): Flow<ReferenceItemEntity?> = dao.observeById(id)

    fun observeAll(): Flow<List<ReferenceItemEntity>> = dao.observeAll()

    fun observeByStatus(status: ReferenceStatus): Flow<List<ReferenceItemEntity>> =
        dao.observeByStatus(status)

    fun observeRecent(limit: Int = 20): Flow<List<ReferenceItemEntity>> = dao.observeRecent(limit)

    /** 阅读 module source: READING + ARTICLE, archived items excluded. */
    fun observeReading(): Flow<List<ReferenceItemEntity>> = dao.observeActiveReading()

    suspend fun count(): Int = dao.count()

    suspend fun countInbox(): Int = dao.countInbox()

    fun observeInboxCount(): Flow<Int> = dao.observeInboxCount()

    suspend fun findByOriginalCaptureId(captureId: String): ReferenceItemEntity? =
        dao.findByOriginalCaptureId(captureId)

    /** Distinct types actually present — drives an honest type filter. */
    suspend fun distinctTypes(): List<ReferenceType> = dao.distinctTypes()

    // ------------------------------------------------------------------
    //  Search
    // ------------------------------------------------------------------

    /**
     * Local search across title / summary / extracted text / source name / source URL.
     *
     * The term is escaped for `LIKE` before it reaches SQL: a bare `%` or `_` typed by the user
     * would otherwise act as a wildcard and turn "50%" into "matches everything". Backslash is the
     * escape character, so it must itself be escaped first — doing it in the other order would
     * double-escape the sequences the first pass produced.
     */
    fun search(query: String): Flow<List<ReferenceItemEntity>> {
        val escaped = escapeLike(query.trim())
        if (escaped.isEmpty()) return dao.observeAll()
        return dao.search(escaped)
    }

    fun searchByStatus(query: String, status: ReferenceStatus): Flow<List<ReferenceItemEntity>> {
        val escaped = escapeLike(query.trim())
        if (escaped.isEmpty()) return dao.observeByStatus(status)
        return dao.searchByStatus(escaped, status)
    }

    internal fun escapeLike(raw: String): String = raw
        .replace("\\", "\\\\")
        .replace("%", "\\%")
        .replace("_", "\\_")

    // ------------------------------------------------------------------
    //  Edit
    // ------------------------------------------------------------------

    /** Field-level update the edit screen uses. Only non-null arguments are applied. */
    suspend fun update(
        id: String,
        title: String? = null,
        summary: String? = null,
        ocrText: String? = null,
        sourceUrl: String? = null,
        sourceName: String? = null,
        author: String? = null,
        referenceType: ReferenceType? = null,
        now: Long = System.currentTimeMillis()
    ): ReferenceItemEntity? = database.withTransaction {
        val current = dao.getById(id) ?: return@withTransaction null
        val updated = current.copy(
            title = title?.trim() ?: current.title,
            summary = summary?.trim()?.takeIf { it.isNotEmpty() } ?: if (summary != null) null else current.summary,
            ocrText = ocrText ?: current.ocrText,
            sourceUrl = sourceUrl?.trim()?.takeIf { it.isNotEmpty() } ?: if (sourceUrl != null) null else current.sourceUrl,
            sourceName = sourceName?.trim()?.takeIf { it.isNotEmpty() } ?: if (sourceName != null) null else current.sourceName,
            author = author?.trim()?.takeIf { it.isNotEmpty() } ?: if (author != null) null else current.author,
            referenceType = referenceType ?: current.referenceType,
            updatedAt = now
        )
        dao.update(updated)
        // Bump the LifeEntity revision too, so the audit trail sees the edit as a meaningful change
        // and a future sync layer has something to diff.
        lifeRepository.getEntity(current.lifeEntityId)?.let {
            lifeRepository.updateEntity(it, now)
        }
        updated
    }

    /**
     * Replaces the full extracted text — used by the OCR review screen and by re-imports.
     *
     * Bumps the backing LifeEntity's revision, exactly like [update] and [setStatus] do. The text
     * this writes is searchable content, so "the OCR pass finished and filled in the body" is a
     * real change to the entity, not a bookkeeping detail: skipping the bump left the audit trail
     * claiming the entity had not changed since creation, and would have made a future sync layer
     * treat new OCR output as already-synced.
     */
    suspend fun updateOcrText(id: String, text: String?, now: Long = System.currentTimeMillis()): Boolean =
        database.withTransaction {
            val current = dao.getById(id) ?: return@withTransaction false
            dao.update(current.copy(ocrText = text?.takeIf { it.isNotBlank() }, updatedAt = now))
            lifeRepository.getEntity(current.lifeEntityId)?.let {
                lifeRepository.updateEntity(it, now)
            }
            true
        }

    // ------------------------------------------------------------------
    //  Status
    // ------------------------------------------------------------------

    /** 待整理 → 已整理. Stamps [ReferenceItemEntity.organizedAt] on the first pass only. */
    suspend fun markOrganized(id: String, now: Long = System.currentTimeMillis()): Boolean =
        setStatus(id, ReferenceStatus.ORGANIZED, now)

    /** Moves an item out of the working list without deleting it. */
    suspend fun archive(id: String, now: Long = System.currentTimeMillis()): Boolean =
        setStatus(id, ReferenceStatus.ARCHIVED, now)

    /** Brings an archived item back into the inbox. */
    suspend fun unarchive(id: String, now: Long = System.currentTimeMillis()): Boolean =
        setStatus(id, ReferenceStatus.INBOX, now)

    private suspend fun setStatus(id: String, status: ReferenceStatus, now: Long): Boolean {
        val current = dao.getById(id) ?: return false
        if (current.status == status) return true
        // organizedAt records the FIRST time this item left the inbox. Restoring an archived item
        // must not erase that history, and re-organising must not keep pushing the date forward.
        val organizedAt = when {
            status == ReferenceStatus.INBOX -> null
            else -> current.organizedAt ?: now
        }
        val changed = dao.updateStatus(id, status, organizedAt, now) > 0
        if (changed) {
            lifeRepository.getEntity(current.lifeEntityId)?.let {
                lifeRepository.updateEntity(it, now)
            }
        }
        return changed
    }

    // ------------------------------------------------------------------
    //  Tags — reuse the shared TagEntity / EntityTagCrossRef tables
    // ------------------------------------------------------------------

    /**
     * Attaches a tag by name to this reference's LifeEntity.
     *
     * There is deliberately no `reference_tags` table: references are LifeEntities, so the existing
     * `tags` + `entity_tag_cross_ref` pair already models this correctly. A second tagging system
     * would mean two places to search and two ways to lose data.
     */
    suspend fun addTag(referenceId: String, tagName: String): TagEntity? {
        val reference = dao.getById(referenceId) ?: return null
        val tag = lifeRepository.createTag(tagName)
        lifeRepository.attachTag(reference.lifeEntityId, tag.id)
        return tag
    }

    suspend fun removeTag(referenceId: String, tagId: String): Boolean {
        val reference = dao.getById(referenceId) ?: return false
        lifeRepository.detachTag(reference.lifeEntityId, tagId)
        return true
    }

    /**
     * Tags for a reference, read through its LifeEntity.
     *
     * This is the only way the UI reads tags: a reference owns no tag columns of its own, and going
     * through the LifeEntity is what guarantees a tag added in 资料库 is also visible from 园艺 or
     * 旅行 once those modules land.
     */
    fun observeTagsFor(reference: ReferenceItemEntity): Flow<List<TagEntity>> =
        lifeRepository.observeTagsForEntity(reference.lifeEntityId)

    suspend fun tagsFor(reference: ReferenceItemEntity): List<TagEntity> =
        lifeRepository.getTagsForEntity(reference.lifeEntityId)

    // ------------------------------------------------------------------
    //  Media — through the LifeEntity, never a cascade
    // ------------------------------------------------------------------

    /**
     * Links an already-imported media asset to this reference's LifeEntity.
     *
     * Note what this does *not* do: it does not copy, move or own the asset, and it does not delete
     * anything when the link is removed. One [com.qq.closie.life.media.MediaAssetEntity] can be
     * linked to several entities at once (a photo of a receipt might belong to 财务 *and* 旅行), so
     * the link table is the only place that knows the reference count. Deleting the asset when any
     * one link goes away would destroy another module's content — see [delete] for the same
     * reasoning applied to the reference itself.
     *
     * Returns false when either side is missing, so a dangling id can never be written: there is no
     * physical foreign key on this table, and the repository is the only layer that can enforce it.
     */
    suspend fun linkMedia(
        referenceId: String,
        mediaAssetId: String,
        role: String = MEDIA_ROLE_PRIMARY
    ): Boolean {
        val reference = dao.getById(referenceId) ?: return false
        val assetExists = database.mediaDao().getAssetById(mediaAssetId) != null
        if (!assetExists) return false
        mediaRepository.linkMedia(
            mediaAssetId = mediaAssetId,
            ownerEntityId = reference.lifeEntityId,
            role = role
        )
        return true
    }

    /** Media assets linked to this reference, in link order. */
    suspend fun mediaFor(reference: ReferenceItemEntity): List<String> =
        mediaRepository.getLinksForOwner(reference.lifeEntityId).map { it.mediaAssetId }

    companion object {
        /**
         * Role of a reference's main image.
         *
         * A plain string because [com.qq.closie.life.media.MediaLinkEntity.role] is one — the media
         * layer is deliberately role-agnostic so a new module can invent "receipt" or "cover"
         * without a schema change. 资料库 names its one role here so callers never hardcode it.
         */
        const val MEDIA_ROLE_PRIMARY = "primary"
    }

    // ------------------------------------------------------------------
    //  Delete
    // ------------------------------------------------------------------

    /**
     * Soft-deletes the backing LifeEntity and removes the typed row.
     *
     * Media is deliberately NOT touched: a MediaAsset may be linked to a trip, a plant or another
     * reference at the same time, and there is no link-count GC yet. Orphaned media is the safe
     * failure direction — an unused file wastes space, a wrong delete loses a photo forever.
     */
    suspend fun delete(id: String, now: Long = System.currentTimeMillis()): Boolean =
        database.withTransaction {
            val current = dao.getById(id) ?: return@withTransaction false
            lifeRepository.softDeleteEntity(current.lifeEntityId, now)
            dao.deleteById(id)
            true
        }
}
