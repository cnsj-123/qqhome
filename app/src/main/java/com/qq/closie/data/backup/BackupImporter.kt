package com.qq.closie.data.backup

import android.content.Context
import android.net.Uri
import com.qq.closie.data.model.*
import java.io.File

/**
 * What a validated, fully staged restore is about to become.
 *
 * Produced only after *everything* has been proven present and well-formed, so [RestoreCoordinator]
 * runs a commit sequence over values it cannot fail to interpret. Holding the parsed payload and the
 * built staging trees together is deliberate: the previous shape validated in one place and used the
 * values in another, which is how a checkable condition stops being checked.
 *
 * @param payload the typed Life OS payload, or null for a v1 / wardrobe-only archive.
 * @param extractDir the unpacked archive, still needed for `life/media` bytes.
 * @param closetStageDir the fully built future `closie/` directory, not yet swapped in.
 * @param mediaStageDir the fully built future `media/` directory, not yet swapped in (null for v1).
 */
internal class StagedRestore(
    val payload: LifeBackupPayload?,
    val extractDir: File,
    val closetStageDir: File,
    val mediaStageDir: File?
)

/**
 * Unpacks and validates an archive into staging directories. Reads only; it never publishes anything.
 *
 * This is the "Stage" half of the restore, separated from the "Commit" half so that the distinction the
 * atomicity fix depends on is visible in the type system: this class cannot swap a directory or write a
 * database row, and [RestoreCoordinator] cannot validate an archive.
 *
 * Both filesystem surfaces are staged here — the Closet into [closetStageDir] and the Life OS media into
 * [mediaStageDir] — each file written `tmp → copy → validate → rename` so a crash mid-stage can never
 * leave a half-written file masquerading as a real one. The two are published as directory swaps later;
 * nothing here touches live data.
 */
internal object BackupImporter {

    /**
     * Format versions this build can *restore*. A superset of what it *writes* (`FORMAT_VERSION` = 2):
     * v1 archives from before v0.3.0 remain restorable forever.
     */
    val SUPPORTED_RESTORE_FORMATS = setOf(1, 2)

    /**
     * Unpacks [inputUri] and builds the complete, validated staging directories.
     *
     * On any failure the caller is expected to discard [StagedRestore.extractDir], [closetStageDir] and
     * [mediaStageDir]; nothing outside them has been touched.
     */
    fun stage(
        context: Context,
        inputUri: Uri,
        extractDir: File,
        closetStageDir: File,
        mediaStageDir: File
    ): StagedRestore {
        val gson = BackupValidator.gson
        BackupValidator.unzipSafely(context, inputUri, extractDir)

        // 1. Validate manifest.
        val manifestFile = File(extractDir, "manifest.json")
        if (!manifestFile.exists()) throw IllegalStateException("备份缺少 manifest.json")
        val manifest = gson.fromJson(manifestFile.readText(), BackupManifest::class.java)
            ?: throw IllegalStateException("备份 manifest 无效")
        if (manifest.formatVersion !in SUPPORTED_RESTORE_FORMATS) {
            throw IllegalStateException("该备份版本暂不支持（formatVersion=${manifest.formatVersion}）")
        }

        val lifeDataFile = File(extractDir, "life/data.json")
        val declaresLifeOs = manifest.formatVersion >= 2 && manifest.includesLifeOs
        if (declaresLifeOs && !lifeDataFile.isFile) {
            throw IllegalStateException("备份声称包含 Life OS 数据，但 life/data.json 缺失")
        }
        val hasLifeSection = declaresLifeOs && lifeDataFile.isFile

        // Parse and validate the Life OS payload *before* touching anything.
        val lifePayload = if (hasLifeSection) {
            runCatching { gson.fromJson(lifeDataFile.readText(), LifeBackupPayload::class.java) }
                .getOrNull() ?: throw IllegalStateException("Life OS 数据无法解析")
        } else {
            null
        }
        // Reject hostile entry names up front, rather than discovering them at the moment one particular
        // file happens to be copied. The archive's own `life/media` directory is the containment root,
        // because that is what the names are used to read from.
        lifePayload?.let { BackupValidator.validateMediaArchiveNames(it, File(extractDir, "life/media")) }

        // 2. Parse and validate the five wardrobe JSON files.
        val items = BackupValidator.parseFile<ClothingItem>(File(extractDir, "data/items.json"), BackupValidator.itemType)
        val wears = BackupValidator.parseFile<WearEvent>(File(extractDir, "data/wear.json"), BackupValidator.wearType)
        val washes = BackupValidator.parseFile<WashEvent>(File(extractDir, "data/wash.json"), BackupValidator.washType)
        val ootds = BackupValidator.parseFile<Ootd>(File(extractDir, "data/ootds.json"), BackupValidator.ootdType)
        val outfits = BackupValidator.parseFile<Outfit>(File(extractDir, "data/outfits.json"), BackupValidator.outfitType)
        BackupValidator.validateReferences(items, wears, washes, ootds, outfits)

        val ootdDrafts = BackupValidator.parseOptionalFile<Ootd>(File(extractDir, "data/ootd_drafts.json"), BackupValidator.ootdType)
        val outfitDrafts = BackupValidator.parseOptionalFile<Outfit>(File(extractDir, "data/outfit_drafts.json"), BackupValidator.outfitType)

        // 3. Build the full future "closie/" inside staging.
        closetStageDir.mkdirs()
        val restoredItems = items.map { item ->
            item.copy(images = item.images.map { img ->
                img.copy(localPath = if (img.localPath.isNullOrBlank()) null else BackupValidator.stageImageStrict(context, extractDir, closetStageDir, img.localPath, "items"))
            })
        }
        val restoredOotds = ootds.map { o ->
            o.copy(images = o.images.mapNotNull { p -> if (p.isBlank()) null else BackupValidator.stageImageStrict(context, extractDir, closetStageDir, p, "ootd") })
        }
        val restoredOutfits = outfits.map { o ->
            o.copy(tryOnImages = o.tryOnImages.mapNotNull { p -> if (p.isBlank()) null else BackupValidator.stageImageStrict(context, extractDir, closetStageDir, p, "outfit") })
        }
        val restoredOotdDrafts = ootdDrafts.map { o ->
            o.copy(images = o.images.mapNotNull { p -> if (p.isBlank()) null else BackupValidator.stageImageStrict(context, extractDir, closetStageDir, p, "ootd") })
        }
        val restoredOutfitDrafts = outfitDrafts.map { o ->
            o.copy(tryOnImages = o.tryOnImages.mapNotNull { p -> if (p.isBlank()) null else BackupValidator.stageImageStrict(context, extractDir, closetStageDir, p, "outfit") })
        }

        BackupValidator.writeAtomic(closetStageDir, "items.json", gson.toJson(restoredItems))
        BackupValidator.writeAtomic(closetStageDir, "wear.json", gson.toJson(wears))
        BackupValidator.writeAtomic(closetStageDir, "wash.json", gson.toJson(washes))
        BackupValidator.writeAtomic(closetStageDir, "ootds.json", gson.toJson(restoredOotds))
        BackupValidator.writeAtomic(closetStageDir, "outfits.json", gson.toJson(restoredOutfits))

        val draftsDir = File(closetStageDir, "drafts").apply { mkdirs() }
        BackupValidator.writeAtomic(draftsDir, "ootd_drafts.json", gson.toJson(restoredOotdDrafts))
        BackupValidator.writeAtomic(draftsDir, "outfit_drafts.json", gson.toJson(restoredOutfitDrafts))

        // 4. Re-validate the fully assembled staging directory before committing.
        if (!BackupValidator.validateDataDirectory(closetStageDir)) throw IllegalStateException("备份数据校验失败")

        // 5. Stage the Life OS media as a directory swap (v2 only).
        val mediaStage = if (lifePayload != null) {
            stageMedia(extractDir, mediaStageDir, lifePayload)
        } else {
            null
        }

        return StagedRestore(
            payload = lifePayload,
            extractDir = extractDir,
            closetStageDir = closetStageDir,
            mediaStageDir = mediaStage
        )
    }

    /**
     * Copies every referenced Life OS media file into [mediaStageDir], each written
     * `tmp → copy → validate → rename`. Validation checks the source exists, is a regular file with
     * non-zero length, and (when the row records them) matches the declared [MediaResourceEntity.sizeBytes]
     * and [MediaResourceEntity.sha256].
     *
     * Every name goes through [BackupValidator.requireSafeMediaArchiveName] before it is used, so a
     * `../`-style entry can neither read outside the archive's `life/media` tree nor — more importantly —
     * write outside [mediaStageDir]. That check runs here as well as at payload-parse time on purpose:
     * this is the function that actually performs the write, and a guarantee belongs at the point of use
     * as well as at the point of validation.
     *
     * Only after *every* file has been staged and validated does the caller move on to [RestoreState.STAGED].
     */
    private fun stageMedia(extractDir: File, mediaStageDir: File, payload: LifeBackupPayload): File {
        val archiveMediaDir = File(extractDir, "life/media")
        mediaStageDir.mkdirs()
        payload.mediaResources.forEach { record ->
            // Verified destination, and the source is derived from the *same* verified name so the two
            // cannot diverge.
            val dest = BackupValidator.requireSafeMediaArchiveName(mediaStageDir, record.archiveFileName)
            val src = File(archiveMediaDir, dest.name)
            if (!src.isFile || src.length() <= 0L) {
                throw IllegalStateException("备份缺少媒体文件 ${record.archiveFileName}")
            }
            val tmp = File(mediaStageDir, ".${dest.name}.tmp")
            src.copyTo(tmp, overwrite = true)
            if (!tmp.isFile || tmp.length() <= 0L) {
                throw IllegalStateException("媒体文件暂存失败 ${record.archiveFileName}")
            }
            record.row.sizeBytes?.let { expected ->
                if (tmp.length() != expected) {
                    throw IllegalStateException("媒体文件大小不符 ${record.archiveFileName}")
                }
            }
            record.row.sha256?.let { expected ->
                val actual = BackupValidator.sha256Hex(tmp)
                    ?: throw IllegalStateException("无法计算媒体校验值 ${record.archiveFileName}")
                if (actual != expected) {
                    throw IllegalStateException("媒体文件校验失败 ${record.archiveFileName}")
                }
            }
            if (!tmp.renameTo(dest)) throw IllegalStateException("媒体文件落盘失败 ${record.archiveFileName}")
        }
        return mediaStageDir
    }
}
