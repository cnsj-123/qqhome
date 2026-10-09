package com.qq.closie.life.reference

import com.qq.closie.data.backup.RestoreStartupGate
import com.qq.closie.life.capture.CaptureItemEntity
import com.qq.closie.life.repository.CaptureRepository
import com.qq.closie.life.repository.MediaRepository
import com.qq.closie.life.repository.ReferenceRepository
import com.qq.closie.life.web.WebMetadataReader
import com.qq.closie.life.web.WebMetadata
import java.time.Instant
import java.time.ZoneId

data class ReferenceImportResult(
    val reference: ReferenceItemEntity,
    val ocrFailed: Boolean = false,
    val metadataFetched: Boolean = false
)

/** Intake into an unclassified source archive; never confirms domain business truth. */
class ReferenceImporter(
    private val referenceRepository: ReferenceRepository,
    private val captureRepository: CaptureRepository,
    private val mediaRepository: MediaRepository,
    private val readMetadata: suspend (String) -> WebMetadata = WebMetadataReader()::read,
    private val zone: ZoneId = ZoneId.systemDefault()
) {

    suspend fun importLink(
        url: String,
        originalCaptureId: String? = null
    ): ReferenceImportResult {
        val metadata = readMetadata(url)
        val reference = referenceRepository.create(
            title = metadata.displayTitle,
            referenceType = ReferenceType.ARTICLE,
            ocrText = metadata.description,
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

    suspend fun importCapture(
        captureId: String,
        referenceType: ReferenceType? = null
    ): ReferenceImportResult? {
        val capture = captureRepository.getById(captureId) ?: return null

        referenceRepository.findByOriginalCaptureId(captureId)?.let { existing ->
            return ReferenceImportResult(reference = existing)
        }

        return runCatching { createReferenceFor(capture, captureId, referenceType) }
            .getOrElse { error ->
                referenceRepository.findByOriginalCaptureId(captureId)?.let { existing ->
                    ReferenceImportResult(reference = existing)
                } ?: throw error
            }
    }

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
            val assetId = capture.primaryMediaAssetId
                ?: return null // a genuinely empty record: nothing to file, and saying so is correct
            return importCaptureMedia(
                captureId = captureId,
                mediaAssetId = assetId,
                title = capture.displayTitle,
                referenceType = referenceType
            )
        }

        return RestoreStartupGate.withBusinessAccessSuspending {
            val live = captureRepository.getById(captureId)
                ?: return@withBusinessAccessSuspending null

            referenceRepository.findByOriginalCaptureId(captureId)?.let { existing ->
                return@withBusinessAccessSuspending ReferenceImportResult(reference = existing)
            }

            val liveText = listOfNotNull(live.displayTitle, live.rawText, live.note)
                .firstOrNull { it.isNotBlank() }
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

    private suspend fun importCaptureMedia(
        captureId: String,
        mediaAssetId: String,
        title: String?,
        referenceType: ReferenceType?
    ): ReferenceImportResult? {
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
            referenceRepository.linkMedia(referenceId = reference.id, mediaAssetId = asset.id)
            ReferenceImportResult(reference = reference)
        }
    }

    private fun firstLine(text: String): String? =
        text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }
            ?.take(MAX_TITLE_LENGTH)
            ?.takeIf { it.isNotBlank() }

    private fun defaultImageTitle(): String =
        "图片 · " + Instant.now().atZone(zone).toLocalDate().toString()

    companion object {

        const val MAX_TITLE_LENGTH = 60

    }
}
