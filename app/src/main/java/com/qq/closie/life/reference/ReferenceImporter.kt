package com.qq.closie.life.reference

import android.content.Context
import android.net.Uri
import com.qq.closie.data.ocr.OcrEngine
import com.qq.closie.life.capture.CaptureItemEntity
import com.qq.closie.life.capture.CaptureSource
import com.qq.closie.life.data.database.LifeDatabase
import com.qq.closie.life.media.MediaImportResult
import com.qq.closie.life.media.MediaStoreImporter
import com.qq.closie.life.repository.CaptureRepository
import com.qq.closie.life.repository.ReferenceRepository
import com.qq.closie.life.web.WebMetadataReader
import java.time.Instant
import java.time.ZoneId

/**
 * The result of distilling something into a 资料库 entry.
 *
 * [ocrFailed] and [metadataFetched] are reported rather than hidden because the caller may want to
 * say something truthful ("图片已保存，文字识别失败") instead of implying the pipeline fully
 * succeeded. Silently degrading a partial success into an unqualified one is how a user ends up
 * trusting a field that is empty for a reason.
 */
data class ReferenceImportResult(
    val reference: ReferenceItemEntity,
    val ocrFailed: Boolean = false,
    val metadataFetched: Boolean = false
)

/**
 * Turns raw material — a screenshot, a shared photo, a pasted link — into a 资料库 entry.
 *
 * This is the piece that makes 资料库 an actual workflow rather than a table. It exists as its own
 * class (not as logic inside a ViewModel or a repository) because it *coordinates* three
 * collaborators that must not know about each other:
 *
 *   [MediaStoreImporter]  copies bytes into Life OS's private storage
 *   [OcrEngine]           reads text off the image, on device
 *   [WebMetadataReader]   reads title/description off a page
 *   [ReferenceRepository] writes the strongly-typed row + its LifeEntity
 *
 * **Every step is best-effort and independent.** A screenshot with no readable text still becomes a
 * reference. A link whose page refuses to load still becomes a reference — with the URL as its
 * title. The one thing that must succeed is that the user's *thing* is kept; everything derived
 * from it is a bonus. That ordering is deliberate: it is always better to file something with less
 * metadata than to fail and lose it.
 *
 * **OCR is limited on purpose.** Recognising a 4000px screenshot at full resolution costs seconds
 * and hundreds of megabytes of bitmap; [MediaStoreImporter.decodeManaged] downsamples to 2048px
 * first, which is more than enough for body text and keeps the import feeling instant. The first
 * line of recognised text becomes the title, because on a screenshot the first line usually *is*
 * the heading — and a reference titled by its own content is one the user can find again.
 */
class ReferenceImporter(
    private val context: Context,
    private val database: LifeDatabase,
    private val mediaStoreImporter: MediaStoreImporter,
    private val referenceRepository: ReferenceRepository,
    private val captureRepository: CaptureRepository,
    private val webMetadataReader: WebMetadataReader,
    private val zone: ZoneId = ZoneId.systemDefault()
) {

    /**
     * The gallery path, end to end, in one call.
     *
     * The shell hands over the URI the Photo Picker returned; this method does everything the user
     * expects "从相册" to mean:
     *
     *   1. copy the bytes into managed storage (**once**),
     *   2. OCR the managed copy,
     *   3. create the 记录 row that points at that asset,
     *   4. create the INBOX 资料库 entry distilled from it,
     *   5. link the same asset to the reference.
     *
     * The reason it lives here rather than in the shell is the ordering constraint that makes it
     * correct: steps 3–5 must all use the **same** [MediaAssetEntity]. The shell used to import the
     * image, write a capture row, and stop — so "从相册" produced a record with no 资料库 entry, no
     * OCR and no way forward. Doing the import once, here, is what guarantees there is exactly one
     * copy of the bytes and exactly one asset identity shared by the record and the reference.
     *
     * Returns the reference so the caller can navigate to it. The capture is created first and is
     * **never** deleted on the failure path — §20 again: a record is history, and losing the
     * user's screenshot because filing it failed would be the app deleting the wrong thing.
     */
    suspend fun importGalleryImage(uri: Uri): GalleryImportResult? {
        val import = mediaStoreImporter.import(context, uri)
        if (import is MediaImportResult.Failure) return null
        val success = import as MediaImportResult.Success

        val captureId = java.util.UUID.randomUUID().toString()
        captureRepository.create(
            id = captureId,
            source = CaptureSource.GALLERY,
            primaryMediaAssetId = success.asset.id
        )

        // OCR the managed copy, not the picker URI — the grant may already be gone.
        var ocrText: String? = null
        var ocrFailed = false
        success.resource.managedPath?.let { path ->
            val bitmap = MediaStoreImporter.decodeManaged(path)
            if (bitmap == null) {
                ocrFailed = true
            } else {
                val result = OcrEngine.recognizeText(bitmap)
                bitmap.recycle()
                if (result.isSuccess) {
                    ocrText = result.getOrNull()?.takeIf { it.isNotBlank() }
                } else {
                    ocrFailed = true
                }
            }
        }

        val reference = referenceRepository.create(
            title = suggestTitleFromOcr(ocrText) ?: defaultImageTitle(),
            referenceType = ReferenceType.OTHER,
            summary = ocrText?.let { firstMeaningfulParagraph(it) },
            ocrText = ocrText,
            originalCaptureId = captureId
        )
        referenceRepository.linkMedia(
            referenceId = reference.id,
            mediaAssetId = success.asset.id
        )

        return GalleryImportResult(
            captureId = captureId,
            referenceId = reference.id,
            mediaAssetId = success.asset.id,
            ocrFailed = ocrFailed
        )
    }

    /** Outcome of [importGalleryImage]: both rows, and the single asset they share. */
    data class GalleryImportResult(
        val captureId: String,
        val referenceId: String,
        val mediaAssetId: String,
        val ocrFailed: Boolean
    )

    /**
     * Imports a link, reading its title/description first.
     *
     * Never fails. If the page cannot be read the URL is still filed — a link the user chose to keep
     * is worth keeping even when the site is unreachable, and refusing to save it would mean the
     * user loses the thing because the *site* was down.
     */
    suspend fun importLink(
        url: String,
        originalCaptureId: String? = null
    ): ReferenceImportResult {
        val metadata = webMetadataReader.read(url)
        val reference = referenceRepository.create(
            title = metadata.displayTitle,
            referenceType = ReferenceType.ARTICLE,
            summary = metadata.description,
            sourceUrl = metadata.url,
            sourceName = metadata.displaySource,
            author = metadata.author,
            originalCaptureId = originalCaptureId
        )
        return ReferenceImportResult(
            reference = reference,
            ocrFailed = false,
            metadataFetched = metadata.fetched
        )
    }

    /**
     * Imports a capture that already exists (text / clipboard / share / gallery) as a reference.
     *
     * Reads the capture through [CaptureRepository] rather than taking its fields as arguments, so a
     * caller cannot pass a stale copy: whatever the user has edited into the record by now is what
     * gets filed.
     *
     * **Idempotent.** If this capture has already been filed, the existing reference is returned
     * instead of a second one being created. The check is not merely an optimisation: the same
     * capture is reachable from 记录详情's 存进资料库, from the gallery import flow, and (for an
     * already-saved item) from re-opening a screen, and every one of those paths would otherwise
     * append another near-identical row. A user who filed a screenshot twice would then have two
     * copies to tag, edit and delete, with no way to tell which was which.
     *
     * **Handles a pure-image capture.** The previous version required some text field to be
     * non-blank and returned null otherwise, so a screenshot with no OCR output — the most common
     * thing a user files — produced nothing at all while the UI implied success. Now the capture's
     * own media is the fallback: the managed asset it already owns is reused (never re-copied,
     * never re-OCR'd) and filed as an image reference.
     */
    suspend fun importCapture(
        captureId: String,
        referenceType: ReferenceType? = null
    ): ReferenceImportResult? {
        val capture = captureRepository.getById(captureId) ?: return null

        // Already filed once — hand back what exists rather than creating a duplicate.
        referenceRepository.findByOriginalCaptureId(captureId)?.let { existing ->
            return ReferenceImportResult(reference = existing)
        }

        // The check above is the fast path; the UNIQUE index on `originalCaptureId` is the guarantee.
        // Two coroutines can both pass the `find` above before either writes, so the losing insert
        // throws instead of silently producing a second reference. That is the index doing its job —
        // but a thrown exception is not an acceptable outcome for the user, who asked for their
        // capture to be filed, not for an error. So the collision is caught and resolved into the
        // correct answer: return the reference the winner created.
        return runCatching { createReferenceFor(capture, captureId, referenceType) }
            .getOrElse { error ->
                referenceRepository.findByOriginalCaptureId(captureId)?.let { existing ->
                    ReferenceImportResult(reference = existing)
                } ?: throw error
            }
    }

    /** The actual filing work, split out so [importCapture] can retry it through a unique collision. */
    private suspend fun createReferenceFor(
        capture: CaptureItemEntity,
        captureId: String,
        referenceType: ReferenceType?
    ): ReferenceImportResult? {
        val url = capture.sourceUrl?.takeIf { it.isNotBlank() }
        if (url != null) {
            return importLink(url, originalCaptureId = captureId)
        }

        val text = listOfNotNull(capture.displayTitle, capture.rawText, capture.note)
            .firstOrNull { it.isNotBlank() }

        if (text == null) {
            // No text anywhere. If the capture owns media, file that instead of giving up.
            val assetId = capture.primaryMediaAssetId
                ?: return null // a genuinely empty record: nothing to file, and saying so is correct
            return importCaptureMedia(
                captureId = captureId,
                mediaAssetId = assetId,
                title = capture.displayTitle,
                referenceType = referenceType
            )
        }

        val reference = referenceRepository.create(
            title = capture.displayTitle?.takeIf { it.isNotBlank() }
                ?: firstLine(text)
                ?: defaultImageTitle(),
            referenceType = referenceType ?: ReferenceType.NOTE,
            summary = capture.note?.takeIf { it!!.isNotBlank() },
            ocrText = capture.rawText?.takeIf { it.isNotBlank() },
            originalCaptureId = captureId
        )
        return ReferenceImportResult(reference = reference)
    }

    /**
     * Files a capture that is *only* an image — no text was captured or recognised.
     *
     * The capture's managed asset is **reused, not re-imported.** It was already copied into
     * `filesDir/media/` by the capture pipeline (and de-duplicated on SHA-256 then), so importing it
     * again would at best be a wasted copy and at worst silently attach a second, different asset to
     * the same photo. This is the "one media identity, many business references" rule in practice:
     * the gallery record and the 资料库 entry end up pointing at the *same* [MediaAssetEntity], so a
     * later unlink from either side cannot orphan or duplicate the bytes.
     *
     * OCR is not re-run here either. Whatever the capture pipeline recognised is already stored on
     * the capture; re-running a several-second ML Kit pass on a screen that is meant to feel instant
     * would be a regression, and the result would be identical.
     */
    private suspend fun importCaptureMedia(
        captureId: String,
        mediaAssetId: String,
        title: String?,
        referenceType: ReferenceType?
    ): ReferenceImportResult? {
        val asset = database.mediaDao().getAssetById(mediaAssetId) ?: return null

        val reference = referenceRepository.create(
            title = title?.takeIf { it.isNotBlank() } ?: defaultImageTitle(),
            referenceType = referenceType ?: ReferenceType.OTHER,
            originalCaptureId = captureId
        )
        // linkMedia validates both sides; a false return means the asset row disappeared between
        // the lookup above and here, in which case the reference is still worth keeping.
        referenceRepository.linkMedia(referenceId = reference.id, mediaAssetId = asset.id)
        return ReferenceImportResult(reference = reference)
    }

    // ------------------------------------------------------------------
    //  Title heuristics
    // ------------------------------------------------------------------

    /**
     * The first non-empty line of recognised text, capped so a wall of text on a screenshot does not
     * become a 200-character title that blows out the list row.
     *
     * Returns null rather than an empty string when there is nothing usable, so the caller can fall
     * through to the next strategy instead of having to test for blankness.
     */
    internal fun suggestTitleFromOcr(ocrText: String?): String? {
        val firstLine = ocrText
            ?.lineSequence()
            ?.map { it.trim() }
            ?.firstOrNull { it.isNotEmpty() }
            ?: return null
        return firstLine.take(MAX_TITLE_LENGTH).takeIf { it.isNotBlank() }
    }

    private fun firstLine(text: String): String? =
        text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }
            ?.take(MAX_TITLE_LENGTH)
            ?.takeIf { it.isNotBlank() }

    /**
     * The first paragraph of OCR output, used as the summary.
     *
     * A paragraph rather than a line because a summary made of one line of a screenshot ("限时优惠")
     * tells the user nothing, whereas a few lines give them a reason to open it. Capped at
     * [MAX_SUMMARY_LENGTH] so the list row stays a row.
     */
    internal fun firstMeaningfulParagraph(ocrText: String): String? {
        val paragraph = ocrText
            .split('\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString(" ")
            .take(MAX_SUMMARY_LENGTH)
        return paragraph.takeIf { it.isNotBlank() }
    }

    /**
     * The fallback title for an image with no readable text.
     *
     * A timestamp, not "未命名" or "图片": the user has a list of these, and a list of identical
     * labels is unusable. A date at least tells them *when* they saved it, which is how people
     * actually remember screenshots.
     */
    private fun defaultImageTitle(): String =
        "图片 · " + Instant.now().atZone(zone).toLocalDate().toString()

    companion object {
        /** A title should fit on one line of a 393dp list row without truncating mid-word. */
        const val MAX_TITLE_LENGTH = 60

        const val MAX_SUMMARY_LENGTH = 140
    }
}
