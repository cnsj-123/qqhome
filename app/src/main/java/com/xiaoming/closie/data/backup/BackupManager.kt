package com.xiaoming.closie.data.backup

import android.content.Context
import android.net.Uri
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.xiaoming.closie.data.draft.DraftStore
import com.xiaoming.closie.data.model.*
import com.xiaoming.closie.data.repository.WardrobeRepository
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

const val BACKUP_FORMAT_VERSION = 1
const val BACKUP_SCHEMA_VERSION = 1

/** Backward/forward-compatible backup format version. */
data class BackupManifest(
    val formatVersion: Int = BACKUP_FORMAT_VERSION,
    val schemaVersion: Int = BACKUP_SCHEMA_VERSION,
    val createdAt: String = ""
)

/**
 * Creates and restores a complete local backup (all JSON plus private images) as a single ZIP.
 * Restore is staged: everything is fully built and validated in a temporary sibling directory and
 * only swapped into place atomically, so a failed restore leaves the current wardrobe untouched.
 */
object BackupManager {
    const val FORMAT_VERSION = BACKUP_FORMAT_VERSION
    const val SCHEMA_VERSION = BACKUP_SCHEMA_VERSION

    // ZIP resource limits (defensive against corrupt/malicious archives).
    private const val MAX_ENTRIES = 20_000
    private const val MAX_ENTRY_SIZE = 512L * 1024 * 1024
    private const val MAX_TOTAL_SIZE = 5L * 1024 * 1024 * 1024

    private val gson = Gson()
    private val itemType = object : TypeToken<List<ClothingItem>>() {}.type
    private val wearType = object : TypeToken<List<WearEvent>>() {}.type
    private val washType = object : TypeToken<List<WashEvent>>() {}.type
    private val ootdType = object : TypeToken<List<Ootd>>() {}.type
    private val outfitType = object : TypeToken<List<Outfit>>() {}.type

    private fun dataDir(context: Context) = File(context.filesDir, "closie")

    /**
     * Recovers from a restore that was interrupted (e.g. process killed) between the two directory
     * renames. Must run before the repository reads JSON. Never throws.
     */
    fun recoverInterruptedRestore(context: Context) {
        val filesDir = context.filesDir
        val closie = File(filesDir, "closie")
        val olds = listDirs(filesDir, ".closie_restore_old_")
        val stages = listDirs(filesDir, ".closie_restore_stage_")

        // "closie" missing but an old snapshot exists -> likely died mid-swap; restore the old data.
        if (!closie.exists() && olds.isNotEmpty()) {
            runCatching { olds.first().renameTo(closie) }
        }
        // Stale staging dirs are always safe to drop.
        stages.forEach { runCatching { it.deleteRecursively() } }
        // Only drop stale old snapshots once the live directory looks valid, to never delete the
        // user's only complete copy.
        if (closie.exists() && olds.isNotEmpty() && validateDataDirectory(closie)) {
            olds.forEach { runCatching { it.deleteRecursively() } }
        }
    }

    private fun listDirs(parent: File, prefix: String): List<File> =
        parent.listFiles()?.filter { it.name.startsWith(prefix) }?.sortedByDescending { it.lastModified() }.orEmpty()

    /**
     * Returns true when a data directory contains all five core JSON files and they all parse.
     * Draft files are optional (older backups have none); when present they must also parse.
     */
    fun validateDataDirectory(dir: File): Boolean {
        val files = listOf(
            "items.json" to itemType,
            "wear.json" to wearType,
            "wash.json" to washType,
            "ootds.json" to ootdType,
            "outfits.json" to outfitType
        )
        val coreValid = files.all { (name, type) ->
            val f = File(dir, name)
            f.isFile && runCatching { gson.fromJson<Any>(f.readText(), type) }.getOrNull() != null
        }
        if (!coreValid) return false
        val draftsDir = File(dir, "drafts")
        val draftFiles = listOf(
            "ootd_drafts.json" to ootdType,
            "outfit_drafts.json" to outfitType
        )
        return draftFiles.all { (name, type) ->
            val f = File(draftsDir, name)
            if (!f.exists()) true // draft-less directories (old data) remain valid
            else runCatching { gson.fromJson<Any>(f.readText(), type) }.getOrNull() != null
        }
    }

    /** Writes a complete backup ZIP to [outputUri] (SAF document). */
    suspend fun export(context: Context, repo: WardrobeRepository, outputUri: Uri): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
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

                // Drafts are user data too: a complete backup must include them and their images.
                val draftStore = DraftStore(context)
                val ootdDrafts = draftStore.listOotdDrafts()
                val outfitDrafts = draftStore.listOutfitDrafts()
                ootdDrafts.forEach { it.images.forEach { p -> if (p.isNotBlank()) allPaths += p } }
                outfitDrafts.forEach { it.tryOnImages.forEach { p -> if (p.isNotBlank()) allPaths += p } }

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

                val manifest = BackupManifest(
                    formatVersion = FORMAT_VERSION,
                    schemaVersion = SCHEMA_VERSION,
                    createdAt = java.time.Instant.now().toString()
                )

                context.contentResolver.openOutputStream(outputUri)?.use { raw ->
                    ZipOutputStream(raw).use { zip ->
                        fun writeEntry(name: String, content: String) {
                            zip.putNextEntry(ZipEntry(name))
                            zip.write(content.toByteArray(Charsets.UTF_8))
                            zip.closeEntry()
                        }
                        writeEntry("manifest.json", gson.toJson(manifest))
                        writeEntry("data/items.json", gson.toJson(mappedItems))
                        writeEntry("data/wear.json", gson.toJson(wears))
                        writeEntry("data/wash.json", gson.toJson(washes))
                        writeEntry("data/ootds.json", gson.toJson(mappedOotds))
                        writeEntry("data/outfits.json", gson.toJson(mappedOutfits))
                        writeEntry("data/ootd_drafts.json", gson.toJson(mappedOotdDrafts))
                        writeEntry("data/outfit_drafts.json", gson.toJson(mappedOutfitDrafts))
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

    /** Restores a backup ZIP via a fully staged, rollback-safe flow. */
    suspend fun restore(context: Context, repo: WardrobeRepository, inputUri: Uri): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val ts = System.currentTimeMillis()
                val extractDir = File(context.cacheDir, "backup_restore_extract_$ts")
                val stageDir = File(context.filesDir, ".closie_restore_stage_$ts")
                val oldDir = File(context.filesDir, ".closie_restore_old_$ts")
                try {
                    unzipSafely(context, inputUri, extractDir)

                    // 1. Validate manifest.
                    val manifestFile = File(extractDir, "manifest.json")
                    if (!manifestFile.exists()) throw IllegalStateException("备份缺少 manifest.json")
                    val manifest = gson.fromJson(manifestFile.readText(), BackupManifest::class.java)
                        ?: throw IllegalStateException("备份 manifest 无效")
                    if (manifest.formatVersion != FORMAT_VERSION) {
                        throw IllegalStateException("该备份版本暂不支持（formatVersion=${manifest.formatVersion}）")
                    }

                    // 2. Parse and validate all JSON.
                    val items = parseFile<ClothingItem>(File(extractDir, "data/items.json"), itemType)
                    val wears = parseFile<WearEvent>(File(extractDir, "data/wear.json"), wearType)
                    val washes = parseFile<WashEvent>(File(extractDir, "data/wash.json"), washType)
                    val ootds = parseFile<Ootd>(File(extractDir, "data/ootds.json"), ootdType)
                    val outfits = parseFile<Outfit>(File(extractDir, "data/outfits.json"), outfitType)
                    validateReferences(items, wears, washes, ootds, outfits)

                    // Drafts are optional: older backups without draft files still restore cleanly
                    // and are interpreted as an empty draft list.
                    val ootdDrafts = parseOptionalFile<Ootd>(File(extractDir, "data/ootd_drafts.json"), ootdType)
                    val outfitDrafts = parseOptionalFile<Outfit>(File(extractDir, "data/outfit_drafts.json"), outfitType)

                    // 3. Build the full future "closie/" inside staging.
                    stageDir.mkdirs()
                    val restoredItems = items.map { item ->
                        item.copy(images = item.images.map { img ->
                            img.copy(localPath = if (img.localPath.isNullOrBlank()) null else stageImageStrict(context, extractDir, stageDir, img.localPath, "items"))
                        })
                    }
                    val restoredOotds = ootds.map { o ->
                        o.copy(images = o.images.mapNotNull { p ->
                            if (p.isBlank()) null else stageImageStrict(context, extractDir, stageDir, p, "ootd")
                        })
                    }
                    val restoredOutfits = outfits.map { o ->
                        o.copy(tryOnImages = o.tryOnImages.mapNotNull { p ->
                            if (p.isBlank()) null else stageImageStrict(context, extractDir, stageDir, p, "outfit")
                        })
                    }
                    val restoredOotdDrafts = ootdDrafts.map { o ->
                        o.copy(images = o.images.mapNotNull { p ->
                            if (p.isBlank()) null else stageImageStrict(context, extractDir, stageDir, p, "ootd")
                        })
                    }
                    val restoredOutfitDrafts = outfitDrafts.map { o ->
                        o.copy(tryOnImages = o.tryOnImages.mapNotNull { p ->
                            if (p.isBlank()) null else stageImageStrict(context, extractDir, stageDir, p, "outfit")
                        })
                    }

                    // 4. Write the staged JSON.
                    writeAtomic(stageDir, "items.json", gson.toJson(restoredItems))
                    writeAtomic(stageDir, "wear.json", gson.toJson(wears))
                    writeAtomic(stageDir, "wash.json", gson.toJson(washes))
                    writeAtomic(stageDir, "ootds.json", gson.toJson(restoredOotds))
                    writeAtomic(stageDir, "outfits.json", gson.toJson(restoredOutfits))

                    // Restored drafts live in the same closie/drafts folder the app reads, so the
                    // store picks them up immediately after the swap — nothing is deleted here.
                    val draftsDir = File(stageDir, "drafts").apply { mkdirs() }
                    writeAtomic(draftsDir, "ootd_drafts.json", gson.toJson(restoredOotdDrafts))
                    writeAtomic(draftsDir, "outfit_drafts.json", gson.toJson(restoredOutfitDrafts))

                    // 5. Re-validate the fully assembled staging directory before committing.
                    if (!validateDataDirectory(stageDir)) throw IllegalStateException("备份数据校验失败")

                    // 6. Commit via directory-level swap with rollback.
                    val closieDir = dataDir(context)
                    if (closieDir.exists() && !closieDir.renameTo(oldDir)) {
                        throw IllegalStateException("无法暂存旧数据")
                    }
                    try {
                        if (!stageDir.renameTo(closieDir)) {
                            if (oldDir.exists() && !closieDir.exists()) runCatching { oldDir.renameTo(closieDir) }
                            throw IllegalStateException("无法应用新数据")
                        }
                    } catch (e: Exception) {
                        if (oldDir.exists() && !closieDir.exists()) runCatching { oldDir.renameTo(closieDir) }
                        throw e
                    }

                    // 7. Refresh in-memory state and health-check before dropping the old snapshot.
                    repo.reloadFromDisk()
                    if (!validateDataDirectory(closieDir)) {
                        if (oldDir.exists()) {
                            runCatching { closieDir.deleteRecursively() }
                            runCatching { oldDir.renameTo(closieDir) }
                            repo.reloadFromDisk()
                        }
                        throw IllegalStateException("恢复后数据校验失败")
                    }
                    oldDir.deleteRecursively()
                    Unit
                } finally {
                    extractDir.deleteRecursively()
                    if (stageDir.exists()) stageDir.deleteRecursively()
                }
            }
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

    private fun <T> parseFile(file: File, type: java.lang.reflect.Type): List<T> {
        if (!file.exists()) throw IllegalStateException("备份缺少 ${file.name}")
        val result = runCatching { gson.fromJson<List<T>>(file.readText(), type) }
            .getOrElse { throw IllegalStateException("备份数据无法解析（${file.name}）") }
        if (result == null) throw IllegalStateException("备份数据无效（${file.name}）")
        return result
    }

    /** Like [parseFile] but a missing file means an empty list — for optional draft entries. */
    private fun <T> parseOptionalFile(file: File, type: java.lang.reflect.Type): List<T> {
        if (!file.exists()) return emptyList()
        val result = runCatching { gson.fromJson<List<T>>(file.readText(), type) }
            .getOrElse { throw IllegalStateException("备份数据无法解析（${file.name}）") }
        return result ?: emptyList()
    }

    /** Basic referential integrity checks; inconsistent backups must fail, not be silently fixed. */
    private fun validateReferences(
        items: List<ClothingItem>,
        wears: List<WearEvent>,
        washes: List<WashEvent>,
        ootds: List<Ootd>,
        outfits: List<Outfit>
    ) {
        fun fail() { throw IllegalStateException("备份数据引用关系无效") }

        val itemIds = items.map { it.id }.toSet()
        if (items.any { it.id.isBlank() }) fail()
        if (itemIds.size != items.size) fail()

        val wearIds = wears.map { it.id }.toSet()
        if (wearIds.size != wears.size) fail()
        wears.forEach { w ->
            if (w.itemId !in itemIds) fail()
            if (w.source == WearSource.OOTD) {
                if (w.ootdId.isNullOrBlank()) fail()
                if (ootds.none { it.id == w.ootdId }) fail()
            }
        }

        val washIds = washes.map { it.id }.toSet()
        if (washIds.size != washes.size) fail()
        washes.forEach { w -> if (w.itemId !in itemIds) fail() }

        val ootdIds = ootds.map { it.id }.toSet()
        if (ootdIds.size != ootds.size) fail()
        val ownedIds = items.filter { it.status == ItemStatus.OWNED }.map { it.id }.toSet()
        ootds.forEach { o -> o.itemIds.forEach { iid -> if (iid !in ownedIds) fail() } }

        val outfitIds = outfits.map { it.id }.toSet()
        if (outfitIds.size != outfits.size) fail()
        outfits.forEach { o ->
            o.itemIds.forEach { iid -> if (iid !in itemIds) fail() }
            o.placements.forEach { p -> if (p.itemId !in itemIds) fail() }
        }
    }

    private fun writeAtomic(dir: File, name: String, content: String) {
        dir.mkdirs()
        val f = File(dir, name)
        val t = File(dir, ".$name.tmp")
        t.writeText(content)
        if (!t.renameTo(f)) { f.delete(); check(t.renameTo(f)) }
    }

    /**
     * Copies a backed-up image ("images/xxx.png") into staging and returns its final absolute path.
     * Strictly validates the reference: canonical path must stay inside the backup's images/ tree,
     * the file must exist, be a regular file and have non-zero size. Throws on any violation.
     */
    private fun stageImageStrict(context: Context, extractDir: File, stageDir: File, relPath: String, subdir: String): String {
        if (!relPath.startsWith("images/") || relPath.contains("..")) {
            throw IllegalStateException("备份引用的图片路径非法：$relPath")
        }
        val imagesRoot = File(extractDir, "images").canonicalFile
        val candidate = File(extractDir, relPath).canonicalFile
        if (!candidate.path.startsWith(imagesRoot.path + File.separator)) {
            throw IllegalStateException("备份引用的图片路径非法：$relPath")
        }
        if (!candidate.exists() || !candidate.isFile) {
            throw IllegalStateException("备份引用的图片缺失或无效：$relPath")
        }
        if (candidate.length() <= 0L) {
            throw IllegalStateException("备份引用的图片无效：$relPath")
        }
        val targetSubdir = when (subdir) {
            "ootd" -> "images/ootd"
            "outfit" -> "images/outfit"
            else -> "images"
        }
        val targetDir = File(stageDir, targetSubdir).apply { mkdirs() }
        val ext = candidate.extension.ifBlank { "bin" }
        val name = "${UUID.randomUUID()}.$ext"
        val target = File(targetDir, name)
        candidate.copyTo(target, overwrite = true)
        if (target.length() <= 0L) throw IllegalStateException("备份图片复制失败：$relPath")
        return File(context.filesDir, "closie/$targetSubdir/$name").absolutePath
    }

    private fun unzipSafely(context: Context, inputUri: Uri, destDir: File) {
        val root = destDir.canonicalFile
        var entryCount = 0
        var totalBytes = 0L
        val seen = mutableSetOf<String>()
        context.contentResolver.openInputStream(inputUri)?.use { raw ->
            ZipInputStream(raw).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    entryCount++
                    if (entryCount > MAX_ENTRIES) throw IllegalStateException("备份文件条目过多")
                    if (entry.size > MAX_ENTRY_SIZE) throw IllegalStateException("备份文件过大")
                    if (!seen.add(entry.name)) throw IllegalStateException("备份文件包含重复条目：${entry.name}")
                    val target = File(root, entry.name).canonicalFile
                    if (!target.path.startsWith(root.path + File.separator)) {
                        throw SecurityException("备份包含非法路径：${entry.name}")
                    }
                    if (entry.isDirectory) {
                        target.mkdirs()
                    } else {
                        target.parentFile?.mkdirs()
                        val buffer = ByteArray(8192)
                        var entryBytes = 0L
                        target.outputStream().use { out ->
                            var read = zip.read(buffer)
                            while (read > 0) {
                                entryBytes += read
                                totalBytes += read
                                if (entryBytes > MAX_ENTRY_SIZE) throw IllegalStateException("备份文件单条目过大")
                                if (totalBytes > MAX_TOTAL_SIZE) throw IllegalStateException("备份文件过大")
                                out.write(buffer, 0, read)
                                read = zip.read(buffer)
                            }
                        }
                    }
                    zip.closeEntry()
                }
            }
        } ?: throw IllegalStateException("无法读取备份文件")
    }
}
