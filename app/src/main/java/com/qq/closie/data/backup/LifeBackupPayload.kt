package com.qq.closie.data.backup

import com.qq.closie.life.capture.CaptureItemEntity
import com.qq.closie.life.core.EntityTagCrossRef
import com.qq.closie.life.core.LifeEntityEntity
import com.qq.closie.life.core.LifeRelationEntity
import com.qq.closie.life.core.TagEntity
import com.qq.closie.life.media.MediaAssetEntity
import com.qq.closie.life.media.MediaLinkEntity
import com.qq.closie.life.media.MediaResourceEntity
import com.qq.closie.life.media.MediaStoreImporter
import com.qq.closie.life.plan.PlanItemEntity
import com.qq.closie.life.reference.ReferenceItemEntity

/**
 * The complete Life OS half of a v2 backup, as typed rows rather than a copied database file.
 *
 * ### Why not just copy `life_os.db`
 *
 * Copying the live Room file was the previous design and it fails in three separate ways, all of
 * which were real in the shipped v0.3.0 build:
 *
 *  1. **The wrong file was copied.** The path was resolved from a constant that said `life-db`
 *     while the database was actually named `life_os.db`, so nothing was ever added to the archive
 *     and the failure was invisible — `exists()` was simply false.
 *  2. **A copied database cannot be restored into a running app.** Restore closed the database and
 *     swapped the file underneath it, but every repository in the process still held the *closed*
 *     `LifeDatabase` instance. The next `referenceRepository.create(...)` would hit
 *     "connection pool has been closed" — the app appeared to restore successfully and then threw
 *     on the user's next action.
 *  3. **Bytes are device-specific.** `MediaResourceEntity.managedPath` stores an absolute path
 *     under `filesDir`. Copying rows verbatim carries the old device's paths into the new device,
 *     where the same absolute path may not exist, may point into another Android user's storage,
 *     or may belong to a work profile. Every restored image would render as a broken thumbnail.
 *
 * ### The typed design
 *
 * Rows are written as plain data ([LifeBackupPayload] and its two satellites) and restored inside a
 * single `withTransaction` on the *live* database. Nothing is closed, no file is swapped, and the
 * repositories the UI is already holding keep working — which is what makes
 * "restore, then immediately create a plan" succeed instead of throwing.
 *
 * Ordering matters on insert because of foreign keys: entities before the rows that reference them.
 * [toInsertOrder] encodes that order once so export, import and tests cannot disagree about it.
 */
data class LifeBackupPayload(
    val lifeEntities: List<LifeEntityEntity> = emptyList(),
    val lifeRelations: List<LifeRelationEntity> = emptyList(),
    val tags: List<TagEntity> = emptyList(),
    val entityTagCrossRefs: List<EntityTagCrossRef> = emptyList(),
    val mediaAssets: List<MediaAssetEntity> = emptyList(),
    val mediaResources: List<MediaResourceRecord> = emptyList(),
    val mediaLinks: List<MediaLinkEntity> = emptyList(),
    val captureItems: List<CaptureItemEntity> = emptyList(),
    val referenceItems: List<ReferenceItemEntity> = emptyList(),
    val planItems: List<PlanItemEntity> = emptyList()
) {

    /**
     * Tables flattened into insert order, with the media table replaced by [MediaResourceRecord].
     *
     * Used by the restore path to drive its writes in one loop, and by tests to assert that every
     * table is covered — a table added to the schema but forgotten here would silently stop being
     * backed up, which is exactly the class of bug this file exists to prevent.
     */
    fun toInsertOrder(): List<Pair<String, List<Any>>> = listOf(
        "life_entities" to lifeEntities,
        "tags" to tags,
        "life_relations" to lifeRelations,
        "entity_tag_cross_ref" to entityTagCrossRefs,
        "media_assets" to mediaAssets,
        "media_resources" to mediaResources,
        "media_links" to mediaLinks,
        "capture_items" to captureItems,
        "reference_items" to referenceItems,
        "plan_items" to planItems
    )

    /** Total row count, for the manifest and for logging a restore. */
    val rowCount: Int
        get() = toInsertOrder().sumOf { it.second.size }
}

/**
 * One `media_resources` row with its archive identity separated from its device path.
 *
 * [archiveFileName] is where the bytes live *inside the backup ZIP* (`life/media/<archiveFileName>`).
 * [row] is the original entity with [MediaResourceEntity.managedPath] **excluded** — restore always
 * recomputes that field from the current device's `filesDir`, so the value in [row] is discarded.
 *
 * Keeping the two apart is the whole point: the archive is portable and the path is not. Serialising
 * the raw entity would have shipped the old absolute path inside the row and made it far too easy
 * for a restore implementation to keep it.
 */
data class MediaResourceRecord(
    val row: MediaResourceEntity,
    val archiveFileName: String
)

/**
 * Where a restored media file should land on *this* device.
 *
 * `managedPath` is an absolute path under `filesDir`, and `filesDir` differs between devices and
 * between Android users / work profiles on the same device — so it is never carried across.
 * Rebuilding it from the current context is the only correct thing to do.
 *
 * The filename is preserved as-is because other rows (and the user's mental model) refer to it, and
 * because it is already collision-free: [com.qq.closie.life.media.MediaStoreImporter] names managed
 * files after their content hash.
 */
internal fun managedPathFor(filesDir: java.io.File, fileName: String): String =
    java.io.File(java.io.File(filesDir, MediaStoreImporter.MEDIA_DIR), fileName).absolutePath
