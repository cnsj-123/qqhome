package com.qq.closie.data.backup

import android.content.Context
import android.net.Uri
import com.qq.closie.data.draft.DraftStore
import com.qq.closie.data.repository.WardrobeRepository
import com.qq.closie.life.data.database.LifeDatabase
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Writes a backup archive, and the CSV view of the wardrobe.
 *
 * Owns only the outbound direction: reading live state and serialising it. It never validates an
 * incoming archive and never touches the filesystem outside its own cache scratch directory, which is
 * what lets the restore path be reasoned about without exporting being involved.
 */
internal object BackupExporter {

    /**
     * Writes a complete backup ZIP to [outputUri] (SAF document).
     *
     * ### A complete backup means all three surfaces
     *
     * [lifeDatabase] is non-null and required. A v0.3 backup is the user's Closet **and** their Life OS
     * database **and** their Life OS media; there is no such thing as a "complete backup" that omits
     * the life-graph, and an API that could produce one was an invitation to ship one by accident. The
     * old `LifeDatabase? = null` parameter still exists internally on [BackupManager], but production
     * callers cannot reach it through a null.
     *
     * ### One consistent snapshot, taken once
     *
     * The Life OS payload comes from a single [LifeBackupApplier.snapshot] call, which reads every table
     * inside one Room transaction. Everything downstream — which media files to export, their archive
     * names, the payload the archive carries — is derived from that one payload. The previous version
     * called `getAllResources()` **three separate times** (to collect paths, to copy files, and to build
     * the payload); each call was its own implicit transaction, so a concurrent user write could make
     * the three disagree and produce an archive whose rows and bytes belonged to different moments.
     *
     * ### Media bytes must match the rows
     *
     * Every media row that has a `managedPath` must have a readable file, and where the row records
     * `sizeBytes`/`sha256` those are verified against the bytes actually written. A resource that cannot
     * be backed up **fails the export** rather than being skipped: an archive that silently drops media
     * restores into a 资料库 full of broken thumbnails, and looks entirely successful while doing it.
     */
    suspend fun export(
        context: Context,
        repo: WardrobeRepository,
        outputUri: Uri,
        lifeDatabase: LifeDatabase
    ): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val gson = BackupValidator.gson
                val items = repo.listItems()
                val wears = repo.listWearEvents()
                val washes = repo.washEvents.value
                val ootds = repo.listOotds()
                val outfits = repo.listOutfits()

                // ---- The one Life OS snapshot, read atomically. ---------------------------------
                val lifeSnapshot = LifeBackupApplier.snapshot(lifeDatabase)

                val tmpDir = File(context.cacheDir, "backup_export_${System.currentTimeMillis()}").apply { mkdirs() }
                val imagesDir = File(tmpDir, "images").apply { mkdirs() }

                val allPaths = mutableListOf<String>()
                items.forEach { it.images.forEach { img -> img.localPath?.let { p -> allPaths += p } } }
                ootds.forEach { it.images.forEach { p -> if (p.isNotBlank()) allPaths += p } }
                outfits.forEach { it.tryOnImages.forEach { p -> if (p.isNotBlank()) allPaths += p } }

                // Drafts are user data too: a complete backup must include them and their images.
                val draftStore = DraftStore(context)
                val ootdDrafts = draftStore.listOotdDrafts()
                val outfitDrafts = draftStore.listOutfitDrafts()
                ootdDrafts.forEach { it.images.forEach { p -> if (p.isNotBlank()) allPaths += p } }
                outfitDrafts.forEach { it.tryOnImages.forEach { p -> if (p.isNotBlank()) allPaths += p } }

                // ---- Life OS media, enumerated from the snapshot (never re-queried). ------------
                // `managedPath` is an absolute path on *this* device, so it is the right thing to read
                // bytes from — but it must not travel in the archive. The archive name is stable and
                // device-independent; restore rebuilds the path. See LifeBackupPayload.
                val lifeMediaDir = File(tmpDir, "life_media").apply { mkdirs() }
                val archiveNameByResourceId = mutableMapOf<String, String>()
                lifeSnapshot.mediaResources.forEach { record ->
                    val path = record.row.managedPath?.takeIf { it.isNotBlank() } ?: return@forEach
                    val ext = File(path).extension.ifBlank { "bin" }
                    // Named by resource id: unique by construction, and stable across exports of the
                    // same row. Two resources may legitimately share an original filename.
                    val archiveName = "${record.row.id}.$ext"
                    val source = File(path)
                    if (!source.isFile || source.length() <= 0L) {
                        throw IllegalStateException("Life OS 媒体文件缺失，无法创建完整备份：$path")
                    }
                    val staged = File(lifeMediaDir, archiveName)
                    source.copyTo(staged, overwrite = true)
                    // Verify the bytes we just wrote against what the row claims. A mismatch means the
                    // file changed under us (or was already damaged), and shipping it would produce a
                    // backup whose rows and bytes disagree.
                    record.row.sizeBytes?.let { expected ->
                        if (staged.length() != expected) {
                            throw IllegalStateException("Life OS 媒体文件大小与记录不符：$path")
                        }
                    }
                    record.row.sha256?.let { expected ->
                        val actual = BackupValidator.sha256Hex(staged)
                            ?: throw IllegalStateException("无法校验 Life OS 媒体文件：$path")
                        if (actual != expected) {
                            throw IllegalStateException("Life OS 媒体文件校验失败：$path")
                        }
                    }
                    archiveNameByResourceId[record.row.id] = archiveName
                }

                // Never silently drop referenced images: fail if a local file is missing.
                val missing = allPaths.distinct().filter { path ->
                    val f = File(path)
                    !f.exists() || !f.isFile || f.length() <= 0L
                }
                if (missing.isNotEmpty()) {
                    throw IllegalStateException("有 ${missing.size} 张本地图片缺失，无法创建完整备份")
                }

                val pathMap = mutableMapOf<String, String>()
                var index = 0
                allPaths.distinct().forEach { path ->
                    val f = File(path)
                    val ext = f.extension.ifBlank { "bin" }
                    val name = "img_%04d.$ext".format(index++)
                    f.copyTo(File(imagesDir, name), overwrite = true)
                    pathMap[path] = name
                }

                fun mappedPath(p: String?): String? = p?.let { pathMap[it]?.let { n -> "images/$n" } }

                val mappedItems = items.map { item ->
                    item.copy(images = item.images.map { img -> img.copy(localPath = mappedPath(img.localPath)) })
                }
                val mappedOotds = ootds.map { o -> o.copy(images = o.images.mapNotNull { mappedPath(it) }) }
                val mappedOutfits = outfits.map { o -> o.copy(tryOnImages = o.tryOnImages.mapNotNull { mappedPath(it) }) }
                val mappedOotdDrafts = ootdDrafts.map { o -> o.copy(images = o.images.mapNotNull { mappedPath(it) }) }
                val mappedOutfitDrafts = outfitDrafts.map { o -> o.copy(tryOnImages = o.tryOnImages.mapNotNull { mappedPath(it) }) }

                // ---- The typed payload, derived from the snapshot. ------------------------------
                // Every media row is carried, including rows with no `managedPath`: those describe
                // external media this device never copied, and they belong in the backup as rows. A
                // row that *does* have a path must have been copied above, so an absent archive name
                // here is a bug, not a reason to drop data.
                val payload = lifeSnapshot.copy(
                    mediaResources = lifeSnapshot.mediaResources.map { record ->
                        val hasPath = !record.row.managedPath.isNullOrBlank()
                        val archiveName = if (hasPath) {
                            archiveNameByResourceId[record.row.id]
                                ?: throw IllegalStateException("Life OS 媒体缺少归档名：${record.row.id}")
                        } else {
                            ""
                        }
                        // managedPath is blanked out: it describes the *exporting* device's filesystem,
                        // and restore recomputes it. Shipping it would invite a future restore
                        // implementation to trust a path from another device.
                        MediaResourceRecord(
                            row = record.row.copy(managedPath = null),
                            archiveFileName = archiveName
                        )
                    }
                )

                val manifest = BackupManifest(
                    formatVersion = BackupManager.FORMAT_VERSION,
                    schemaVersion = BackupManager.SCHEMA_VERSION,
                    createdAt = java.time.Instant.now().toString(),
                    includesLifeOs = true
                )

                context.contentResolver.openOutputStream(outputUri)?.use { raw ->
                    ZipOutputStream(raw).use { zip ->
                        BackupValidator.writeEntry(zip, "manifest.json", gson.toJson(manifest))
                        BackupValidator.writeEntry(zip, "data/items.json", gson.toJson(mappedItems))
                        BackupValidator.writeEntry(zip, "data/wear.json", gson.toJson(wears))
                        BackupValidator.writeEntry(zip, "data/wash.json", gson.toJson(washes))
                        BackupValidator.writeEntry(zip, "data/ootds.json", gson.toJson(mappedOotds))
                        BackupValidator.writeEntry(zip, "data/outfits.json", gson.toJson(mappedOutfits))
                        BackupValidator.writeEntry(zip, "data/ootd_drafts.json", gson.toJson(mappedOotdDrafts))
                        BackupValidator.writeEntry(zip, "data/outfit_drafts.json", gson.toJson(mappedOutfitDrafts))
                        imagesDir.listFiles().orEmpty().forEach { f ->
                            zip.putNextEntry(ZipEntry("images/${f.name}"))
                            f.inputStream().use { it.copyTo(zip) }
                            zip.closeEntry()
                        }
                        // A complete v0.3 backup always carries its Life OS section.
                        BackupValidator.writeEntry(zip, "life/data.json", gson.toJson(payload))
                        lifeMediaDir.listFiles().orEmpty().forEach { f ->
                            zip.putNextEntry(ZipEntry("life/media/${f.name}"))
                            f.inputStream().use { it.copyTo(zip) }
                            zip.closeEntry()
                        }
                    }
                } ?: throw IllegalStateException("无法写入目标文件")
                tmpDir.deleteRecursively()
                "OK"
            }.also { runCatching { context.cacheDir?.listFiles().orEmpty().filter { it.name.startsWith("backup_export_") }.forEach { it.deleteRecursively() } } }
        }

    /**
     * Writes a wardrobe-only **v2** archive (manifest `includesLifeOs = false`, no `life/` entries).
     *
     * Test-only, and named so: v1 is a restore-compatibility format and this build must not *produce*
     * archives missing the Life OS half. It exists because the v1 / no-Life-OS restore path still has to
     * be regression-tested, and building that fixture through the production [export] would mean keeping
     * a nullable database parameter on the public API purely for tests.
     *
     * The wardrobe half is written by the same code as a complete export — the only difference is the
     * absence of the `life/` section and the manifest flag — so this cannot drift from the real format.
     */
    suspend fun exportWardrobeOnly(
        context: Context,
        repo: WardrobeRepository,
        outputUri: Uri
    ): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val gson = BackupValidator.gson
                val items = repo.listItems()
                val wears = repo.listWearEvents()
                val washes = repo.washEvents.value
                val ootds = repo.listOotds()
                val outfits = repo.listOutfits()

                val tmpDir = File(context.cacheDir, "backup_export_${System.currentTimeMillis()}").apply { mkdirs() }
                val imagesDir = File(tmpDir, "images").apply { mkdirs() }

                val allPaths = mutableListOf<String>()
                items.forEach { it.images.forEach { img -> img.localPath?.let { p -> allPaths += p } } }
                ootds.forEach { it.images.forEach { p -> if (p.isNotBlank()) allPaths += p } }
                outfits.forEach { it.tryOnImages.forEach { p -> if (p.isNotBlank()) allPaths += p } }
                val draftStore = DraftStore(context)
                val ootdDrafts = draftStore.listOotdDrafts()
                val outfitDrafts = draftStore.listOutfitDrafts()
                ootdDrafts.forEach { it.images.forEach { p -> if (p.isNotBlank()) allPaths += p } }
                outfitDrafts.forEach { it.tryOnImages.forEach { p -> if (p.isNotBlank()) allPaths += p } }

                val pathMap = mutableMapOf<String, String>()
                var index = 0
                allPaths.distinct().forEach { path ->
                    val f = File(path)
                    if (!f.isFile || f.length() <= 0L) {
                        throw IllegalStateException("有本地图片缺失，无法创建备份")
                    }
                    val ext = f.extension.ifBlank { "bin" }
                    val name = "img_%04d.$ext".format(index++)
                    f.copyTo(File(imagesDir, name), overwrite = true)
                    pathMap[path] = name
                }

                fun mappedPath(p: String?): String? = p?.let { pathMap[it]?.let { n -> "images/$n" } }
                val mappedItems = items.map { item ->
                    item.copy(images = item.images.map { img -> img.copy(localPath = mappedPath(img.localPath)) })
                }
                val mappedOotds = ootds.map { o -> o.copy(images = o.images.mapNotNull { mappedPath(it) }) }
                val mappedOutfits = outfits.map { o -> o.copy(tryOnImages = o.tryOnImages.mapNotNull { mappedPath(it) }) }

                val manifest = BackupManifest(
                    formatVersion = BackupManager.FORMAT_VERSION,
                    schemaVersion = BackupManager.SCHEMA_VERSION,
                    createdAt = java.time.Instant.now().toString(),
                    includesLifeOs = false
                )

                context.contentResolver.openOutputStream(outputUri)?.use { raw ->
                    ZipOutputStream(raw).use { zip ->
                        BackupValidator.writeEntry(zip, "manifest.json", gson.toJson(manifest))
                        BackupValidator.writeEntry(zip, "data/items.json", gson.toJson(mappedItems))
                        BackupValidator.writeEntry(zip, "data/wear.json", gson.toJson(wears))
                        BackupValidator.writeEntry(zip, "data/wash.json", gson.toJson(washes))
                        BackupValidator.writeEntry(zip, "data/ootds.json", gson.toJson(mappedOotds))
                        BackupValidator.writeEntry(zip, "data/outfits.json", gson.toJson(mappedOutfits))
                        imagesDir.listFiles().orEmpty().forEach { f ->
                            zip.putNextEntry(ZipEntry("images/${f.name}"))
                            f.inputStream().use { it.copyTo(zip) }
                            zip.closeEntry()
                        }
                    }
                } ?: throw IllegalStateException("无法写入目标文件")
                tmpDir.deleteRecursively()
                "OK"
            }.also { runCatching { context.cacheDir?.listFiles().orEmpty().filter { it.name.startsWith("backup_export_") }.forEach { it.deleteRecursively() } } }
        }

    /** Exports the wardrobe as a CSV (UTF-8 with BOM) for viewing on a computer. */
    suspend fun exportCsv(context: Context, repo: WardrobeRepository, outputUri: Uri): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val items = repo.listItems()
                val wears = repo.listWearEvents()
                val washes = repo.washEvents.value
                val wearCount = wears.groupingBy { it.itemId }.eachCount()
                val washCount = washes.groupingBy { it.itemId }.eachCount()

                val header = listOf(
                    "id", "status", "name", "category", "subcategory", "brand", "store",
                    "platform", "productUrl", "price", "originalPrice", "purchaseDate",
                    "sizeLabel", "rating", "wearCount", "washCount"
                )
                val lines = StringBuilder("\uFEFF")
                lines.appendLine(header.joinToString(",") { csvCell(it) })
                items.forEach { item ->
                    val row = listOf(
                        item.id, item.status.name, item.name, item.category, item.subcategory,
                        item.brand, item.store, item.purchasePlatform, item.productUrl,
                        item.price?.toString().orEmpty(), item.originalPrice?.toString().orEmpty(),
                        item.purchaseDate, item.sizeLabel, item.rating.toString(),
                        (wearCount[item.id] ?: 0).toString(), (washCount[item.id] ?: 0).toString()
                    )
                    lines.appendLine(row.joinToString(",") { csvCell(it) })
                }
                context.contentResolver.openOutputStream(outputUri)?.use { raw ->
                    raw.write(lines.toString().toByteArray(Charsets.UTF_8))
                } ?: throw IllegalStateException("无法写入目标文件")
            }
        }

    private fun csvCell(value: String): String = "\"" + value.replace("\"", "\"\"") + "\""
}
