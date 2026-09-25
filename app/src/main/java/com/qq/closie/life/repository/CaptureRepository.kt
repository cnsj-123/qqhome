package com.qq.closie.life.repository

import com.qq.closie.life.capture.CaptureItemEntity
import com.qq.closie.life.capture.CaptureSource
import com.qq.closie.life.capture.CaptureStatus
import androidx.room.withTransaction
import com.qq.closie.life.data.database.LifeDatabase
import kotlinx.coroutines.flow.Flow

/**
 * Write/read boundary for the capture inbox.
 *
 * The pipeline is staged and every transition is explicit:
 *
 *   NEW → PROCESSING → NEEDS_REVIEW → CONFIRMED
 *                          ↓
 *                    FAILED / DISMISSED
 *
 * [CaptureItemEntity.primaryMediaAssetId] is nullable on purpose: a text-only capture (pasted
 * link, clipboard snippet, transcript) has no media attached yet and must still be storable.
 * Nothing here cascades — a capture is history, and history must never be silently erased.
 */
class CaptureRepository(private val database: LifeDatabase) {

    private val dao = database.captureDao()
    private val mediaDao = database.mediaDao()

    fun observeAll(): Flow<List<CaptureItemEntity>> = dao.observeAll()

    fun observeByStatus(status: CaptureStatus): Flow<List<CaptureItemEntity>> =
        dao.observeByStatus(status)

    /** Everything still in flight — i.e. not CONFIRMED, FAILED or DISMISSED. */
    fun observePending(): Flow<List<CaptureItemEntity>> = dao.observePending()

    /**
     * Local search across a capture's title, raw text, note and source URL.
     *
     * Mirrors [ReferenceRepository.search]: the term is escaped for `LIKE` before it reaches SQL,
     * because a bare `%` or `_` typed by the user would otherwise act as a wildcard and turn "50%"
     * into "matches every record". Backslash is the escape character, so it has to be escaped
     * first — the other order would double-escape what the first pass produced.
     *
     * A blank query falls back to [observeAll] rather than running a `LIKE '%%'`, so the "no
     * search" path and the "empty search" path cannot disagree about ordering.
     */
    fun search(query: String): Flow<List<CaptureItemEntity>> {
        val escaped = escapeLike(query.trim())
        if (escaped.isEmpty()) return dao.observeAll()
        return dao.search(escaped)
    }

    /** Recently created captures, newest first. Used by the home page's 最近 block. */
    fun observeRecent(limit: Int): Flow<List<CaptureItemEntity>> = dao.observeRecent(limit)

    internal fun escapeLike(raw: String): String = raw
        .replace("\\", "\\\\")
        .replace("%", "\\%")
        .replace("_", "\\_")

    suspend fun getById(id: String): CaptureItemEntity? = dao.getById(id)

    suspend fun count(): Int = dao.count()

    /**
     * Creates a capture in [CaptureStatus.NEW].
     *
     * Returns false (instead of throwing) when the id already exists so callers can dedupe
     * repeated shares without wrapping every call in a try/catch — and also when
     * [primaryMediaAssetId] points at an asset that was never stored.
     *
     * That second check matters because [CaptureItemEntity.primaryMediaAssetId] carries no physical
     * foreign key: nothing in SQLite would stop a dangling reference, and since captures are history
     * that is never cascade-deleted, a bad reference would sit in the database forever. Rejecting
     * at the door is the only place it can be enforced.
     */
    suspend fun create(
        id: String,
        source: CaptureSource,
        rawText: String? = null,
        sourceUrl: String? = null,
        primaryMediaAssetId: String? = null,
        now: Long = System.currentTimeMillis()
    ): Boolean {
        if (dao.getById(id) != null) return false
        if (primaryMediaAssetId != null && mediaDao.getAssetById(primaryMediaAssetId) == null) {
            return false
        }
        dao.insert(
            CaptureItemEntity(
                id = id,
                source = source,
                status = CaptureStatus.NEW,
                rawText = rawText,
                sourceUrl = sourceUrl,
                primaryMediaAssetId = primaryMediaAssetId,
                createdAt = now,
                updatedAt = now
            )
        )
        return true
    }

    suspend fun updateRawText(id: String, rawText: String?): Boolean {
        val current = dao.getById(id) ?: return false
        dao.update(current.copy(rawText = rawText, updatedAt = System.currentTimeMillis()))
        return true
    }

    /**
     * The record editor's save.
     *
     * [rawText] is deliberately NOT part of this call. It is the captured history — what the outside
     * world handed us — and the editor is for the user's own [displayTitle] and [note]. Letting the
     * editor rewrite the raw text would destroy the only verbatim record Life OS keeps, and would
     * make "I edited my note" and "I changed what was captured" indistinguishable in the data.
     *
     * A blank title or note is stored as `null` rather than an empty string, so "never set" and
     * "cleared" collapse to the same state. Two different representations of nothing would show up
     * as two different bugs later.
     */
    suspend fun updateUserFields(
        id: String,
        displayTitle: String?,
        note: String?,
        now: Long = System.currentTimeMillis()
    ): Boolean {
        val current = dao.getById(id) ?: return false
        dao.update(
            current.copy(
                displayTitle = displayTitle?.trim()?.takeIf { it.isNotEmpty() },
                note = note?.trim()?.takeIf { it.isNotEmpty() },
                updatedAt = now
            )
        )
        return true
    }

    /**
     * Points a capture at a media asset that already exists.
     *
     * Returns false when either side is missing: no capture with [id], or no asset with
     * [mediaAssetId]. Refusing the second case is what keeps [CaptureItemEntity.primaryMediaAssetId]
     * from ever holding a dangling id — see [create] for why the repository is the only layer that
     * can enforce it.
     */
    suspend fun attachMedia(id: String, mediaAssetId: String): Boolean {
        val current = dao.getById(id) ?: return false
        if (mediaDao.getAssetById(mediaAssetId) == null) return false
        dao.update(
            current.copy(
                primaryMediaAssetId = mediaAssetId,
                updatedAt = System.currentTimeMillis()
            )
        )
        return true
    }

    suspend fun markProcessing(id: String): Boolean = transition(id, CaptureStatus.PROCESSING)

    suspend fun markNeedsReview(id: String): Boolean = transition(id, CaptureStatus.NEEDS_REVIEW)

    suspend fun markConfirmed(id: String): Boolean = transition(id, CaptureStatus.CONFIRMED)

    /** FAILED is terminal-ish but recoverable: a retry moves it back to PROCESSING. */
    suspend fun markFailed(id: String, error: String): Boolean {
        if (dao.getById(id) == null) return false
        return dao.updateStatus(
            id = id,
            status = CaptureStatus.FAILED,
            timestamp = System.currentTimeMillis(),
            errorMessage = error
        ) > 0
    }

    suspend fun dismiss(id: String): Boolean = transition(id, CaptureStatus.DISMISSED)

    suspend fun delete(id: String) {
        dao.getById(id)?.let { dao.delete(it) }
    }

    suspend fun <T> withTransaction(block: suspend () -> T): T = database.withTransaction(block)

    private suspend fun transition(id: String, target: CaptureStatus): Boolean {
        val current = dao.getById(id) ?: return false
        if (current.status == target) return true
        // Non-failure transitions clear the previous error: a retry that succeeds must not keep
        // the old message around to confuse the next screen that reads it.
        val error = if (target == CaptureStatus.FAILED) current.errorMessage else null
        return dao.updateStatus(
            id = id,
            status = target,
            timestamp = System.currentTimeMillis(),
            errorMessage = error
        ) > 0
    }
}
