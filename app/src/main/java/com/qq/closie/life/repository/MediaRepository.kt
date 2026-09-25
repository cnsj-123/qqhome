package com.qq.closie.life.repository

import androidx.room.withTransaction
import com.qq.closie.life.data.database.LifeDatabase
import com.qq.closie.life.data.database.dao.MediaDao
import com.qq.closie.life.media.MediaAssetEntity
import com.qq.closie.life.media.MediaLinkEntity
import com.qq.closie.life.media.MediaResourceEntity
import com.qq.closie.life.media.MediaResourceRole
import com.qq.closie.life.media.MediaType
import java.util.UUID
import kotlinx.coroutines.flow.Flow

/**
 * Repository for the media foundation: assets, resources, and links.
 *
 * Core principle: one media identity, many business references.
 * A single photo can be linked to a Trip, a Journal, and a Plant without copying file copies.
 *
 * [linkMedia] refuses to create a dangling reference: both the owner entity AND the media asset
 * must already exist, otherwise it throws [IllegalArgumentException].
 */
class MediaRepository(
    private val database: LifeDatabase,
) {
    private val mediaDao: MediaDao = database.mediaDao()
    private val entityDao = database.lifeEntityDao()

    // ------------------------------------------------------------------
    //  MediaAsset
    // ------------------------------------------------------------------

    suspend fun createMediaAsset(
        mediaType: MediaType,
        takenAt: Long? = null,
        timestamp: Long = System.currentTimeMillis(),
    ): MediaAssetEntity {
        val asset = MediaAssetEntity(
            id = UUID.randomUUID().toString(),
            mediaType = mediaType,
            takenAt = takenAt,
            createdAt = timestamp,
            updatedAt = timestamp,
        )
        mediaDao.insertAsset(asset)
        return asset
    }

    suspend fun getMediaAsset(id: String): MediaAssetEntity? = mediaDao.getAssetById(id)

    fun observeRecentAssets(limit: Int = 20): Flow<List<MediaAssetEntity>> =
        mediaDao.observeRecentAssets(limit)

    suspend fun countAssets(): Int = mediaDao.countAssets()

    // ------------------------------------------------------------------
    //  MediaResource
    // ------------------------------------------------------------------

    suspend fun addMediaResource(
        mediaAssetId: String,
        role: MediaResourceRole,
        mimeType: String,
        originalName: String? = null,
        contentUri: String? = null,
        mediaStoreId: Long? = null,
        managedPath: String? = null,
        remoteObjectKey: String? = null,
        sha256: String? = null,
        sizeBytes: Long? = null,
        width: Int? = null,
        height: Int? = null,
        durationMs: Long? = null,
        timestamp: Long = System.currentTimeMillis(),
    ): MediaResourceEntity {
        val resource = MediaResourceEntity(
            id = UUID.randomUUID().toString(),
            mediaAssetId = mediaAssetId,
            role = role,
            mimeType = mimeType,
            originalName = originalName,
            contentUri = contentUri,
            mediaStoreId = mediaStoreId,
            managedPath = managedPath,
            remoteObjectKey = remoteObjectKey,
            sha256 = sha256,
            sizeBytes = sizeBytes,
            width = width,
            height = height,
            durationMs = durationMs,
            createdAt = timestamp,
        )
        mediaDao.insertResource(resource)
        return resource
    }

    suspend fun getResourcesForAsset(assetId: String): List<MediaResourceEntity> =
        mediaDao.getResourcesForAsset(assetId)

    /**
     * The managed (app-private, non-revocable) image file for [assetId], or null.
     *
     * Returns a path only when the file is actually present on disk — a row whose bytes have been
     * cleared would otherwise hand Coil a path that resolves to a broken image, which is worse than
     * showing no thumbnail at all. ORIGINAL and PRIMARY_IMAGE are both accepted because an asset
     * that has been through a re-encode keeps its bytes under the latter.
     */
    suspend fun managedImagePathFor(assetId: String): String? =
        getResourcesForAsset(assetId)
            .firstOrNull {
                (it.role == MediaResourceRole.ORIGINAL || it.role == MediaResourceRole.PRIMARY_IMAGE) &&
                    it.managedPath != null
            }
            ?.managedPath
            ?.takeIf { java.io.File(it).isFile }

    fun observeResourcesForAsset(assetId: String): Flow<List<MediaResourceEntity>> =
        mediaDao.observeResourcesForAsset(assetId)

    suspend fun findResourceBySha256(sha256: String): MediaResourceEntity? =
        mediaDao.findResourceBySha256(sha256)

    // ------------------------------------------------------------------
    //  MediaLink
    // ------------------------------------------------------------------

    /**
     * Links a media asset to a business entity.
     *
     * Both references are validated so no dangling link can ever be created:
     *  - the owner entity must exist in life_entities
     *  - the media asset must exist in media_assets
     *
     * @throws IllegalArgumentException when either side is missing.
     */
    suspend fun linkMedia(
        mediaAssetId: String,
        ownerEntityId: String,
        role: String,
        sortOrder: Int = 0,
        timestamp: Long = System.currentTimeMillis(),
    ): MediaLinkEntity {
        require(entityDao.getById(ownerEntityId) != null) {
            "Cannot link media: owner entity $ownerEntityId does not exist"
        }
        require(mediaDao.getAssetById(mediaAssetId) != null) {
            "Cannot link media: media asset $mediaAssetId does not exist"
        }
        val link = MediaLinkEntity(
            id = UUID.randomUUID().toString(),
            mediaAssetId = mediaAssetId,
            ownerEntityId = ownerEntityId,
            role = role,
            sortOrder = sortOrder,
            createdAt = timestamp,
        )
        mediaDao.insertLink(link)
        return link
    }

    suspend fun unlinkMedia(id: String) {
        mediaDao.deleteLinkById(id)
    }

    suspend fun unlinkMedia(assetId: String, ownerId: String) {
        mediaDao.deleteLink(assetId, ownerId)
    }

    suspend fun getLinksForAsset(assetId: String): List<MediaLinkEntity> =
        mediaDao.getLinksForAsset(assetId)

    suspend fun getLinksForOwner(ownerId: String): List<MediaLinkEntity> =
        mediaDao.getLinksForOwner(ownerId)

    fun observeLinksForOwner(ownerId: String): Flow<List<MediaLinkEntity>> =
        mediaDao.observeLinksForOwner(ownerId)

    suspend fun countLinksForOwner(ownerId: String): Int = mediaDao.countLinksForOwner(ownerId)

    // ------------------------------------------------------------------
    //  Transaction helper
    // ------------------------------------------------------------------

    suspend fun <T> withTransaction(block: suspend () -> T): T {
        return database.withTransaction(block)
    }
}
