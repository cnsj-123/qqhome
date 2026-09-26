package com.qq.closie.life.reference

import android.content.Context
import android.net.Uri
import com.qq.closie.data.backup.RestoreStartupGate
import com.qq.closie.data.ocr.OcrEngine
import com.qq.closie.life.capture.CaptureItemEntity
import com.qq.closie.life.capture.CaptureSource
import com.qq.closie.life.data.database.LifeDatabase
import com.qq.closie.life.media.MediaImportResult
import com.qq.closie.life.media.MediaStoreImporter
import com.qq.closie.life.repository.CaptureRepository
import com.qq.closie.life.repository.MediaRepository
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
    /**
     * The live database.
     *
     * Retained even though nothing in this class touches it any more: the one direct DAO access it used
     * to have (`importCaptureMedia`'s asset lookup) now goes through [MediaRepository] so it inherits the
     * business lease instead of asserting a precondition it could not keep. Removing the parameter would
     * change the constructor for no correctness gain, and it remains the honest description of what this
     * importer operates on — but note that *no* new direct DAO call belongs here. Reach for a repository.
     */
    private val database: LifeDatabase,
    private val mediaStoreImporter: MediaStoreImporter,
    private val referenceRepository: ReferenceRepository,
    private val captureRepository: CaptureRepository,
    private val mediaRepository: MediaRepository,
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
     *
     * ### `null` means "nothing was filed", and it is returned for more than a failed copy
     *
     * Steps 1–2 happen outside any lease (the copy has its own; OCR has none), so by the time the durable
     * sequence opens, `success.asset` describes a moment that may already be over — a restore completing
     * during OCR is the case that matters. Steps 3–5 therefore **re-validate** rather than assume:
     * the asset is re-read under the lease, and the `Boolean` results of `captureRepository.create` and
     * `linkMedia` are checked instead of discarded. See the comment on the lease body for why each of the
     * two booleans is load-bearing.
     *
     * The consequence for callers: `null` does **not** mean "no reference exists" — a reference may have
     * been created and left unlinked, which is a deliberate outcome rather than a leak. It means "the
     * gallery import did not complete, do not navigate anywhere or report success."
     */
    suspend fun importGalleryImage(uri: Uri): GalleryImportResult? {
        // Step 1 — copy the bytes. `MediaStoreImporter.import` holds its own business lease over the
        // whole durable half of the copy (dedup read, temp write, publish, asset/resource rows), so by
        // the time it returns the file exists under `filesDir/media/` and the asset row is committed.
        // That lease is released here; the steps below take a fresh one.
        val import = mediaStoreImporter.import(context, uri)
        if (import is MediaImportResult.Failure) return null
        val success = import as MediaImportResult.Success

        val captureId = java.util.UUID.randomUUID().toString()

        // Step 2 — OCR. Deliberately *outside* the lease: decoding a 2048px bitmap and running ML Kit
        // takes seconds and hundreds of megabytes, it touches no shared state, and holding the gate
        // closed across it would freeze every other screen for the duration of a local OCR pass. The
        // only thing it reads is a file that the import above just published.
        val ocrOutcome = ocrManagedCopy(success.resource.managedPath)
        var ocrText: String? = null
        var ocrFailed = false
        when (ocrOutcome) {
            is OcrOutcome.Text -> ocrText = ocrOutcome.text
            OcrOutcome.NoText -> ocrFailed = true
            // No managed path at all: nothing to OCR. Not a failure of OCR, so it is not reported as
            // one — but `ocrText` stays null, which is the same input to the title heuristic.
            OcrOutcome.Skipped -> Unit
        }

        // ### Steps 3–5 run under one lease, because they are one durable sequence
        //
        // Creating the 记录, distilling the 资料库 entry, and linking the shared asset are three writes
        // that only mean anything together. Each individual repository call takes its own lease, so a
        // restore *cannot* be interleaved into the middle of any one of them — but that is not enough,
        // and the gap is not academic:
        //
        // ```
        //   captureRepository.create(captureId)     <- lease released
        //   ... restore begins, replays the DB, rolls the media tree ...
        //   referenceRepository.create(...)         <- lease re-acquired, succeeds
        //   -> a 资料库 entry whose `originalCaptureId` names a 记录 the restore deleted
        // ```
        //
        // That is a dangling foreign key in all but name, produced by the recovery path, on data the user
        // just asked to file. Holding one lease across all three makes the sequence atomic with respect to
        // the restore: either every row is written before the restore's protocol opens, or none is.
        //
        // The import's own lease above was released before OCR, so this is a second, independent lease
        // rather than one long-held one — the OCR gap is precisely the unbounded stretch we must not hold
        // the gate across.
        //
        // ### The re-validation this second lease must begin with (BLOCKER 5)
        //
        // Re-acquiring the lease proves the gate is open *now*. It says nothing about the world
        // `success.asset.id` came from — and between the import returning and this lease being taken there
        // were two unbounded stretches, OCR (seconds, hundreds of megabytes) and the lease wait itself. A
        // restore can complete in either:
        //
        // ```
        //   mediaStoreImporter.import(...) -> asset "A" committed, bytes under filesDir/media/
        //   ... OCR (seconds) ...
        //   ... a restore replays the database and rolls the media tree ...
        //   captureRepository.create(primaryMediaAssetId = "A")  -> the asset row is GONE
        // ```
        //
        // What made this worse than a stale read is what the old code then did with the answers:
        //
        //  - `captureRepository.create` returns a `Boolean` and it was **discarded**. Its own
        //    implementation returns `false` precisely when `primaryMediaAssetId` names no existing asset,
        //    so the exact case above produced `false` — the record row was never written, and the caller
        //    was told nothing.
        //  - `linkMedia`'s `Boolean` was discarded too, so the missing link was invisible as well.
        //  - The function still returned a fully-populated `GalleryImportResult`.
        //
        // The user was therefore shown success for a screenshot that had no record row, no link, and a
        // `reference.originalCaptureId` pointing at a capture that does not exist. Every field in the
        // result was a fabrication. So the lease body below is now a sequence of *four* checks where it
        // used to be three writes: re-read the asset, create the capture and check it, create the
        // reference, link and check it. Each failure is reported as the honest outcome (null — nothing was
        // filed) rather than being papered over, because a partial success here is indistinguishable from
        // success and leads the caller to navigate to a row that is not there.
        return RestoreStartupGate.withBusinessAccessSuspending {
            // The durable input, re-validated under the same lease that will use it. This is the whole
            // point of the two-phase shape: `success.asset` is a value read outside the lease, and a
            // value read outside the lease may not be carried into a durable write.
            val liveAsset = mediaRepository.getMediaAsset(success.asset.id)
                ?: return@withBusinessAccessSuspending null

            // `Boolean` honoured: `false` means the capture row was not written (the id already existed
            // with different content, or the asset row it would point at did not resolve). Continuing
            // would file a reference whose `originalCaptureId` names nothing.
            val captureCreated = captureRepository.create(
                id = captureId,
                source = CaptureSource.GALLERY,
                primaryMediaAssetId = liveAsset.id
            )
            if (!captureCreated) return@withBusinessAccessSuspending null

            val reference = referenceRepository.create(
                title = suggestTitleFromOcr(ocrText) ?: defaultImageTitle(),
                referenceType = ReferenceType.OTHER,
                summary = ocrText?.let { firstMeaningfulParagraph(it) },
                ocrText = ocrText,
                originalCaptureId = captureId
            )

            // `Boolean` honoured here as well. A false means the reference exists but the image was not
            // attached — the user would open a 资料库 entry with no picture and no indication why, which
            // is the "implying the pipeline fully succeeded" failure this class's doc comment opens with.
            // The reference is deliberately *not* deleted: §20, and a text-only reference is still worth
            // keeping. It is simply not reported as a successful gallery import.
            val linked = referenceRepository.linkMedia(
                referenceId = reference.id,
                mediaAssetId = liveAsset.id
            )
            if (!linked) return@withBusinessAccessSuspending null

            GalleryImportResult(
                captureId = captureId,
                referenceId = reference.id,
                mediaAssetId = liveAsset.id,
                ocrFailed = ocrFailed
            )
        }
    }

    /**
     * Runs OCR on a managed copy, reporting the three outcomes the caller must distinguish.
     *
     * A sealed result rather than a nullable string because "there was no managed path", "the bitmap
     * could not be decoded / OCR threw", and "OCR succeeded but found no text" are three different
     * statements, and collapsing them loses the one the user sees: `ocrFailed` is shown as "文字识别失败",
     * which would be a lie for a capture that simply had no image to read.
     */
    private sealed interface OcrOutcome {
        data class Text(val text: String?) : OcrOutcome
        data object NoText : OcrOutcome
        data object Skipped : OcrOutcome
    }

    private suspend fun ocrManagedCopy(managedPath: String?): OcrOutcome {
        if (managedPath == null) return OcrOutcome.Skipped
        val bitmap = MediaStoreImporter.decodeManaged(managedPath) ?: return OcrOutcome.NoText
        // `finally`, not a call after the fact: the previous revision recycled the bitmap only on the
        // path where `recognizeText` returned, so a throw leaked a ~2048px bitmap for the life of the
        // process. Best-effort here means "report NoText", not "propagate": this class's contract is that
        // a screenshot still becomes a reference even when the derived text cannot be produced, and an
        // escaping exception would instead lose the whole import.
        val result = runCatching {
            try {
                OcrEngine.recognizeText(bitmap)
            } finally {
                bitmap.recycle()
            }
        }.getOrNull() ?: return OcrOutcome.NoText
        return if (result.isSuccess) {
            OcrOutcome.Text(result.getOrNull()?.takeIf { it.isNotBlank() })
        } else {
            OcrOutcome.NoText
        }
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
     *
     * ### Two phases: read outside the lease, decide inside it
     *
     * The capture is read here, under its own short lease, because the *shape* of the work depends on it:
     * a capture with a `sourceUrl` is a link import (network, no database), one with text is a note
     * import, one that is only an image reuses its own asset. Those are genuinely different paths and the
     * choice between them has to be made before the durable write.
     *
     * What must **not** happen is to carry the entity itself across that boundary. `capture` is a
     * snapshot of a row taken at a moment when a restore may have been about to begin:
     *
     * ```
     *   capture = captureRepository.getById(captureId)   <- lease released here
     *   ... a restore replays the database; the capture row is gone ...
     *   referenceRepository.create(originalCaptureId = captureId)
     *   -> a 资料库 entry naming a 记录 that no longer exists, on data the user just asked to file
     * ```
     *
     * So the value is used only to *choose a path* and to seed pure/network work (a URL, some text). Every
     * path that writes re-acquires the lease and re-reads the capture from the durable state before it
     * writes — see [createReferenceFor], [importCaptureMedia] and [importLink]. `referenceType` and
     * `captureId` are plain values, safe to carry; the entity is not.
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
        //
        // The dispatch is on the *value* read above (a URL string, the text fields), which is exactly the
        // safe part to carry: it decides which durable path runs, and each durable path re-reads.
        return runCatching { createReferenceFor(capture, captureId, referenceType) }
            .getOrElse { error ->
                referenceRepository.findByOriginalCaptureId(captureId)?.let { existing ->
                    ReferenceImportResult(reference = existing)
                } ?: throw error
            }
    }

    /**
     * Chooses the filing path for [capture] and runs it.
     *
     * **Pure/network decisions are made from [capture]; every durable write re-reads.** The entity passed
     * in is deliberately used only for `sourceUrl`, the text fields and `primaryMediaAssetId` — values, all
     * of them — and the writes it leads to ([importLink], the note branch here, [importCaptureMedia]) each
     * take their own lease and re-read the capture (or, for a link, only need its id) before touching the
     * database. See [importCapture] for the interleaving this prevents.
     */
    private suspend fun createReferenceFor(
        capture: CaptureItemEntity,
        captureId: String,
        referenceType: ReferenceType?
    ): ReferenceImportResult? {
        val url = capture.sourceUrl?.takeIf { it.isNotBlank() }
        if (url != null) {
            // `importLink` is network work plus one repository write; it takes the id, never the entity.
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

        // ### The note branch is a durable write, so it re-reads under its own lease
        //
        // The values above (`displayTitle`, `note`, `rawText`) are copied from an entity read outside any
        // lease. Writing them without re-checking would file a reference whose `originalCaptureId` names a
        // capture the restore may have deleted — and, worse, the user would be told it succeeded. So the
        // fields are re-read from inside the lease and the row's continued existence is confirmed before
        // the reference is created; if the capture is gone, nothing is filed and the caller gets `null`.
        return RestoreStartupGate.withBusinessAccessSuspending {
            val live = captureRepository.getById(captureId)
                ?: return@withBusinessAccessSuspending null

            // Idempotency re-checked *inside* the lease as well. The outer `findByOriginalCaptureId` was
            // a fast path taken under a different lease, so it proves nothing about this moment: a
            // concurrent filing of the same capture could have completed in between. Re-checking here
            // turns "the UNIQUE index will throw" from the primary defence into the backstop it should be.
            referenceRepository.findByOriginalCaptureId(captureId)?.let { existing ->
                return@withBusinessAccessSuspending ReferenceImportResult(reference = existing)
            }

            val liveText = listOfNotNull(live.displayTitle, live.rawText, live.note)
                .firstOrNull { it.isNotBlank() }
            // The capture stopped having any text to file while we waited. Not an error — the row exists
            // but there is nothing in it that this branch can file — and `null` is the honest answer.
                ?: return@withBusinessAccessSuspending null

            val reference = referenceRepository.create(
                title = live.displayTitle?.takeIf { it.isNotBlank() }
                    ?: firstLine(liveText)
                    ?: defaultImageTitle(),
                referenceType = referenceType ?: ReferenceType.NOTE,
                summary = live.note?.takeIf { it.isNotBlank() },
                ocrText = live.rawText?.takeIf { it.isNotBlank() },
                originalCaptureId = captureId
            )
            ReferenceImportResult(reference = reference)
        }
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
        // ### This used to be the one ungated DB access in the class, guarded by a bare `requireReady()`
        //
        // The previous revision reached `database.mediaDao().getAssetById(...)` directly and prefixed it
        // with a `requireReady()` check "so a blocked process could not still read assets and then fail
        // halfway through creating the reference". A check cannot provide that: the read and the create
        // are two separate acquisitions, and between them a restore can begin, replay the database and
        // delete the very asset the read returned. The sequence then creates a reference whose
        // `linkMedia` target is a row that no longer exists.
        //
        // The fix has two parts, and both are needed:
        //
        //  1. the lookup goes through [MediaRepository]/`referenceRepository` like everything else, so it
        //     takes the business lease instead of asserting a precondition it cannot keep; and
        //  2. the lookup and the two writes share **one** lease, so the `linkMedia` below cannot observe a
        //     different world from the one the lookup saw.
        //
        // The lookup returning null ("the asset is gone") stays a null result rather than a throw: the
        // capture had no fileable media, which is a legitimate outcome, not an error.
        //
        // ### The capture is re-read here, and the idempotency check re-run (BLOCKER 6)
        //
        // `captureId` and `mediaAssetId` arrive as plain Strings, but both name rows that were read
        // outside this lease — `mediaAssetId` from the capture entity, which `importCapture` fetched under
        // a lease that has since been released. A restore in the gap deletes either row. Writing a
        // reference whose `originalCaptureId` names a missing 记录 is the dangling-key shape this class
        // has already been burned by once, so the sequence below re-establishes both facts under the lease
        // that performs the write:
        //
        //   1. the capture still exists (its lifecycle entity is what `originalCaptureId` points at),
        //   2. it has not been filed in the meantime,
        //   3. the asset still exists (which is what `linkMedia` will re-check anyway, but checking here
        //      keeps the reference from being created for a link that cannot be made).
        //
        // The asset lookup uses the id passed in, not `live.primaryMediaAssetId`: the caller chose the
        // asset deliberately (it *is* the capture's current one as of the read), and silently switching to
        // a different asset because the row changed would attach the wrong file. If the id is gone, the
        // honest answer is "nothing to file".
        return RestoreStartupGate.withBusinessAccessSuspending {
            if (captureRepository.getById(captureId) == null) {
                return@withBusinessAccessSuspending null
            }
            referenceRepository.findByOriginalCaptureId(captureId)?.let { existing ->
                return@withBusinessAccessSuspending ReferenceImportResult(reference = existing)
            }
            val asset = mediaRepository.getMediaAsset(mediaAssetId)
                ?: return@withBusinessAccessSuspending null

            val reference = referenceRepository.create(
                title = title?.takeIf { it.isNotBlank() } ?: defaultImageTitle(),
                referenceType = referenceType ?: ReferenceType.OTHER,
                originalCaptureId = captureId
            )
            // linkMedia validates both sides; a false return means the asset row disappeared between
            // the lookup above and here, in which case the reference is still worth keeping.
            referenceRepository.linkMedia(referenceId = reference.id, mediaAssetId = asset.id)
            ReferenceImportResult(reference = reference)
        }
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
