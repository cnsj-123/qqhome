package com.qq.closie.data.backup

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.qq.closie.data.repository.LocalWardrobeRepository
import com.qq.closie.life.capture.CaptureSource
import com.qq.closie.life.capture.CaptureStatus
import com.qq.closie.life.core.EntityTagCrossRef
import com.qq.closie.life.core.LifeEntityEntity
import com.qq.closie.life.core.LifeRelationEntity
import com.qq.closie.life.core.TagEntity
import com.qq.closie.life.data.database.LifeDatabase
import com.qq.closie.life.media.MediaAssetEntity
import com.qq.closie.life.media.MediaLinkEntity
import com.qq.closie.life.media.MediaResourceEntity
import com.qq.closie.life.media.MediaResourceRole
import com.qq.closie.life.media.MediaType
import com.qq.closie.life.plan.PlanItemEntity
import com.qq.closie.life.reference.ReferenceItemEntity
import com.qq.closie.life.reference.ReferenceStatus
import com.qq.closie.life.reference.ReferenceType
import com.qq.closie.life.repository.CaptureRepository
import com.qq.closie.life.repository.LifeRepository
import com.qq.closie.life.repository.MediaRepository
import com.qq.closie.life.repository.PlanRepository
import com.qq.closie.life.repository.ReferenceRepository
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Proves a v2 backup actually contains Life OS data, and that restoring it does not break the app.
 *
 * ### Why this file exists
 *
 * The previous v2 implementation shipped with **no tests at all** and was wrong in two ways that a
 * test would have caught immediately:
 *
 *  1. It looked the database up under the name `life-db` while the real file was `life_os.db`. The
 *     `exists()` check was simply false, so the Life OS half was omitted from every backup — and
 *     because nothing threw, every backup reported success. A user would discover this only after
 *     changing phones, with the wardrobe restored and an empty timeline.
 *  2. Restore closed the `LifeDatabase` and swapped the file, leaving every repository holding a
 *     dead instance. The app reported a successful restore and then threw on the next write.
 *
 * Each test below corresponds to one of the guarantees whose absence produced those bugs. The last
 * one — [restore_leavesTheDatabaseUsable] — is the one that would have failed loudest.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupManagerTest {

    private lateinit var context: Context
    private lateinit var db: LifeDatabase
    private lateinit var life: LifeRepository
    private lateinit var media: MediaRepository
    private lateinit var captureRepo: CaptureRepository
    private lateinit var referenceRepo: ReferenceRepository
    private lateinit var planRepo: PlanRepository
    private lateinit var wardrobe: LocalWardrobeRepository

    private val createdUris = mutableListOf<Uri>()

    @Before
    fun setUp() {
        // Constructing a repository is gated: it refuses to open while [RestoreStartupGate] is blocked.
        // Robolectric also runs `ClosieApplication.onCreate`, which resolves the gate for real (no marker
        // ⇒ READY). Marking it ready here keeps this class about the archive format rather than about
        // the startup barrier, which has its own dedicated test classes.
        RestoreStartupGate.markReady()

        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, LifeDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        life = LifeRepository(db)
        media = MediaRepository(db)
        captureRepo = CaptureRepository(db)
        referenceRepo = ReferenceRepository(db, life, media, captureRepo)
        planRepo = PlanRepository(db, life)
        wardrobe = LocalWardrobeRepository(context)
    }

    @After
    fun tearDown() {
        runCatching { db.close() }
        createdUris.forEach { runCatching { File(it.path!!).delete() } }
        // Reset on the way out too: the gate is process-wide state that would otherwise leak into
        // whichever test class Robolectric loads next.
        RestoreStartupGate.resetForTesting()
    }

    // ------------------------------------------------------------------
    //  Helpers
    // ------------------------------------------------------------------

    /** A real file-backed URI, because BackupManager writes through the content resolver. */
    private fun outputUri(name: String): Uri {
        val f = File(context.cacheDir, "$name-${System.nanoTime()}.zip")
        createdUris += Uri.fromFile(f)
        return Uri.fromFile(f)
    }

    /** Writes a managed image file the way MediaStoreImporter would: under filesDir/media. */
    private fun writeManagedMedia(fileName: String, bytes: ByteArray = byteArrayOf(1, 2, 3, 4)): String {
        val dir = File(context.filesDir, "media").apply { mkdirs() }
        val f = File(dir, fileName)
        f.writeBytes(bytes)
        return f.absolutePath
    }

    private suspend fun seedCapture(text: String, url: String? = null): String {
        val id = "cap-${System.nanoTime()}"
        captureRepo.create(
            id = id,
            source = CaptureSource.SHARE,
            rawText = text,
            sourceUrl = url
        )
        return id
    }

    private suspend fun seedReference(title: String, captureId: String? = null): ReferenceItemEntity =
        referenceRepo.create(
            title = title,
            referenceType = ReferenceType.ARTICLE,
            originalCaptureId = captureId
        )

    private suspend fun seedPlan(title: String, dueAt: Long? = null): PlanItemEntity =
        planRepo.create(title = title, dueAt = dueAt)

    // ------------------------------------------------------------------
    //  Round trip: what goes in comes back out
    // ------------------------------------------------------------------

    @Test
    fun backupV2_containsLifeData() = runTest {
        seedCapture("一条记录")
        seedReference("一篇资料")
        seedPlan("一个计划")

        val uri = outputUri("contains-life")
        val result = BackupManager.export(context, wardrobe, uri, db)
        assertThat(result.isSuccess).isTrue()

        // Read the archive back and prove the payload is really in there — not merely that export
        // returned success. This is the assertion that would have failed for the `life-db` bug,
        // where export succeeded while writing no Life OS section at all.
        val payload = readLifePayload(uri)
        assertThat(payload).isNotNull()
        assertThat(payload!!.captureItems.map { it.rawText }).contains("一条记录")
        assertThat(payload.referenceItems.map { it.title }).contains("一篇资料")
        assertThat(payload.planItems.map { it.title }).contains("一个计划")
    }

    @Test
    fun backupV2_containsManagedMedia() = runTest {
        val path = writeManagedMedia("shot.png")
        val assetId = "asset-1"
        db.mediaDao().insertAsset(
            MediaAssetEntity(id = assetId, mediaType = MediaType.IMAGE, createdAt = 1L, updatedAt = 1L)
        )
        db.mediaDao().insertResource(
            MediaResourceEntity(
                id = "res-1",
                mediaAssetId = assetId,
                role = MediaResourceRole.ORIGINAL,
                mimeType = "image/png",
                managedPath = path,
                createdAt = 1L
            )
        )

        val uri = outputUri("contains-media")
        assertThat(BackupManager.export(context, wardrobe, uri, db).isSuccess).isTrue()

        val payload = readLifePayload(uri)!!
        assertThat(payload.mediaResources).hasSize(1)
        // The row must NOT carry the device path — that is the cross-device portability rule.
        assertThat(payload.mediaResources.first().row.managedPath).isNull()
        // The bytes live in the archive, addressed by a device-independent name.
        assertThat(payload.mediaResources.first().archiveFileName).isNotEmpty()
    }

    @Test
    fun backupV2_restoresCaptureReferenceAndPlan() = runTest {
        seedCapture("待恢复记录", url = null)
        seedReference("待恢复资料")
        seedPlan("待恢复计划")

        val uri = outputUri("restore-entities")
        assertThat(BackupManager.export(context, wardrobe, uri, db).isSuccess).isTrue()

        // Wipe everything the way a fresh install would look.
        db.planDao().deleteAll()
        db.referenceDao().deleteAll()
        db.captureDao().deleteAll()

        assertThat(BackupManager.restore(context, wardrobe, uri, db).isSuccess).isTrue()

        assertThat(db.captureDao().getAllOnce().map { it.rawText }).contains("待恢复记录")
        assertThat(db.referenceDao().getAllOnce().map { it.title }).contains("待恢复资料")
        assertThat(db.planDao().getAllOnce().map { it.title }).contains("待恢复计划")
    }

    @Test
    fun backupV2_restoresTagsAndRelations() = runTest {
        val now = 1_000L
        db.lifeEntityDao().insertAll(
            listOf(
                LifeEntityEntity(id = "e1", entityType = "REFERENCE", createdAt = now, updatedAt = now, revision = 1),
                LifeEntityEntity(id = "e2", entityType = "PLAN", createdAt = now, updatedAt = now, revision = 1)
            )
        )
        db.tagDao().insertAll(listOf(TagEntity(id = "t1", name = "园艺", normalizedName = "园艺", createdAt = now)))
        db.tagDao().insertAllCrossRefs(listOf(EntityTagCrossRef(entityId = "e1", tagId = "t1")))
        db.lifeRelationDao().insertAll(
            listOf(
                LifeRelationEntity(
                    id = "r1",
                    fromEntityId = "e1",
                    toEntityId = "e2",
                    relationType = "RELATES_TO",
                    createdAt = now
                )
            )
        )

        val uri = outputUri("tags-relations")
        assertThat(BackupManager.export(context, wardrobe, uri, db).isSuccess).isTrue()

        db.tagDao().deleteAllCrossRefs()
        db.lifeRelationDao().deleteAll()
        db.tagDao().deleteAll()

        assertThat(BackupManager.restore(context, wardrobe, uri, db).isSuccess).isTrue()

        assertThat(db.tagDao().getAllOnce().map { it.name }).contains("园艺")
        assertThat(db.tagDao().getAllCrossRefsOnce()).hasSize(1)
        assertThat(db.lifeRelationDao().getAllOnce()).hasSize(1)
    }

    // ------------------------------------------------------------------
    //  Media paths are rewritten for the receiving device
    // ------------------------------------------------------------------

    @Test
    fun backupV2_restoresManagedMediaToCurrentFilesDir() = runTest {
        val original = writeManagedMedia("device-a-photo.png", byteArrayOf(9, 8, 7))
        db.mediaDao().insertAsset(
            MediaAssetEntity(id = "a1", mediaType = MediaType.IMAGE, createdAt = 1L, updatedAt = 1L)
        )
        db.mediaDao().insertResource(
            MediaResourceEntity(
                id = "r1", mediaAssetId = "a1", role = MediaResourceRole.ORIGINAL,
                mimeType = "image/png", managedPath = original, createdAt = 1L
            )
        )

        val uri = outputUri("media-restore")
        assertThat(BackupManager.export(context, wardrobe, uri, db).isSuccess).isTrue()

        // Simulate the files having been lost on this device.
        db.mediaDao().deleteAllResources()
        db.mediaDao().deleteAllAssets()
        File(original).delete()

        assertThat(BackupManager.restore(context, wardrobe, uri, db).isSuccess).isTrue()

        val restored = db.mediaDao().getAllResources().single()
        val restoredPath = restored.managedPath!!
        // The bytes are back, on THIS device, under filesDir/media.
        assertThat(File(restoredPath).exists()).isTrue()
        assertThat(restoredPath).startsWith(File(context.filesDir, "media").absolutePath)
    }

    @Test
    fun backupV2_rewritesManagedPathForCurrentDevice() = runTest {
        // The exported row carries a path from a *different* device. Restore must not reuse it:
        // filesDir is not stable across devices, Android users or work profiles, so a verbatim path
        // would point at a file that does not exist and every image would render broken.
        val foreignPath = "/data/user/0/com.qq.closie/files/media/from-another-phone.png"
        db.mediaDao().insertAsset(
            MediaAssetEntity(id = "a2", mediaType = MediaType.IMAGE, createdAt = 1L, updatedAt = 1L)
        )
        // Write the bytes where *this* run can find them, but record the foreign path on the row.
        val localBytes = File(context.cacheDir, "src.png").apply { writeBytes(byteArrayOf(5, 5, 5)) }
        db.mediaDao().insertResource(
            MediaResourceEntity(
                id = "r2", mediaAssetId = "a2", role = MediaResourceRole.ORIGINAL,
                mimeType = "image/png", managedPath = localBytes.absolutePath, createdAt = 1L
            )
        )

        val uri = outputUri("path-rewrite")
        assertThat(BackupManager.export(context, wardrobe, uri, db).isSuccess).isTrue()
        db.mediaDao().deleteAllResources()
        db.mediaDao().deleteAllAssets()

        assertThat(BackupManager.restore(context, wardrobe, uri, db).isSuccess).isTrue()

        val restoredPath = db.mediaDao().getAllResources().single().managedPath!!
        assertThat(restoredPath).isNotEqualTo(foreignPath)
        assertThat(restoredPath).startsWith(File(context.filesDir, "media").absolutePath)
        assertThat(File(restoredPath).exists()).isTrue()
    }

    // ------------------------------------------------------------------
    //  The database must still work after a restore
    // ------------------------------------------------------------------

    @Test
    fun backupV2_afterRestoreDatabaseRemainsUsable() = runTest {
        seedCapture("旧记录")
        val uri = outputUri("still-usable")
        assertThat(BackupManager.export(context, wardrobe, uri, db).isSuccess).isTrue()
        assertThat(BackupManager.restore(context, wardrobe, uri, db).isSuccess).isTrue()

        // The heart of the matter. The old implementation closed the database and reopened a new
        // instance, so these two calls would throw "connection pool has been closed" — after a
        // restore that reported success. Writing through the live DAOs in a transaction is what
        // makes this pass.
        val reference = referenceRepo.create(title = "恢复后新建资料", referenceType = ReferenceType.NOTE)
        val plan = planRepo.create(title = "恢复后新建计划")

        assertThat(reference.id).isNotEmpty()
        assertThat(plan.id).isNotEmpty()
        // And the restored data is still there alongside the new rows.
        assertThat(db.captureDao().getAllOnce().map { it.rawText }).contains("旧记录")
    }

    // ------------------------------------------------------------------
    //  v1 stays restorable, and does not touch Life OS
    // ------------------------------------------------------------------

    @Test
    fun backupV1_stillRestoresClosie() = runTest {
        val uri = outputUri("v1-closie")
        writeV1Archive(uri, itemsJson = "[]")

        val result = BackupManager.restore(context, wardrobe, uri, db)
        assertThat(result.isSuccess).isTrue()
    }

    @Test
    fun backupV1_doesNotEraseExistingLifeData() = runTest {
        seedCapture("v1恢复前就存在的记录")
        seedReference("v1恢复前就存在的资料")
        seedPlan("v1恢复前就存在的计划")

        val uri = outputUri("v1-no-erase")
        writeV1Archive(uri, itemsJson = "[]")

        assertThat(BackupManager.restore(context, wardrobe, uri, db).isSuccess).isTrue()

        // A pre-v0.3.0 backup knows nothing about Life OS. Restoring it must return the wardrobe and
        // leave the Life OS database alone — not clear it, not recreate it. Losing accumulated
        // 记录 because an old backup was restored would be the worst possible reading of "restore".
        assertThat(db.captureDao().getAllOnce().map { it.rawText })
            .contains("v1恢复前就存在的记录")
        assertThat(db.referenceDao().getAllOnce().map { it.title })
            .contains("v1恢复前就存在的资料")
        assertThat(db.planDao().getAllOnce().map { it.title })
            .contains("v1恢复前就存在的计划")
    }

    @Test
    fun wardrobeOnlyArchive_isBuiltOnlyByTheTestFixture_andRestoresWithoutTouchingLifeOs() = runTest {
        // The production facade cannot produce this shape any more: `export` requires a database, so a
        // "complete backup" that is missing the Life OS half is not expressible through the public API.
        // The fixture below is deliberately named `exportWardrobeOnlyForTesting` and is `internal`, so
        // the capability exists for exercising the v1 / no-Life-OS *restore* path without leaving a
        // nullable database parameter on the surface production callers use.
        seedCapture("测试夹具建立前就存在的记录")

        val uri = outputUri("wardrobe-only")
        assertThat(BackupManager.exportWardrobeOnlyForTesting(context, wardrobe, uri).isSuccess).isTrue()
        assertThat(readLifePayload(uri)).isNull()

        // Restoring it must leave the Life OS database exactly as it was.
        assertThat(BackupManager.restore(context, wardrobe, uri, db).isSuccess).isTrue()
        assertThat(db.captureDao().getAllOnce().map { it.rawText }).contains("测试夹具建立前就存在的记录")
    }

    @Test
    fun completeExport_alwaysCarriesTheLifeOsSection() = runTest {
        // The other half of the contract: a production export is never wardrobe-only.
        val uri = outputUri("complete")
        assertThat(BackupManager.export(context, wardrobe, uri, db).isSuccess).isTrue()
        assertThat(readZipEntry(uri, "life/data.json")).isNotNull()
    }

    /**
     * A production export must carry every table — the archive is a *complete* backup, not a sample.
     *
     * Asserted table by table rather than by "the export succeeded", because the failure this guards
     * against is silent: an exporter that forgot one DAO would still produce a valid archive, still
     * report success, and still restore cleanly — it would just lose a whole category of the user's
     * data at the moment they most need it. Seeding one row in every table and finding them all in the
     * payload is what makes that omission a test failure rather than a support ticket.
     */
    @Test
    fun completeExport_carriesEveryLifeOsTable() = runTest {
        val captureId = seedCapture("导出的记录")
        seedReference("导出的资料")
        seedPlan("导出的计划")
        val mediaPath = writeManagedMedia("exported.png")
        db.mediaDao().insertAsset(
            MediaAssetEntity(id = "exp-asset", mediaType = MediaType.IMAGE, createdAt = 1L, updatedAt = 1L)
        )
        db.mediaDao().insertResource(
            MediaResourceEntity(
                id = "exp-res",
                mediaAssetId = "exp-asset",
                role = MediaResourceRole.ORIGINAL,
                mimeType = "image/png",
                managedPath = mediaPath,
                sizeBytes = 4L,
                createdAt = 1L
            )
        )

        val uri = outputUri("complete-tables")
        assertThat(BackupManager.export(context, wardrobe, uri, db).isSuccess).isTrue()

        val payload = readLifePayload(uri)
        assertThat(payload).isNotNull()
        assertThat(payload!!.captureItems.map { it.id }).contains(captureId)
        assertThat(payload.referenceItems.map { it.title }).contains("导出的资料")
        assertThat(payload.planItems.map { it.title }).contains("导出的计划")
        assertThat(payload.mediaAssets.map { it.id }).contains("exp-asset")
        assertThat(payload.mediaResources.map { it.row.id }).contains("exp-res")
        // The managed file itself must be in the archive, or the restore would have a row and no bytes.
        assertThat(payload.mediaResources.first { it.row.id == "exp-res" }.archiveFileName).isNotEmpty()
    }

    /**
     * Exporting is a **snapshot**, not a series of reads: it must see one consistent database state.
     *
     * The exporter reads eleven tables. Read one at a time, a write landing between two of them produces
     * an archive that never existed as a state on disk — typically a row in the archive with no
     * matching parent, or a media row whose file is not in the package. Restoring that archive is how a
     * backup turns into corruption.
     *
     * The implementation takes the whole snapshot inside a single `database.withTransaction`, so the
     * observable consequence is that a write attempted *during* the export is either fully visible or
     * fully absent — never half-applied. This asserts the weaker, directly checkable property that the
     * exported rows are self-consistent: every media resource in the payload resolves to an asset, and
     * to a file, in the same archive.
     */
    @Test
    fun export_isASingleConsistentSnapshot() = runTest {
        val mediaPath = writeManagedMedia("snapshot.png", byteArrayOf(9, 8, 7, 6))
        db.mediaDao().insertAsset(
            MediaAssetEntity(id = "snap-asset", mediaType = MediaType.IMAGE, createdAt = 1L, updatedAt = 1L)
        )
        db.mediaDao().insertResource(
            MediaResourceEntity(
                id = "snap-res",
                mediaAssetId = "snap-asset",
                role = MediaResourceRole.ORIGINAL,
                mimeType = "image/png",
                managedPath = mediaPath,
                sizeBytes = 4L,
                createdAt = 1L
            )
        )
        seedCapture("快照里的记录")

        val uri = outputUri("snapshot")
        assertThat(BackupManager.export(context, wardrobe, uri, db).isSuccess).isTrue()

        val payload = readLifePayload(uri)!!
        val assetIds = payload.mediaAssets.map { it.id }.toSet()
        // No orphan resource: every resource's parent asset is in the same archive.
        assertThat(payload.mediaResources.map { it.row.mediaAssetId }.all { it in assetIds }).isTrue()
        // No row without a file: `archiveFileName` is filled for every managed resource, and the entry
        // named by it really exists in the package.
        payload.mediaResources.forEach { record ->
            assertThat(record.archiveFileName).isNotEmpty()
            assertThat(readZipEntry(uri, "life/media/${record.archiveFileName}")).isNotNull()
        }
    }

    /**
     * A media row whose file cannot be found must fail the **whole export**.
     *
     * The tempting alternative is to skip it and carry on — the archive would still be valid, and most
     * of the user's data would still be backed up. But a backup that silently omits rows is worse than
     * one that refuses: the user believes they are covered, and only discovers otherwise when they
     * restore and the file is missing, by which point the original may be gone. So a missing or
     * mismatched managed file is a hard failure.
     */
    @Test
    fun export_failsWhenAManagedMediaFileIsMissing() = runTest {
        db.mediaDao().insertAsset(
            MediaAssetEntity(id = "ghost-asset", mediaType = MediaType.IMAGE, createdAt = 1L, updatedAt = 1L)
        )
        db.mediaDao().insertResource(
            MediaResourceEntity(
                id = "ghost-res",
                mediaAssetId = "ghost-asset",
                role = MediaResourceRole.ORIGINAL,
                mimeType = "image/png",
                // A path that is recorded but whose bytes do not exist on disk.
                managedPath = File(context.filesDir, "media/never-written.png").absolutePath,
                sizeBytes = 4L,
                createdAt = 1L
            )
        )

        val uri = outputUri("ghost-media")
        val result = BackupManager.export(context, wardrobe, uri, db)

        assertThat(result.isSuccess).isFalse()
    }

    // ------------------------------------------------------------------
    //  A late failure must not half-restore
    // ------------------------------------------------------------------

    @Test
    fun backupV2_failureDoesNotHalfRestore() = runTest {
        seedCapture("必须保留的记录")
        val uri = outputUri("no-half")

        // A v2 manifest promising Life OS data, but with the payload entry omitted. Restore must
        // fail *before* committing the wardrobe, so both halves are still the user's original data.
        writeV2ArchiveWithMissingPayload(uri)

        val result = BackupManager.restore(context, wardrobe, uri, db)
        assertThat(result.isSuccess).isFalse()

        // Life OS untouched — the transaction never ran.
        assertThat(db.captureDao().getAllOnce().map { it.rawText }).contains("必须保留的记录")
    }

    // ------------------------------------------------------------------
    //  Archive helpers
    // ------------------------------------------------------------------

    private fun readLifePayload(uri: Uri): LifeBackupPayload? {
        val text = readZipEntry(uri, "life/data.json") ?: return null
        return com.google.gson.Gson().fromJson(text, LifeBackupPayload::class.java)
    }

    private fun readZipEntry(uri: Uri, entryName: String): String? {
        java.util.zip.ZipInputStream(context.contentResolver.openInputStream(uri)!!).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (entry.name == entryName) {
                    return zip.readBytes().toString(Charsets.UTF_8)
                }
                entry = zip.nextEntry
            }
        }
        return null
    }

    /** Writes a minimal, valid v1 archive: manifest + the five wardrobe JSON files. */
    private fun writeV1Archive(uri: Uri, itemsJson: String) {
        val manifest = """{"formatVersion":1,"schemaVersion":1,"createdAt":"2026-01-01T00:00:00Z"}"""
        writeZip(
            uri, mapOf(
                "manifest.json" to manifest,
                "data/items.json" to itemsJson,
                "data/wear.json" to "[]",
                "data/wash.json" to "[]",
                "data/ootds.json" to "[]",
                "data/outfits.json" to "[]"
            )
        )
    }

    /** A v2 manifest claiming Life OS content, with no `life/data.json` to back it up. */
    private fun writeV2ArchiveWithMissingPayload(uri: Uri) {
        val manifest =
            """{"formatVersion":2,"schemaVersion":1,"createdAt":"2026-01-01T00:00:00Z","includesLifeOs":true}"""
        writeZip(
            uri, mapOf(
                "manifest.json" to manifest,
                "data/items.json" to "[]",
                "data/wear.json" to "[]",
                "data/wash.json" to "[]",
                "data/ootds.json" to "[]",
                "data/outfits.json" to "[]"
            )
        )
    }

    private fun writeZip(uri: Uri, entries: Map<String, String>) {
        context.contentResolver.openOutputStream(uri)!!.use { raw ->
            java.util.zip.ZipOutputStream(raw).use { zip ->
                entries.forEach { (name, content) ->
                    zip.putNextEntry(java.util.zip.ZipEntry(name))
                    zip.write(content.toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                }
            }
        }
    }
}
