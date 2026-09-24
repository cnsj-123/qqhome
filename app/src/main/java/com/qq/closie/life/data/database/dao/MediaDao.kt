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
}
