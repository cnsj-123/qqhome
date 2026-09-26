package com.qq.closie.life.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.room.withTransaction
import com.qq.closie.data.backup.BackupValidator
import com.qq.closie.data.backup.RestoreStartupGate
import com.qq.closie.life.data.database.LifeDatabase
import com.qq.closie.life.repository.MediaRepository
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Outcome of importing one picked image. */
sealed interface MediaImportResult {
    data class Success(
        val asset: MediaAssetEntity,
        val resource: MediaResourceEntity
    ) : MediaImportResult

    /** The picker returned a URI we could not read (revoked grant, missing file, unsupported). */
    data class Failure(val message: String) : MediaImportResult
}

/**
 * Whether the content-addressed target file could be reused or had to be (re)published.
 *
 * Distinguishing these is what lets the failure paths decide whether they are allowed to delete the
 * file: a file this call created is ownerless garbage on failure, while a file a *previous* import
 * already recorded must never be removed.
 */
private enum class PublishOutcome { Reused, NeedsRewrite }

/**
 * Copies a user-picked image into Life OS-managed storage and records it as media.
 *
 * **Why copying is not optional.** `Photo Picker` / `ACTION_OPEN_DOCUMENT` grants are revocable: a
 * persisted URI permission can be dropped when the user clears the app's data, when the provider
 * app is uninstalled, or when the underlying file is deleted from the gallery. A "reference" that
 * stored only that URI would render as a broken thumbnail some weeks later, with no way to tell the
 * difference between "the image is gone" and "we lost permission". Copying the bytes into
 * `filesDir/media/` on the day the user saved the item is what makes a saved screenshot still there
 * next year — which is the entire promise of the 资料库.
 *
 * The original file in the gallery is **never** touched. Nothing here deletes, moves or edits the
 * user's photo library; deletion is a separate, explicitly-confirmed action elsewhere, and only
 * after this copy has been written and re-read successfully.
 */
class MediaStoreImporter(
    private val database: LifeDatabase,
    private val mediaRepository: MediaRepository,
) {

    /**
     * Imports [uri], which must be readable by this process.
     *
     * Runs on IO. Returns [MediaImportResult.Failure] instead of throwing: an import that cannot
     * complete must leave the caller with a message, not an exception, because losing a screenshot
     * import is an annoyance while crashing the capture flow is a bug the user cannot work around.
     */
    suspend fun import(context: Context, uri: Uri): MediaImportResult = withContext(Dispatchers.IO) {
        // ### Why the *whole* durable sequence sits under one lease
        //
        // The previous revision guarded this with a three-point `requireReady()` check: before the
        // transaction, at its start, and at its end. That is a check, not a lock, and the gap it leaves is
        // exactly the bug it was written to prevent:
        //
        // ```
        //   requireReady()  -> ok
        //   FileOutputStream(tmp) writes 40 MB           <-- network-speed copy, hundreds of ms
        //   ... restore begins, deletes and replaces filesDir/media, commits its DB rows ...
        //   tmp.renameTo(target)  -> target lives inside the tree the restore just replaced
        //   requireReady()  -> now closed, throws, Room rolls back
        //   -> the row is gone but `target` is a stray file inside the *restored* media tree
        // ```
        //
        // The end check does roll the row back, which is why this is a leak rather than corruption — but
        // the file write it guarded was never protected, because a check cannot be. Worse, the write can
        // also land *before* the swap and be silently deleted by it, and the dedup read
        // (`findResourceBySha256`) can answer "already imported, reuse this file" about a file the restore
        // is at that moment replacing.
        //
        // So the durable sequence — dedup read, target inspection, temp write, publish, database
        // transaction, and the failure cleanup that must only delete a file *this* call created — runs
        // under one business lease. The lease is refused outright while an unfinished recovery has the
        // gate non-READY (see `withBusinessAccessSuspending`), and holding it makes `beginRestore()`
        // refuse for the duration. Either the import fully precedes the restore or it fully follows it;
        // there is no ordering in which the restore can be interleaved into the middle.
        //
        // The lease is acquired *after* the pure computation (reading the picked URI's bytes, hashing
        // them, decoding dimensions) but *before* anything reads or writes durable state. That work is
        // unbounded — a 40 MB read from a cloud-backed picker URI — and holding the gate closed across it
        // would stall the whole app for a copy that may not even succeed. It touches no shared state, so
        // it is safe to do unlocked; the moment we consult `mediaRepository` or the filesystem, we are
        // inside.
        //
        // Returned as a Failure rather than thrown, because that is this function's contract (see the
        // KDoc): the caller gets a message it can show instead of an exception it must catch. A refusal
        // because the gate is closed is one such message.
        runCatching {
            val resolver = context.contentResolver
            val mimeType = resolver.getType(uri) ?: "image/*"
            val originalName = queryDisplayName(context, uri)

            // Read the bytes first. If this fails there is nothing to copy and nothing to record —
            // no orphan asset row is created. Deliberately outside the lease: see above.
            val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
                ?: return@runCatching MediaImportResult.Failure("无法读取所选图片")

            val sha256 = sha256Of(bytes)
            val dimensions = decodeDimensions(bytes)
            val mediaType = if (mimeType.startsWith("video/", true)) MediaType.VIDEO else MediaType.IMAGE
            val extension = extensionFor(mimeType)

            RestoreStartupGate.withBusinessAccessSuspending {
                // Deduplicate on content hash: saving the same screenshot twice must reuse one file,
                // not silently double the app's storage. Same principle as "one media identity, many
                // business references" — the second import links to the existing asset.
                //
                // Inside the lease on purpose: the answer ("this content is already stored at
                // `managedPath`") is a statement about the media tree, and the restore replaces that
                // tree. Reading it outside would let us return a path that the swap is about to delete.
                val existing = mediaRepository.findResourceBySha256(sha256)
                if (existing != null) {
                    mediaRepository.getMediaAsset(existing.mediaAssetId)?.let { asset ->
                        return@withBusinessAccessSuspending MediaImportResult.Success(asset, existing)
                    }
                }

                val mediaDir = File(context.filesDir, MEDIA_DIR).apply { mkdirs() }
                val target = File(mediaDir, "$sha256.$extension")

                // `tmp → verify → publish`, never a direct write to `target`.
                //
                // Writing `target` in place creates a window in which the file exists at its final,
                // content-addressed name while holding only part of its bytes: if the process is killed
                // during the write (or the disk fills), `target.exists()` is true and the *next* import of
                // the same image takes the "already imported" branch and trusts a truncated file forever.
                // It would compute the same sha256, so it would look like a legitimate dedup hit.
                //
                // So the bytes land in a uniquely-named temp file, are verified against the hash we already
                // know they must have, and only then become visible under `target`.
                val publish = if (target.isFile) {
                    // An existing `target` is not automatically trustworthy — see the note above. It is
                    // reused only if it really is the content we are trying to store; otherwise it is a
                    // leftover from an interrupted or corrupted write and must be replaced, not kept.
                    val existingHash = BackupValidator.sha256Hex(target)
                    val usable = existingHash == sha256 && target.length() == bytes.size.toLong()
                    if (usable) PublishOutcome.Reused else PublishOutcome.NeedsRewrite
                } else {
                    PublishOutcome.NeedsRewrite
                }

                val createdNow = publish == PublishOutcome.NeedsRewrite
                if (createdNow) {
                    val tmp = File(mediaDir, ".$sha256.${java.util.UUID.randomUUID()}.tmp")
                    try {
                        FileOutputStream(tmp).use { out ->
                            out.write(bytes)
                            out.flush()
                            // fsync before the database row points at this path: a row committed ahead of
                            // the bytes is exactly the broken-thumbnail bug this class exists to prevent.
                            out.fd.sync()
                        }
                        if (!tmp.isFile || tmp.length() != bytes.size.toLong()) {
                            throw IllegalStateException("图片写入后大小不符")
                        }
                        // Re-hash what actually landed on disk. This is the step that makes the
                        // content-addressed name honest: the file is proven to be the bytes we hashed,
                        // rather than assumed to be because we wrote them.
                        val writtenHash = BackupValidator.sha256Hex(tmp)
                            ?: throw IllegalStateException("无法校验写入的图片")
                        if (writtenHash != sha256) {
                            throw IllegalStateException("图片写入后校验失败")
                        }
                        // Replace a bad `target` if one is there; publish is an atomic rename either way.
                        // A direct overwrite of `target` would reintroduce the partial-file window.
                        if (target.exists() && !target.delete()) {
                            throw IllegalStateException("无法替换损坏的图片文件")
                        }
                        if (!tmp.renameTo(target)) {
                            throw IllegalStateException("图片落盘失败")
                        }
                    } catch (e: Throwable) {
                        tmp.delete()
                        // A published file this call created is now ownerless, and the caller is about to
                        // report failure without writing a database row for it. Leaving it would leak
                        // storage that nothing references and nothing will ever clean up.
                        if (target.exists() && BackupValidator.sha256Hex(target) != sha256) {
                            target.delete()
                        }
                        throw e
                    }
                }

                // Re-read to prove the file is actually usable before we record it.
                if (!target.canRead() || target.length() == 0L) {
                    if (createdNow) target.delete()
                    return@withBusinessAccessSuspending MediaImportResult.Failure("图片写入后无法读取")
                }

                try {
                    // No `requireReady()` around this transaction any more. The lease *is* the guarantee:
                    // `beginRestore()` cannot succeed while `activeBusinessOps > 0`, so the restore cannot
                    // open its own protocol between the dedup read above and the commit below. The three
                    // checkpoints were an attempt to approximate this without holding anything, and they
                    // could only ever narrow the window, never close it.
                    //
                    // Every media write inside goes through `MediaRepository`, whose methods each take the
                    // same lease re-entrantly (counting, so this is cheap and cannot deadlock), and which
                    // is what keeps the *transaction* itself from overlapping a restore's DB replay.
                    database.withTransaction {
                        val asset = mediaRepository.createMediaAsset(mediaType = mediaType)
                        val resource = mediaRepository.addMediaResource(
                            mediaAssetId = asset.id,
                            role = MediaResourceRole.ORIGINAL,
                            mimeType = mimeType,
                            originalName = originalName,
                            // The source URI is kept for provenance/display only. The authoritative copy is
                            // managedPath, which cannot be revoked — never rely on contentUri alone.
                            contentUri = uri.toString(),
                            managedPath = target.absolutePath,
                            sha256 = sha256,
                            sizeBytes = bytes.size.toLong(),
                            width = dimensions?.first,
                            height = dimensions?.second
                        )
                        MediaImportResult.Success(asset, resource)
                    }
                } catch (e: Throwable) {
                    // The bytes are durable but the row that would own them is not, so the file has no
                    // owner and nothing will ever reference it. Remove it *only* when this call created it:
                    // deleting a file that a previous import already recorded would break that import's
                    // rows and turn a working thumbnail into a missing file.
                    if (createdNow) target.delete()
                    throw e
                }
            }
        }.getOrElse { error ->
            MediaImportResult.Failure(error.message ?: "导入图片失败")
        }
    }

    /** Absolute path of the managed copy for [assetId], or null when it was never imported. */
    suspend fun managedPathFor(assetId: String): String? =
        mediaRepository.getResourcesForAsset(assetId)
            .firstOrNull { it.role == MediaResourceRole.ORIGINAL || it.role == MediaResourceRole.PRIMARY_IMAGE }
            ?.managedPath
            ?.takeIf { File(it).isFile }

    private fun queryDisplayName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
    }.getOrNull()

    /** Bounds-only decode: never allocates the full bitmap just to learn its size. */
    private fun decodeDimensions(bytes: ByteArray): Pair<Int, Int>? = runCatching {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        if (options.outWidth > 0 && options.outHeight > 0) {
            options.outWidth to options.outHeight
        } else {
            null
        }
    }.getOrNull()

    private fun sha256Of(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun extensionFor(mimeType: String): String = when {
        mimeType.contains("png", true) -> "png"
        mimeType.contains("webp", true) -> "webp"
        mimeType.contains("gif", true) -> "gif"
        mimeType.contains("heic", true) -> "heic"
        mimeType.contains("jpeg", true) || mimeType.contains("jpg", true) -> "jpg"
        mimeType.startsWith("video/", true) -> "mp4"
        else -> "img"
    }

    companion object {
        /** App-private directory holding every Life OS-managed media original. */
        const val MEDIA_DIR = "media"

        /** Decodes a managed file to a bitmap for OCR. Bounds-scaled to keep memory sane. */
        suspend fun decodeManaged(path: String, maxDimension: Int = 2048): Bitmap? =
            withContext(Dispatchers.IO) {
                runCatching {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(path, bounds)
                    var sample = 1
                    while (bounds.outWidth / sample > maxDimension || bounds.outHeight / sample > maxDimension) {
                        sample *= 2
                    }
                    BitmapFactory.decodeFile(
                        path,
                        BitmapFactory.Options().apply { inSampleSize = sample }
                    )
                }.getOrNull()
            }
    }
}
