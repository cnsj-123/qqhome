package com.qq.closie.life.reference

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverter

/**
 * A piece of saved information the user actually wants to keep — a tutorial screenshot, a bookmarked
 * article, a pasted guide, a note to self.
 *
 * Why this is NOT [com.qq.closie.life.capture.CaptureItemEntity]:
 * a capture is *history* — an immutable event ("I grabbed this at 08:31"). A reference is *content*
 * — something the user has curated, titled, summarised, tagged and will come back to. Squeezing both
 * into one table would turn the capture inbox into a catch-all business table, which this project
 * explicitly refuses to do (see the domain rules in §49 of the v0.3.0 spec).
 *
 * The pipeline is therefore:
 *
 *   screenshot / link / text        ← source
 *        ↓
 *   CaptureItem (raw, immutable)    ← inbox, never edited into "the real thing"
 *        ↓  user organises
 *   ReferenceItem (curated)         ← this entity, carries lifeEntityId
 *
 * Every reference is a real LifeEntity: [lifeEntityId] points at its
 * [com.qq.closie.life.core.LifeEntityEntity] row with entityType
 * [ReferenceEntityType.REFERENCE]. That is what makes it a first-class citizen of the life graph —
 * it can be tagged, related to a trip or a plant, and carry media, all through the existing
 * LifeRelation / Tag / MediaLink infrastructure without a single new join table.
 */
@Entity(
    tableName = "reference_items",
    indices = [
        // UNIQUE, not merely indexed. The relationship between a reference and its LifeEntity is
        // 1:1 — [create] writes exactly one LifeEntity per reference — and the only reason to keep
        // the index at all is to enforce that. A non-unique index would let a second reference be
        // pointed at an entity another reference already owns, producing two rows that are "the
        // same thing" to every tag, media link and relation lookup. That corrupts the life graph
        // silently: no query errors, the wrong row just shows the wrong tags.
        Index(value = ["lifeEntityId"], unique = true),
        Index(value = ["status"]),
        Index(value = ["referenceType"]),
        Index(value = ["createdAt"]),
        Index(value = ["status", "createdAt"]),
        Index(value = ["status", "updatedAt"]),
        // UNIQUE, not merely indexed. Product rule: one capture yields at most one organised
        // reference. A plain index left the importer's find-then-create sequence open to a race —
        // two coroutines both see `find == null`, both `create`, and the user gets two references
        // for one capture, each needing separate tagging and deletion with no way to tell which is
        // which. UNIQUE makes the database enforce the rule rather than trusting call ordering.
        //
        // Multiple NULLs are permitted by SQLite, so hand-written references (which have no source
        // capture) are unaffected and can be as numerous as the user likes.
        Index(value = ["originalCaptureId"], unique = true),
        Index(value = ["sourceUrl"]),
    ]
)
data class ReferenceItemEntity(

    @PrimaryKey
    val id: String,

    /**
     * The LifeEntity this reference *is*. Not nullable: an orphaned reference would be invisible to
     * the life graph, which is the one property that makes it useful.
     */
    @ColumnInfo(name = "lifeEntityId")
    val lifeEntityId: String,

    @ColumnInfo(name = "title")
    val title: String,

    @ColumnInfo(name = "referenceType")
    val referenceType: ReferenceType,

    /** One or two lines the user (or OCR) wrote to remember why this was worth keeping. */
    @ColumnInfo(name = "summary")
    val summary: String? = null,

    /**
     * Full extracted text — OCR output for screenshots, article body for pages.
     *
     * Kept separate from [summary] on purpose: a summary is what is *shown*, this is what is
     * *searched*. v0.3 stores ML Kit's flat `visionText.text`; the column exists so a future
     * structured-OCR pass (blocks / lines / bounding boxes) can be added without a migration of
     * the searchable surface.
     */
    @ColumnInfo(name = "ocrText")
    val ocrText: String? = null,

    @ColumnInfo(name = "sourceUrl")
    val sourceUrl: String? = null,

    /** Human-readable origin: a site name (少数派), a platform (淘宝), or 相册. */
    @ColumnInfo(name = "sourceName")
    val sourceName: String? = null,

    @ColumnInfo(name = "author")
    val author: String? = null,

    /**
     * The capture this reference was distilled from, when there was one.
     *
     * No physical foreign key, matching [com.qq.closie.life.capture.CaptureItemEntity]: a reference
     * must survive its capture being cleaned up, and a capture's history must never cascade away
     * the curated content a user built on top of it. Validity is enforced by
     * [com.qq.closie.life.repository.ReferenceRepository.create], which stores null rather than a
     * dangling id when the capture cannot be found.
     */
    @ColumnInfo(name = "originalCaptureId")
    val originalCaptureId: String? = null,

    @ColumnInfo(name = "status")
    val status: ReferenceStatus = ReferenceStatus.INBOX,

    @ColumnInfo(name = "createdAt")
    val createdAt: Long,

    @ColumnInfo(name = "updatedAt")
    val updatedAt: Long,

    /** When the user moved this out of the inbox. Null while still [ReferenceStatus.INBOX]. */
    @ColumnInfo(name = "organizedAt")
    val organizedAt: Long? = null,
)

/**
 * What kind of thing this is.
 *
 * [READING] and [ARTICLE] are the two the 阅读 module filters on — see
 * [com.qq.closie.life.repository.ReferenceRepository.observeReading]. Everything else is general
 * reference material.
 */
enum class ReferenceType {
    ARTICLE,
    TUTORIAL,
    GUIDE,
    NOTE,
    REFERENCE,
    READING,
    PRODUCT_INFO,
    OTHER,
}

/**
 * Where the item sits in the user's own workflow.
 *
 * Three states only. A richer status machine would imply the app is managing the user's reading
 * habits, which it is not — Life OS stores, it does not supervise.
 */
enum class ReferenceStatus {
    /** Just saved, not yet titled/summarised by the user. */
    INBOX,

    /** The user has reviewed it: title and summary are theirs. */
    ORGANIZED,

    /** Kept for the record, out of the way. */
    ARCHIVED,
}

/** Room converter for [ReferenceType]. Never store this as a free-form String. */
class ReferenceTypeConverter {
    @TypeConverter
    fun toValue(type: ReferenceType): String = type.name

    @TypeConverter
    fun fromValue(value: String): ReferenceType =
        runCatching { ReferenceType.valueOf(value) }.getOrDefault(ReferenceType.OTHER)
}

/** Room converter for [ReferenceStatus]. */
class ReferenceStatusConverter {
    @TypeConverter
    fun toValue(status: ReferenceStatus): String = status.name

    @TypeConverter
    fun fromValue(value: String): ReferenceStatus =
        runCatching { ReferenceStatus.valueOf(value) }.getOrDefault(ReferenceStatus.INBOX)
}

/**
 * entityType value used on the [com.qq.closie.life.core.LifeEntityEntity] row that backs a
 * reference. Declared here so the repository, tests and any future sync layer cannot drift apart
 * on a magic string.
 */
object ReferenceEntityType {
    const val REFERENCE = "REFERENCE"
}
