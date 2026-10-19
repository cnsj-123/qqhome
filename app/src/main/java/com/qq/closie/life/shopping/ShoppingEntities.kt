package com.qq.closie.life.shopping

import androidx.room.*

@Entity(tableName = "shopping_purchases", primaryKeys = ["id"])
data class ShoppingPurchaseEntity(
    val id: String,
    val title: String,
    val currencyCode: String,
    val occurredAt: Long,
    val createdAt: Long,
    val updatedAt: Long,
    val revision: Long = 1,
    val voidedAt: Long? = null
)

@Entity(tableName = "shopping_purchase_lines", primaryKeys = ["id"], indices = [Index("purchaseId")], foreignKeys = [ForeignKey(entity = ShoppingPurchaseEntity::class, parentColumns = ["id"], childColumns = ["purchaseId"])])
data class ShoppingPurchaseLineEntity(
    val id: String,
    val purchaseId: String,
    val title: String,
    val quantity: Int,
    val valueMinor: Long? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val revision: Long = 1,
    val voidedAt: Long? = null
)
