package com.qq.closie.life.core

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A normalized tag that can be attached to any [LifeEntityEntity].
 *
 * [normalizedName] is the canonical key used for deduplication and lookup — it is lower-cased
 * and trimmed before insertion and is unique across the table.
 */
@Entity(
    tableName = "tags",
    indices = [
        Index(value = ["normalizedName"], unique = true),
    ]
)
data class TagEntity(

    @PrimaryKey
    val id: String,

    @ColumnInfo(name = "name")
    val name: String,

    @ColumnInfo(name = "normalizedName")
    val normalizedName: String,

    @ColumnInfo(name = "createdAt")
    val createdAt: Long,
)
