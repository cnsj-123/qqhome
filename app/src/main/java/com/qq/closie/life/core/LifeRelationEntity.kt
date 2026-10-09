package com.qq.closie.life.core

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Directed edge between two [LifeEntityEntity] rows.
 *
 * Examples: outfit → wardrobe_item ("contains"), capture → outfit ("produced"),
 *           wardrobe_item → media_asset ("has_image").
 *
 * A unique index on (fromEntityId, toEntityId, relationType) prevents duplicate edges.
 * No physical cascade delete — Life OS uses soft delete as the primary deletion strategy.
 */
@Entity(
    tableName = "life_relations",
    foreignKeys = [
        ForeignKey(
            entity = LifeEntityEntity::class,
            parentColumns = ["id"],
            childColumns = ["fromEntityId"],
            onDelete = ForeignKey.NO_ACTION,
        ),
        ForeignKey(
            entity = LifeEntityEntity::class,
            parentColumns = ["id"],
            childColumns = ["toEntityId"],
            onDelete = ForeignKey.NO_ACTION,
        ),
    ],
    indices = [
        Index(value = ["fromEntityId"]),
        Index(value = ["toEntityId"]),
        Index(value = ["relationType"]),
        Index(value = ["fromEntityId", "relationType"]),
        Index(value = ["toEntityId", "relationType"]),
        Index(value = ["fromEntityId", "toEntityId", "relationType"], unique = true),
    ]
)
data class LifeRelationEntity(

    @PrimaryKey
    val id: String,

    @ColumnInfo(name = "fromEntityId")
    val fromEntityId: String,

    @ColumnInfo(name = "toEntityId")
    val toEntityId: String,

    @ColumnInfo(name = "relationType")
    val relationType: String,

    @ColumnInfo(name = "createdAt")
    val createdAt: Long,
)
