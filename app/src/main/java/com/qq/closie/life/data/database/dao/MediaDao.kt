package com.qq.closie.life.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.qq.closie.life.media.MediaAssetEntity
import com.qq.closie.life.media.MediaLinkEntity
import com.qq.closie.life.media.MediaResourceEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MediaDao {

    // --- MediaAsset ---

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAsset(asset: MediaAssetEntity)

    @Query("SELECT * FROM media_assets WHERE id = :id")
    suspend fun getAssetById(id: String): MediaAssetEntity?

    @Query("SELECT * FROM media_assets ORDER BY createdAt DESC LIMIT :limit")
    fun observeRecentAssets(limit: Int): Flow<List<MediaAssetEntity>>

    @Query("SELECT COUNT(*) FROM media_assets")
    suspend fun countAssets(): Int

    // --- MediaResource ---

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertResource(resource: MediaResourceEntity)

    @Query("SELECT * FROM media_resources WHERE mediaAssetId = :assetId ORDER BY createdAt ASC")
    suspend fun getResourcesForAsset(assetId: String): List<MediaResourceEntity>

    @Query("SELECT * FROM media_resources WHERE mediaAssetId = :assetId ORDER BY createdAt ASC")
    fun observeResourcesForAsset(assetId: String): Flow<List<MediaResourceEntity>>

    @Query("SELECT * FROM media_resources WHERE sha256 = :sha256 LIMIT 1")
    suspend fun findResourceBySha256(sha256: String): MediaResourceEntity?

    /**
     * Every resource in the database, ordered for a deterministic backup.
     *
     * Used only by [com.qq.closie.data.backup.BackupManager.export] to enumerate the managed files a
     * v2 archive must contain. Ordered by `createdAt, id` rather than relying on insertion order so
     * two exports of the same data produce byte-identical entry lists — which is what makes the
     * export testable at all.
     */
    @Query("SELECT * FROM media_resources ORDER BY createdAt ASC, id ASC")
    suspend fun getAllResources(): List<MediaResourceEntity>

    // --- MediaLink ---

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertLink(link: MediaLinkEntity)

    @Query("DELETE FROM media_links WHERE id = :id")
    suspend fun deleteLinkById(id: String)

    @Query("DELETE FROM media_links WHERE mediaAssetId = :assetId AND ownerEntityId = :ownerId")
    suspend fun deleteLink(assetId: String, ownerId: String): Int

    @Query("SELECT * FROM media_links WHERE mediaAssetId = :assetId ORDER BY sortOrder ASC")
    suspend fun getLinksForAsset(assetId: String): List<MediaLinkEntity>

    @Query("SELECT * FROM media_links WHERE ownerEntityId = :ownerId ORDER BY sortOrder ASC")
    fun observeLinksForOwner(ownerId: String): Flow<List<MediaLinkEntity>>

    @Query("SELECT * FROM media_links WHERE ownerEntityId = :ownerId ORDER BY sortOrder ASC")
    suspend fun getLinksForOwner(ownerId: String): List<MediaLinkEntity>

    @Query("SELECT COUNT(*) FROM media_links WHERE ownerEntityId = :ownerId")
    suspend fun countLinksForOwner(ownerId: String): Int

    // ---- Backup (v2) --------------------------------------------------------------------------
    // Media rows are exported whole-table for the same reason as the other entities, and restored
    // in asset → resource → link order because each references the previous.

    @Query("SELECT * FROM media_assets")
    suspend fun getAllAssets(): List<MediaAssetEntity>

    @Query("SELECT * FROM media_links")
    suspend fun getAllLinks(): List<MediaLinkEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAllAssets(assets: List<MediaAssetEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAllResources(resources: List<MediaResourceEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAllLinks(links: List<MediaLinkEntity>)

    @Query("DELETE FROM media_links")
    suspend fun deleteAllLinks()

    @Query("DELETE FROM media_resources")
    suspend fun deleteAllResources()

    @Query("DELETE FROM media_assets")
    suspend fun deleteAllAssets()
}
