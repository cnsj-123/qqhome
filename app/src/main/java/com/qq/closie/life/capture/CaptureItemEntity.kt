package com.qq.closie.life.capture

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import androidx.room.TypeConverters

/**
 * A single capture event — something the user grabbed from the outside world
 * (screenshot, share text, clipboard, camera, etc.) that has not yet been promoted
 * to a full domain entity.
 *
 * The capture pipeline:
 *   NEW → PROCESSING → NEEDS_REVIEW → CONFIRMED
 *                         ↓
 *                      FAILED / DISMISSED
 *
 * This patch only creates the model + repository layer. The existing QuickCaptureService is NOT
 * migrated.
 */
@TypeConverters(CaptureSourceConverter::class, CaptureStatusConverter::class)
@Entity(
    tableName = "capture_items",
    indices = [
        Index(value = ["source"]),
        Index(value = ["status"]),
        Index(value = ["createdAt"]),
        Index(value = ["status", "createdAt"]),
    ]
)
data class CaptureItemEntity(

    @PrimaryKey
    val id: String,

    @ColumnInfo(name = "source")
    val source: CaptureSource,

    @ColumnInfo(name = "status")
    val status: CaptureStatus,

    @ColumnInfo(name = "rawText")
    val rawText: String? = null,

    @ColumnInfo(name = "sourceUrl")
    val sourceUrl: String? = null,

    /**
     * A title the user typed for this capture. Null until they do.
     *
     * Added in v0.3.0 as an explicit column rather than a key inside a JSON blob: the record screen
     * lets the user edit a capture's title directly, and a title buried in serialised JSON could
     * not be indexed, searched or migrated by SQL. A capture may legitimately have no title (a
     * screenshot usually does not), so this stays nullable.
     */
    @ColumnInfo(name = "displayTitle")
    val displayTitle: String? = null,

    /**
     * The user's own note about this capture — why it mattered, what to do with it.
     *
     * Separate from [rawText] by design: [rawText] is what was *captured* (and is kept verbatim as
     * history), while this is what the user *added*. Editing one must never silently rewrite the
     * other.
     */
    @ColumnInfo(name = "note")
    val note: String? = null,

    /**
     * Optional media reference. Nullable by design: a capture may carry only text.
     *
     * v0.1 declares no physical foreign key on this column. Reference validity is guaranteed by
     * the repository layer, and no cascade delete can ever erase capture history.
     */
    @ColumnInfo(name = "primaryMediaAssetId")
    val primaryMediaAssetId: String? = null,

    @ColumnInfo(name = "createdAt")
    val createdAt: Long,

    @ColumnInfo(name = "updatedAt")
    val updatedAt: Long,

    @ColumnInfo(name = "errorMessage")
    val errorMessage: String? = null,
)

enum class CaptureSource {
    SCREENSHOT,
    SHARE,
    CLIPBOARD,
    CAMERA,
    GALLERY,
    FLOATING_BALL,
    NOTIFICATION,
    MANUAL,
}

enum class CaptureStatus {
    NEW,
    PROCESSING,
    NEEDS_REVIEW,
    CONFIRMED,
    FAILED,
    DISMISSED,
}

class CaptureSourceConverter {
    @TypeConverter
    fun toValue(source: CaptureSource): String = source.name

    @TypeConverter
    fun fromValue(value: String): CaptureSource = runCatching { CaptureSource.valueOf(value) }
        .getOrDefault(CaptureSource.MANUAL)
}

class CaptureStatusConverter {
    @TypeConverter
    fun toValue(status: CaptureStatus): String = status.name

    @TypeConverter
    fun fromValue(value: String): CaptureStatus = runCatching { CaptureStatus.valueOf(value) }
        .getOrDefault(CaptureStatus.NEW)
}
