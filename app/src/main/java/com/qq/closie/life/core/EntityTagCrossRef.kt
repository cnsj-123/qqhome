package com.qq.closie.life.core

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * Many-to-many cross-reference between [LifeEntityEntity] and [TagEntity].
 *
 * Composite primary key (entityId, tagId) prevents duplicate tag attachments.
 */
@Entity(
    tableName = "entity_tag_cross_ref",
    primaryKeys = ["entityId", "tagId"],
    foreignKeys = [
        ForeignKey(
            entity = LifeEntityEntity::class,
            parentColumns = ["id"],
            childColumns = ["entityId"],
            onDelete = ForeignKey.NO_ACTION,
        ),
        ForeignKey(
            entity = TagEntity::class,
            parentColumns = ["id"],
            childColumns = ["tagId"],
            onDelete = ForeignKey.NO_ACTION,
        ),
    ],
    indices = [
        Index(value = ["entityId"]),
        Index(value = ["tagId"]),
    ]
)
data class EntityTagCrossRef(

    @ColumnInfo(name = "entityId")
    val entityId: String,

    @ColumnInfo(name = "tagId")
    val tagId: String,
)
