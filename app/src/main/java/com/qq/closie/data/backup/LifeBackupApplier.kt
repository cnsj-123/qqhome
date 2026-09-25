package com.qq.closie.data.backup

import android.content.Context
import androidx.room.withTransaction
import com.qq.closie.life.data.database.LifeDatabase
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Applies a v2 `life/` payload to the **live, open** Life OS database and verifies the restored media.
 *
 * ### Why the database is never closed
 *
 * A previous implementation did `database.close()`, swapped the `-db`/`-wal`/`-shm` files, and reopened
 * a *new* `LifeDatabase`. That cannot work: `LifeContainer` hands out one instance, and
 * `ReferenceRepository`, `PlanRepository`, `CaptureRepository`, `MediaRepository` and the ViewModels all
 * captured it at construction. Closing it left every one of them holding a dead handle, so the app would
 * report a successful restore and then throw "connection pool has been closed" on the user's very next
 * write.
 *
 * Writing rows through the live DAOs inside one transaction sidesteps the whole class of problem. The
 * instance is never closed, the repositories stay valid, and `create()` works immediately afterwards.
 *
 * ### Media
 *
 * Media bytes are staged and swapped as a *directory* by [RestoreCoordinator] (see
 * `.life_media_restore_stage_<id>` / `.life_media_restore_old_<id>`). This object therefore does **not**
 * copy media files — it only rebuilds each row's `managedPath` under the current device's
 * `filesDir/media` and, after the swap, verifies those files exist and are intact.
 */
internal object LifeBackupApplier {

    /**
     * Reads the complete current Life OS state as a typed payload.
     *
     * Used to save what a restore is about to overwrite. Read *before* the transaction opens, and
     * deliberately through the same DAOs the apply path uses, so the two cannot describe different sets
     * of tables. `managedPath` values are kept verbatim here (unlike in an exported payload): these rows
     * belong to *this* device, so their paths are already correct.
     *
     * ### One transaction, not eleven reads
     *
     * The room reads below run inside a single [androidx.room.withTransaction]. Without it each DAO call
     * is its own implicit transaction, so a user writing to the app between two calls produces a payload
     * whose tables describe *different moments* — for example `media_assets` from before a delete and
     * `media_resources` from after it. That snapshot is not "the database at some time"; it is a state
     * that never existed, and restoring it would manufacture a payload with dangling rows. A single read
     * transaction gives one consistent point in time for all eleven tables, which is the only thing a
     * backup may legitimately claim to be.
     */
    suspend fun snapshot(database: LifeDatabase): LifeBackupPayload = database.withTransaction {
        LifeBackupPayload(
            lifeEntities = database.lifeEntityDao().getAllOnce(),
            lifeRelations = database.lifeRelationDao().getAllOnce(),
            tags = database.tagDao().getAllOnce(),
            entityTagCrossRefs = database.tagDao().getAllCrossRefsOnce(),
            mediaAssets = database.mediaDao().getAllAssets(),
            mediaResources = database.mediaDao().getAllResources().map {
                MediaResourceRecord(row = it, archiveFileName = "")
            },
            mediaLinks = database.mediaDao().getAllLinks(),
            captureItems = database.captureDao().getAllOnce(),
            referenceItems = database.referenceDao().getAllOnce(),
            planItems = database.planDao().getAllOnce()
        )
    }

    /**
     * Puts [snapshot] back as the database's entire contents, in one transaction.
     *
     * **Idempotent, on purpose.** Recovery calls this without knowing whether the interrupted restore's
     * transaction actually committed — that is exactly the ambiguity [RestoreState.DB_COMMITTING]
     * records. So this has to be safe to run an arbitrary number of times: it is a single transaction
     * that deletes everything and re-inserts the snapshot, so the second run produces the same rows as
     * the first. There is no incremental edit to re-apply and no counter to double.
     */
    suspend fun restoreSnapshot(database: LifeDatabase, snapshot: LifeBackupPayload) {
        database.withTransaction {
            applyDeleteOrder(database)
            // No path remapping: the snapshot's `managedPath` values were captured from *this* device,
            // so they are already correct. `pathByArchiveName` is empty, which leaves every row as-is.
            applyInsertOrder(database, snapshot, pathByArchiveName = emptyMap())
            database.lifeEntityDao().countOnce()
        }
    }

    /**
     * Applies [payload] to the live database inside a single transaction. Media files are already live
     * at `filesDir/media/<archiveFileName>` (staged and swapped by [RestoreCoordinator]); this only
     * rebuilds each managed `MediaResourceEntity.managedPath` from that location and writes the rows.
     *
     * Note: this object does **not** fire the [RestoreHooks] `beforeDbCommit`/`afterDbCommit` hooks. Those
     * belong to [RestoreCoordinator], which owns the commit-boundary sequence and calls them around the
     * snapshot and this transaction — keeping the seam in exactly one place so the shipping code path is
     * the one under test.
     */
    suspend fun apply(
        context: Context,
        payload: LifeBackupPayload,
        database: LifeDatabase
    ): Unit = withContext(Dispatchers.IO) {
        val pathByArchiveName = payload.mediaResources.associate {
            it.archiveFileName to managedPathFor(context.filesDir, it.archiveFileName)
        }

        database.withTransaction {
            applyDeleteOrder(database)
            applyInsertOrder(database, payload, pathByArchiveName)
            // Sanity check inside the transaction: if the restored data cannot be read back the whole
            // thing rolls back and the user keeps what they had.
            database.lifeEntityDao().countOnce()
        }
    }

    /**
     * Verifies every restored [com.qq.closie.life.media.MediaResourceEntity] points at a real, intact
     * file on this device. A half-restored media tree would otherwise pass the wardrobe check and only
     * surface as broken thumbnails later.
     *
     * `suspend` because [com.qq.closie.life.media.MediaDao.getAllResources] is a Room suspend query —
     * calling it from a plain function does not compile, and the previous signature only looked correct
     * because the call site is itself suspend. The caller ([RestoreCoordinator.restore]) is already on
     * `Dispatchers.IO` inside a suspend function, so this simply suspends in place: no `runBlocking`, no
     * `GlobalScope`, and no `allowMainThreadQueries` escape hatch.
     */
    suspend fun healthCheckMedia(database: LifeDatabase, mediaDir: File) {
        database.mediaDao().getAllResources().forEach { resource ->
            val path = resource.managedPath
                ?: throw IllegalStateException("恢复后的媒体行缺少 managedPath: ${resource.id}")
            val file = File(path)
            if (!file.exists() || !file.isFile || file.length() <= 0L) {
                throw IllegalStateException("恢复后的媒体文件不可用: $path")
            }
            resource.sizeBytes?.let { expected ->
                if (file.length() != expected) {
                    throw IllegalStateException("恢复后的媒体文件大小不符: $path")
                }
            }
            resource.sha256?.let { expected ->
                val actual = BackupValidator.sha256Hex(file)
                    ?: throw IllegalStateException("无法计算媒体文件校验值: $path")
                if (actual != expected) {
                    throw IllegalStateException("恢复后的媒体文件校验失败: $path")
                }
            }
            // The path must live under this device's Life OS media directory.
            if (!file.canonicalPath.startsWith(mediaDir.canonicalPath + File.separator)) {
                throw IllegalStateException("恢复后的媒体路径不在 Life OS 媒体目录下: $path")
            }
        }
    }

    fun snapshotFile(id: Long, filesDir: File): File = File(filesDir, ".life_restore_dbsnap_$id.json")

    fun writeSnapshot(file: File, snapshot: LifeBackupPayload) = AtomicJson.write(file, snapshot)

    /**
     * Reads the pre-restore snapshot, preserving the absent/corrupt distinction.
     *
     * A corrupt snapshot must reach recovery as a [AtomicJson.ReadResult.Corrupt] failure — never as
     * `null`, which recovery reads as "the database half cannot be completed, retry later" and which is
     * the right *outcome* but the wrong *reason*: the caller must also be able to tell that the evidence
     * file is damaged rather than simply not written yet.
     */
    fun readSnapshot(file: File): AtomicJson.ReadResult<LifeBackupPayload> =
        AtomicJson.read<LifeBackupPayload>(file)

    /**
     * Deletes every Life OS row, in reverse dependency order.
     *
     * The order is not cosmetic. `media_resources` and `media_links` declare real
     * `ForeignKey(NO_ACTION)` constraints onto `media_assets`, and `entity_tag_cross_ref` onto both
     * `life_entities` and `tags`, so children must go before parents or SQLite raises
     * "FOREIGN KEY constraint failed".
     */
    private suspend fun applyDeleteOrder(database: LifeDatabase) {
        database.planDao().deleteAll()
        database.referenceDao().deleteAll()
        database.captureDao().deleteAll()
        database.mediaDao().deleteAllLinks()
        database.mediaDao().deleteAllResources()
        database.mediaDao().deleteAllAssets()
        database.tagDao().deleteAllCrossRefs()
        database.tagDao().deleteAll()
        database.lifeRelationDao().deleteAll()
        database.lifeEntityDao().deleteAll()
    }

    /**
     * Inserts the payload, in dependency order — the exact reverse of [applyDeleteOrder].
     *
     * Every DAO used here inserts with `OnConflictStrategy.REPLACE`. That matters because the schema
     * carries UNIQUE indices the delete order cannot clear (they live on the rows themselves, e.g.
     * `reference_items.lifeEntityId` and `reference_items.originalCaptureId`).
     */
    private suspend fun applyInsertOrder(
        database: LifeDatabase,
        payload: LifeBackupPayload,
        pathByArchiveName: Map<String, String>
    ) {
        database.lifeEntityDao().insertAll(payload.lifeEntities)
        database.tagDao().insertAll(payload.tags)
        database.lifeRelationDao().insertAll(payload.lifeRelations)
        database.tagDao().insertAllCrossRefs(payload.entityTagCrossRefs)
        database.mediaDao().insertAllAssets(payload.mediaAssets)
        database.mediaDao().insertAllResources(
            payload.mediaResources.map { record ->
                val rebuilt = pathByArchiveName[record.archiveFileName]
                if (rebuilt != null) record.row.copy(managedPath = rebuilt) else record.row
            }
        )
        database.mediaDao().insertAllLinks(payload.mediaLinks)
        database.captureDao().insertAll(payload.captureItems)
        database.referenceDao().insertAll(payload.referenceItems)
        database.planDao().insertAll(payload.planItems)
    }
}
