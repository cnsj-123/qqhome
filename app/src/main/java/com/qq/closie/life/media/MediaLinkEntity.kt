package com.qq.closie.life.media

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Links a [MediaAssetEntity] to the business [com.qq.closie.life.core.LifeEntityEntity] that owns
 * or references it.
 *
 * Core principle: one media identity, many business references.
 * A single photo can be linked to a Trip, a Journal, and a Plant — without copying three file copies.
 *
 * MediaLink does not allow dangling references: both the owner entity and the media asset must
 * already exist. Integrity is enforced by repository validation in
 * [com.qq.closie.life.repository.MediaRepository.linkMedia].
 */
@Entity(
    tableName = "media_links",
    foreignKeys = [
        ForeignKey(
            entity = MediaAssetEntity::class,
            parentColumns = ["id"],
            childColumns = ["mediaAssetId"],
            onDelete = ForeignKey.NO_ACTION,
        ),
    ],
    indices = [
        Index(value = ["mediaAssetId"]),
        Index(value = ["ownerEntityId"]),
        Index(value = ["ownerEntityId", "role"]),
        Index(value = ["ownerEntityId", "sortOrder"]),
    ]
)
data class MediaLinkEntity(

    @PrimaryKey
    val id: String,

    @ColumnInfo(name = "mediaAssetId")
    val mediaAssetId: String,

    @ColumnInfo(name = "ownerEntityId")
    val ownerEntityId: String,

    @ColumnInfo(name = "role")
    val role: String,

    @ColumnInfo(name = "sortOrder")
    val sortOrder: Int = 0,

    @ColumnInfo(name = "createdAt")
    val createdAt: Long,
)
