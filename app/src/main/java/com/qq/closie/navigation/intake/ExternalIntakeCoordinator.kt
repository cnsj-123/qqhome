package com.qq.closie.navigation.intake

import com.qq.closie.life.capture.CaptureSource
import com.qq.closie.life.reference.ReferenceImporter
import com.qq.closie.life.repository.CaptureRepository
import com.qq.closie.navigation.ExternalNavCommand
import com.qq.closie.navigation.LifeOsRoute
import java.util.UUID

/** Evidence first. A Reference is an unclassified source archive, never a domain fact. */
class ExternalIntakeCoordinator(
    private val captures: CaptureRepository,
    private val references: ReferenceImporter
) {
    suspend fun intake(command: ExternalNavCommand): String {
        require(command is ExternalNavCommand.ReferenceLink || command is ExternalNavCommand.CaptureText)
        // Reprocessing the same saved UI request cannot duplicate its evidence row.
        val id = UUID.nameUUIDFromBytes("external-intake:${command.nonce}".toByteArray()).toString()
        val text = when (command) {
            is ExternalNavCommand.ReferenceLink -> command.originalText
            is ExternalNavCommand.CaptureText -> command.text
            else -> error("Not an intake command")
        }
        captures.create(id = id, source = CaptureSource.SHARE, rawText = text,
            sourceUrl = (command as? ExternalNavCommand.ReferenceLink)?.url)
        return if (command is ExternalNavCommand.ReferenceLink) fileToLibrary(id) else LifeOsRoute.capture(id)
    }

    suspend fun fileToLibrary(id: String): String = try {
        references.importCapture(id)?.let { LifeOsRoute.referenceEdit(it.reference.id) }
            ?: LifeOsRoute.capture(id)
    } catch (error: kotlinx.coroutines.CancellationException) {
        throw error
    } catch (_: Exception) {
        // Evidence survives network/metadata failure and remains openable.
        LifeOsRoute.capture(id)
    }
}
