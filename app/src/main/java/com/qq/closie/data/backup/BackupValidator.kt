package com.qq.closie.data.backup

import android.content.Context
import android.net.Uri
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.qq.closie.data.model.*
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * Everything that decides whether a backup (or a directory built from one) is *acceptable*.
 *
 * Split out of the former monolithic `BackupManager` so that validation has exactly one owner and one
 * direction of dependency: exporters and importers call into it, it calls into nothing. The previous
 * arrangement had the same helpers reachable from the export path, the restore path and the recovery
 * path at once, which is how a rule ends up relaxed in one of them and not the others.
 *
 * Nothing here mutates durable state. [writeAtomic] and [stageImageStrict] write inside a staging
 * directory that the caller owns and discards on failure.
 */
internal object BackupValidator {

    /** ZIP resource limits (defensive against corrupt/malicious archives). */
    const val MAX_ENTRIES = 20_000
    const val MAX_ENTRY_SIZE = 512L * 1024 * 1024
    const val MAX_TOTAL_SIZE = 5L * 1024 * 1024 * 1024

    val gson = Gson()

    val itemType = object : TypeToken<List<ClothingItem>>() {}.type
    val wearType = object : TypeToken<List<WearEvent>>() {}.type
    val washType = object : TypeToken<List<WashEvent>>() {}.type
    val ootdType = object : TypeToken<List<Ootd>>() {}.type
    val outfitType = object : TypeToken<List<Outfit>>() {}.type

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

    fun <T> parseFile(file: File, type: java.lang.reflect.Type): List<T> {
        if (!file.exists()) throw IllegalStateException("备份缺少 ${file.name}")
        val result = runCatching { gson.fromJson<List<T>>(file.readText(), type) }
            .getOrElse { throw IllegalStateException("备份数据无法解析（${file.name}）") }
        if (result == null) throw IllegalStateException("备份数据无效（${file.name}）")
        return result
    }

    /** Like [parseFile] but a missing file means an empty list — for optional draft entries. */
    fun <T> parseOptionalFile(file: File, type: java.lang.reflect.Type): List<T> {
        if (!file.exists()) return emptyList()
        val result = runCatching { gson.fromJson<List<T>>(file.readText(), type) }
            .getOrElse { throw IllegalStateException("备份数据无法解析（${file.name}）") }
        return result ?: emptyList()
    }

    /** Basic referential integrity checks; inconsistent backups must fail, not be silently fixed. */
    fun validateReferences(
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

    /**
     * Proves a media entry name from a backup is a plain filename that cannot escape [rootDir], and
     * returns the verified destination file.
     *
     * ### Why this exists as one helper
     *
     * `MediaResourceRecord.archiveFileName` arrives from a file the user may have downloaded from
     * anywhere, so it is untrusted input. `File(mediaStageDir, archiveFileName)` with a name like
     * `../../closie/items.json` or `/sdcard/x` resolves **outside** the staging tree — a corrupt or
     * malicious archive could overwrite the user's wardrobe, or any other path the app can write, while
     * the restore reported success. It is the same class of bug [unzipSafely] already guards for ZIP
     * entry names; this closes the second door into the same room.
     *
     * The check is deliberately layered, and the last layer is the one that actually matters:
     *
     *  1. lexical — non-blank, not `.`/`..`, no `/` or `\`, and `File(name).name == name`;
     *  2. canonical containment — the *resolved* parent must be exactly [rootDir], compared on
     *     canonical paths so symlinks and `..` cannot smuggle the target elsewhere.
     *
     * The lexical rules give a clear error for the common case; the canonical comparison is what makes
     * the guarantee hold on a real filesystem.
     *
     * Stage, path-remap and payload validation all call this one function, so there is exactly one
     * definition of "safe media entry name" in the codebase.
     *
     * @throws IllegalStateException when the name is unsafe. Thrown during staging, i.e. **before** any
     *   live data is touched, so a rejected archive leaves the device untouched.
     */
    fun requireSafeMediaArchiveName(rootDir: File, archiveFileName: String?): File {
        fun reject(reason: String): Nothing =
            throw IllegalStateException("备份中的媒体文件名非法（$reason）：$archiveFileName")

        val name = archiveFileName ?: reject("缺失")
        if (name.isBlank()) reject("为空")
        if (name == "." || name == "..") reject("相对路径")
        if (name.contains('/') || name.contains('\\')) reject("包含路径分隔符")
        // Catches Windows-style, trailing-dot and otherwise normalising inputs that the checks above
        // can miss without the platform's own idea of "a bare file name".
        if (File(name).name != name) reject("不是单一文件名")

        val root = rootDir.canonicalFile
        val dest = File(root, name).canonicalFile
        if (dest.parentFile != root) reject("会逃出媒体目录")
        return dest
    }

    /**
     * Validates every media entry name in [payload] against [mediaRoot].
     *
     * Called when the payload is first parsed, so a hostile archive is rejected up front rather than at
     * the moment a particular file happens to be copied.
     */
    fun validateMediaArchiveNames(payload: LifeBackupPayload, mediaRoot: File) {
        payload.mediaResources.forEach { record ->
            requireSafeMediaArchiveName(mediaRoot, record.archiveFileName)
        }
    }

    fun writeAtomic(dir: File, name: String, content: String) {
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
    fun stageImageStrict(context: Context, extractDir: File, stageDir: File, relPath: String, subdir: String): String {
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

    /**
     * Computes the SHA-256 hex digest of [file], or `null` if the file is missing or unreadable.
     *
     * Used to prove a staged or restored media file is byte-identical to the backup's record. A
     * `null` here means "cannot verify" rather than "does not match", and the caller decides how to
     * treat that — a missing file is a hard failure, an unreadable one is reported, not silently trusted.
     */
    fun sha256Hex(file: File): String? {
        if (!file.isFile) return null
        return runCatching {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { fis ->
                val buffer = ByteArray(8192)
                var read = fis.read(buffer)
                while (read > 0) {
                    digest.update(buffer, 0, read)
                    read = fis.read(buffer)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        }.getOrNull()
    }

    fun unzipSafely(context: Context, inputUri: Uri, destDir: File) {
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

    /** Writes one UTF-8 string entry into an open ZIP. */
    fun writeEntry(zip: java.util.zip.ZipOutputStream, name: String, content: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(content.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }
}
