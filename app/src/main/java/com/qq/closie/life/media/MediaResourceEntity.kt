package com.qq.closie.life.media

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverter

/**
 * Concrete bytes that belong to a [MediaAssetEntity].
 *
 * Roles (see [MediaResourceRole]):
 *  - ORIGINAL            — the untouched source file
 *  - PRIMARY_IMAGE       — the still image of a Live Photo / Motion Photo
 *  - PAIRED_VIDEO        — the short video paired with a Live Photo
 *  - ORIGINAL_CONTAINER  — single file that embeds both image + motion (Android Motion Photo)
 *  - PREVIEW             — a web-friendly preview
 *  - THUMBNAIL           — a small thumbnail
 *  - AUDIO               — an extracted or standalone audio track
 */
@Entity(
    tableName = "media_resources",
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
        Index(value = ["mediaAssetId", "role"]),
        Index(value = ["sha256"]),
    ]
)
data class MediaResourceEntity(

    @PrimaryKey
    val id: String,

    @ColumnInfo(name = "mediaAssetId")
    val mediaAssetId: String,

    @ColumnInfo(name = "role")
    val role: MediaResourceRole,

    @ColumnInfo(name = "mimeType")
    val mimeType: String,

    @ColumnInfo(name = "originalName")
    val originalName: String? = null,

    @ColumnInfo(name = "contentUri")
    val contentUri: String? = null,

    @ColumnInfo(name = "mediaStoreId")
    val mediaStoreId: Long? = null,

    @ColumnInfo(name = "managedPath")
    val managedPath: String? = null,

    @ColumnInfo(name = "remoteObjectKey")
    val remoteObjectKey: String? = null,

    @ColumnInfo(name = "sha256")
    val sha256: String? = null,

    @ColumnInfo(name = "sizeBytes")
    val sizeBytes: Long? = null,

    @ColumnInfo(name = "width")
    val width: Int? = null,

    @ColumnInfo(name = "height")
    val height: Int? = null,

    @ColumnInfo(name = "durationMs")
    val durationMs: Long? = null,

    @ColumnInfo(name = "createdAt")
    val createdAt: Long,
)

enum class MediaResourceRole {
    ORIGINAL,
    PRIMARY_IMAGE,
    PAIRED_VIDEO,
    ORIGINAL_CONTAINER,
    PREVIEW,
    THUMBNAIL,
    AUDIO,
}

class MediaResourceRoleConverter {
    @TypeConverter
    fun toValue(role: MediaResourceRole): String = role.name

    @TypeConverter
    fun fromValue(value: String): MediaResourceRole = runCatching { MediaResourceRole.valueOf(value) }
        .getOrDefault(MediaResourceRole.ORIGINAL)
}
