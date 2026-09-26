package com.qq.closie.data.backup

import android.content.Context
import android.net.Uri
import com.qq.closie.data.backup.RestoreStartupGate
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
 *
 * ### Every export is one business operation, under one lease
 *
 * An export is not "a few reads". It is a long sequence — enumerate items, wear, wash, ootds, outfits and
 * both draft files; take the Life OS snapshot; resolve every referenced image path; copy each image's
 * bytes; enumerate Life OS media from the snapshot and copy those bytes with size and checksum
 * verification; finally write the ZIP. Each individual read takes its own short lease inside the
 * repository, and that is exactly the problem: short leases prove each read was safe *at that instant*
 * and say nothing about the sequence.
 *
 * The window is real and the result is corrupt:
 *
 * ```
 *   export: listItems()            <- lease taken and released, wardrobe read
 *   user:   restore starts         <- beginRestore sees activeBusinessOps == 0 and proceeds
 *   restore: swaps closie/ + media, rewrites the DB, reopens the gate
 *   export: listWearEvents(), drafts, life snapshot, copies image bytes …
 *   result: a ZIP mixing pre-restore wardrobe rows with post-restore wear events and media bytes
 * ```
 *
 * Worse than a stale archive: an archive whose `data/items.json` and `images/` disagree, because the
 * image bytes copied after the swap are the *restored* images while the item rows naming them are the
 * pre-restore ones. Restoring that archive later yields a wardrobe whose images do not match its items.
 * A file that is being moved mid-copy can also fail outright, turning a backup into an error.
 *
 * So the whole sequence runs inside one [RestoreStartupGate.withBusinessAccessSuspending]: from the first
 * read of durable app state until the last archive byte is taken. The two directions are then both
 * closed by the same count — an export in progress makes `beginRestore()` refuse
 * (`activeBusinessOps > 0`), and a restore in progress makes the export unable to take a lease at all.
 *
 * ### What the lease does *not* exclude
 *
 * It is worth being exact, because the tempting summary is wrong. The business lease is mutually
 * exclusive only with **restore**. Ordinary business operations — `createItem`, `addWear`, a 计划 write,
 * another export — can acquire a business lease *at the same time*: the counter is a count, not a mutex,
 * and `beginRestore()` is the only thing it locks out. So the guarantee this class buys is precisely:
 *
 * > An export cannot interleave with a restore, and therefore cannot combine pre-restore and
 * > post-restore generations caused by a restore.
 *
 * It does **not** buy transactional consistency against concurrent *ordinary* writes. A user who edits an
 * item while an export runs can still produce an archive in which that one item's row is the pre-edit
 * version — because the export read it a moment earlier — and nothing here prevents that. Closing it
 * would need a real backup transaction (a snapshot the writers also respect), which is a materially
 * larger piece of architecture than a lease; inventing a second gate state to fake it would be worse than
 * leaving it open. It is recorded in the fix8 report as a known follow-up rather than papered over here.
 *
 * The reads themselves are taken from one [WardrobeRepository.snapshot] emission where a caller needs
 * several surfaces at once (see [exportCsv]), which removes the *read-side* version of the same problem
 * for the counters it prints. It does not change the paragraph above: a snapshot is one instant, not a
 * transaction over the writes that follow it.
 *
 * ### What is deliberately *not* leased
 *
 * The scratch work — `cacheDir/backup_export_*`, the staged image and media copies, the ZIP stream itself
 * — is not `filesDir/closie` and is not swapped by a restore, so it needs no lease of its own. It is
 * inside the lease only because the *reads that produced it* had to be. `outputUri` is the user's chosen
 * SAF document, likewise outside the app's private tree.
 *
 * The export deliberately does **not** hold the lease across `openOutputStream` for a slow external
 * provider, because it cannot: the ZIP is written from the staged bytes, and the staging is what had to
 * be protected. A provider that blocks on write extends the lease only because the write is inside the
 * same block — an accepted cost, since the alternative is an archive assembled from two generations.
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
            // `runCatching` is OUTSIDE the lease, not inside it, and the nesting is the contract.
            //
            // Every function here returns `Result`, and a `Result`-returning function must not throw
            // for an expected outcome. A restore owning the gate is exactly that: `beginRestore`
            // returns `false` to ordinary business code, and an export attempted from 设置 while the
            // app is restoring is a normal thing for a user to try. With the lease *inside*
            // `runCatching` — the previous order — `acquireBusinessLease()` threw
            // `RestoreRecoveryPendingException` straight out of `BackupManager.export`, past the
            // caller's `result.onFailure { … }`, and up into the summary-screen coroutine. The
            // assertion this class's own test wrote (`refused.isFailure`) could never be reached,
            // because there was no `Result` to assert on.
            runCatching {
                // One lease for the whole export — see the class doc. It starts before the first read
                // of durable wardrobe state and is released after the ZIP has been written from the
                // staged bytes, so `beginRestore()` cannot take ownership anywhere in between and the
                // archive can never combine pre-restore and post-restore generations.
                RestoreStartupGate.withBusinessAccessSuspending {
                    val gson = BackupValidator.gson
                    // One snapshot emission for all five wardrobe surfaces, not five reads. The lease
                    // excludes a restore from this whole sequence; the single emission is what makes the
                    // five lists internally consistent with each other regardless of any *ordinary*
                    // write landing between two of them — a combination the lease deliberately does not
                    // exclude (see the class doc).
                    val wardrobe = repo.snapshot.value
                    val items = wardrobe.items
                    val wears = wardrobe.wearEvents
                    val washes = wardrobe.washEvents
                    val ootds = wardrobe.ootds
                    val outfits = wardrobe.outfits

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
     *
     * It takes the same operation-level lease as [export], for the same reason: a test fixture built from
     * two generations would make the restore tests assert against an archive no user could ever produce,
     * which is worse than a fixture that is merely unrealistic.
     */
    suspend fun exportWardrobeOnly(
        context: Context,
        repo: WardrobeRepository,
        outputUri: Uri
    ): Result<String> =
        withContext(Dispatchers.IO) {
            // Same nesting as [export], for the same reason: the lease is taken *inside* `runCatching`,
            // so a restore owning the gate is reported as `Result.failure` rather than thrown past the
            // caller. This is a test fixture, but it takes the production lease and therefore has to
            // honour the production contract.
            runCatching {
                RestoreStartupGate.withBusinessAccessSuspending {
                    val gson = BackupValidator.gson
                    // Same one-emission read as [export].
                    val wardrobe = repo.snapshot.value
                    val items = wardrobe.items
                    val wears = wardrobe.wearEvents
                    val washes = wardrobe.washEvents
                    val ootds = wardrobe.ootds
                    val outfits = wardrobe.outfits

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
        }

    /**
     * Exports the wardrobe as a CSV (UTF-8 with BOM) for viewing on a computer.
     *
     * Leased as one operation like the ZIP exports, and — like them — reading through
     * [WardrobeRepository.snapshot] rather than three separate surface flows.
     *
     * The CSV is smaller than the ZIP — no image bytes — but it has a *more visible* version of the same
     * defect if left unguarded: it puts `wearCount` and `washCount` next to each item, and those come from
     * `wear.json`/`wash.json` read *after* `items.json`. A restore landing in between produces a
     * spreadsheet whose per-item counts belong to a different wardrobe than the rows they annotate, and the
     * user has no way to tell.
     *
     * The lease is what excludes a *restore* from the middle of that sequence. It does not, and cannot,
     * exclude an ordinary user mutation — see the class doc. Reading all three lists from one snapshot
     * emission is what makes the counts and the rows they annotate come from the same value regardless of
     * what any other writer is doing, which the lease alone would not give: the repository's per-surface
     * flows are three stores, so `listItems()` + `wearEvents.value` + `washEvents.value` could still be
     * three instants even with the gate closed to restores. One snapshot value is one instant.
     */
    suspend fun exportCsv(context: Context, repo: WardrobeRepository, outputUri: Uri): Result<Unit> =
        withContext(Dispatchers.IO) {
            // `runCatching` outside the lease, exactly as in [export] and [exportWardrobeOnly]: a restore
            // in progress must surface as `Result.failure` through `BackupManager.exportCsv`, which is the
            // contract every caller of this file relies on.
            runCatching {
                RestoreStartupGate.withBusinessAccessSuspending {
                    // One emission, three lists: the rows and the two counts beside them cannot disagree.
                    val wardrobe = repo.snapshot.value
                    val items = wardrobe.items
                    val wearCount = wardrobe.wearEvents.groupingBy { it.itemId }.eachCount()
                    val washCount = wardrobe.washEvents.groupingBy { it.itemId }.eachCount()

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
        }

    private fun csvCell(value: String): String = "\"" + value.replace("\"", "\"\"") + "\""
}
