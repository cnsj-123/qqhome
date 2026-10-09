package com.qq.closie.life.core

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Ordinary-world identity metadata for registered typed domain objects.
 * This registry is not a universal business store and does not enroll future secure Vault content.
 *
 * Business-specific fields belong in dedicated typed entities that reference this row via [id].
 * Do NOT stuff arbitrary JSON into a single column — LifeEntity only carries identity, revision,
 * soft-delete and future sync metadata.
 *
 * [revision] is monotonic: it starts at 1 on creation and is incremented on every meaningful update,
 * soft delete and restore. It must never be 0.
 */
@Entity(
    tableName = "life_entities",
    indices = [
        Index(value = ["entityType"]),
        Index(value = ["updatedAt"]),
        Index(value = ["deletedAt"]),
        Index(value = ["entityType", "deletedAt"]),
    ]
)
data class LifeEntityEntity(

    @PrimaryKey
    val id: String,

    @ColumnInfo(name = "entityType")
    val entityType: String,

    @ColumnInfo(name = "createdAt")
    val createdAt: Long,

    @ColumnInfo(name = "updatedAt")
    val updatedAt: Long,

    @ColumnInfo(name = "deletedAt")
    val deletedAt: Long? = null,

    @ColumnInfo(name = "revision")
    val revision: Long = 1L,
)
