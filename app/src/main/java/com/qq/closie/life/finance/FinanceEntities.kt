package com.qq.closie.life.finance

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverter

/** Finance owns real account flows. These are not final personal consumption or product cost. */
enum class FinanceAccountKind(val label: String) {
    BANK_CARD("银行卡"), WECHAT("微信"), ALIPAY("支付宝"), CASH("现金"), OTHER("其他")
}
enum class FinanceDirection { INFLOW, OUTFLOW }

@Entity(tableName = "finance_accounts")
data class FinanceAccountEntity(
    @PrimaryKey val id: String,
    val name: String,
    val kind: FinanceAccountKind,
    val currencyCode: String,
    val openingBalanceMinor: Long,
    /** Immutable F1 opening-balance anchor, also the canonical account creation time. */
    val createdAt: Long,
    val updatedAt: Long,
    val archivedAt: Long? = null
)

@Entity(tableName = "finance_categories", indices = [Index(value = ["name"])])
data class FinanceCategoryEntity(@PrimaryKey val id: String, val name: String)

@Entity(tableName = "finance_tags", indices = [Index(value = ["name"])])
data class FinanceTagEntity(@PrimaryKey val id: String, val name: String)

@Entity(tableName = "finance_entries", foreignKeys = [
    ForeignKey(entity = FinanceAccountEntity::class, parentColumns = ["id"], childColumns = ["accountId"]),
    ForeignKey(entity = FinanceCategoryEntity::class, parentColumns = ["id"], childColumns = ["categoryId"])
], indices = [Index("accountId"), Index("categoryId"), Index("occurredAt"), Index("voidedAt")])
data class FinanceEntryEntity(
    @PrimaryKey val id: String,
    val accountId: String,
    val direction: FinanceDirection,
    val amountMinor: Long,
    val description: String,
    val occurredAt: Long,
    val recordedAt: Long,
    val updatedAt: Long,
    val categoryId: String? = null,
    val voidedAt: Long? = null
)

@Entity(tableName = "finance_transfers", foreignKeys = [
    ForeignKey(entity = FinanceEntryEntity::class, parentColumns = ["id"], childColumns = ["outflowEntryId"]),
    ForeignKey(entity = FinanceEntryEntity::class, parentColumns = ["id"], childColumns = ["inflowEntryId"])
], indices = [Index(value = ["outflowEntryId"], unique = true), Index(value = ["inflowEntryId"], unique = true)])
data class FinanceTransferEntity(
    @PrimaryKey val id: String,
    val outflowEntryId: String,
    val inflowEntryId: String,
    val recordedAt: Long,
    val updatedAt: Long,
    val voidedAt: Long? = null
)

@Entity(tableName = "finance_entry_tags", primaryKeys = ["entryId", "tagId"], foreignKeys = [
    ForeignKey(entity = FinanceEntryEntity::class, parentColumns = ["id"], childColumns = ["entryId"]),
    ForeignKey(entity = FinanceTagEntity::class, parentColumns = ["id"], childColumns = ["tagId"])
], indices = [Index("tagId")])
data class FinanceEntryTagCrossRef(val entryId: String, val tagId: String)

class FinanceConverters {
    @TypeConverter fun kind(value: String): FinanceAccountKind = FinanceAccountKind.valueOf(value)
    @TypeConverter fun kind(value: FinanceAccountKind): String = value.name
    @TypeConverter fun direction(value: String): FinanceDirection = FinanceDirection.valueOf(value)
    @TypeConverter fun direction(value: FinanceDirection): String = value.name
}
