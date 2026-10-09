package com.qq.closie.life.plan

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Current typed Plans capability: a user intention with an optional soft planned date.
 * [dueAt] is the legacy column name for that date, NOT a hard deadline or an Event.
 * This is not the final Plans model: Goal/Project/Task trees, waiting/paused state,
 * hard deadlines and recurrence belong to future typed Plans extensions.
 * Business fields remain here; LifeEntity is only shared metadata/index support.
 */
@Entity(
    tableName = "plan_items",
    indices = [
        // UNIQUE for the same reason as [com.qq.closie.life.reference.ReferenceItemEntity]: a plan
        // and its LifeEntity are 1:1, and a duplicate link would make tag/relation queries return
        // another plan's data. See that entity for the full rationale.
        Index(value = ["lifeEntityId"], unique = true),
        Index(value = ["completedAt"]),
        Index(value = ["dueAt"]),
        Index(value = ["dueAt", "completedAt"]),
        Index(value = ["createdAt"]),
    ]
)
data class PlanItemEntity(

    @PrimaryKey
    val id: String,

    @ColumnInfo(name = "lifeEntityId")
    val lifeEntityId: String,

    @ColumnInfo(name = "title")
    val title: String,

    @ColumnInfo(name = "note")
    val note: String? = null,

    /**
     * Null means "no particular day". Such a plan groups under 稍后 rather than being hidden —
     * an undated intention is still an intention.
     */
    @ColumnInfo(name = "dueAt")
    val dueAt: Long? = null,

    /**
     * Completion is a timestamp, not a boolean: "when did I get to this" is a question worth being
     * able to answer, and a nullable Long answers both it and "is it done" in one column.
     */
    @ColumnInfo(name = "completedAt")
    val completedAt: Long? = null,

    @ColumnInfo(name = "createdAt")
    val createdAt: Long,

    @ColumnInfo(name = "updatedAt")
    val updatedAt: Long,

    /** Manual ordering within 今天 / 接下来. Ties fall back to createdAt. */
    @ColumnInfo(name = "sortOrder")
    val sortOrder: Int = 0,
)

/** entityType value for the LifeEntity backing a plan. */
object PlanEntityType {
    const val PLAN = "PLAN"
}
