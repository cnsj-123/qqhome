package com.qq.closie.life.media

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverter

/**
 * A single logical media unit (one photo, one video, one Live Photo, etc.).
 *
 * A MediaAsset owns one or more [MediaResourceEntity] rows that describe the concrete bytes
 * (original file, thumbnail, paired video, …). This indirection lets us represent iOS Live
 * Photos and Android Motion Photos without losing the "one asset" identity.
 *
 * [mediaType] uses an enum + Room converter — never an arbitrary String.
 */
@Entity(
    tableName = "media_assets",
    indices = [
        Index(value = ["mediaType"]),
        Index(value = ["takenAt"]),
        Index(value = ["createdAt"]),
    ]
)
data class MediaAssetEntity(

    @PrimaryKey
    val id: String,

    @ColumnInfo(name = "mediaType")
    val mediaType: MediaType,

    @ColumnInfo(name = "takenAt")
    val takenAt: Long? = null,

    @ColumnInfo(name = "createdAt")
    val createdAt: Long,

    @ColumnInfo(name = "updatedAt")
    val updatedAt: Long,
)

/**
 * Strongly-typed media kind. Must not be collapsed into a plain String — the Room converter
 * enforces type safety at the database boundary.
 */
enum class MediaType {
    IMAGE,
    VIDEO,
    AUDIO,
    LIVE_PHOTO,
    MOTION_PHOTO,
}

class MediaTypeConverter {
    @TypeConverter
    fun toValue(mediaType: MediaType): String = mediaType.name

    @TypeConverter
    fun fromValue(value: String): MediaType = runCatching { MediaType.valueOf(value) }
        .getOrDefault(MediaType.IMAGE)
}
