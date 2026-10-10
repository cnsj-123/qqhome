package com.qq.closie.life.finance

import androidx.room.*

enum class FinanceNature(val label: String) {
    PERSONAL("个人"), SPLIT("分账"), PROXY_PURCHASE("代拍"), REIMBURSABLE("报销"), STORED_VALUE("储值"), OTHER("其他")
}
enum class FinanceFlowRole(val label: String) {
    PAYMENT("付款"), REFUND("退款"), REIMBURSEMENT("报销到账"), SETTLEMENT("结算"),
    REBATE("返利"), CASHBACK("返现"), FEE("手续费"), OTHER("其他")
}
enum class FinanceStatPolicy { INCLUDE, EXCLUDE }
enum class FinanceBudgetPolicy { INCLUDE, EXCLUDE }

@Entity(tableName = "finance_events", indices = [Index("occurredAt"), Index("voidedAt")])
data class FinanceEventEntity(
    @PrimaryKey val id: String, val description: String, val nature: FinanceNature,
    val currencyCode: String, val occurredAt: Long, val createdAt: Long, val updatedAt: Long,
    /** Confirmed total personal share; null means not yet classified, never guessed as zero. */
    val personalShareMinor: Long? = null, val voidedAt: Long? = null,
    val relatedTransferId: String? = null
)

@Entity(tableName = "finance_event_entries", foreignKeys = [
    ForeignKey(entity = FinanceEventEntity::class, parentColumns = ["id"], childColumns = ["eventId"]),
    ForeignKey(entity = FinanceEntryEntity::class, parentColumns = ["id"], childColumns = ["entryId"])
], indices = [Index("entryId"), Index("eventId")])
data class FinanceEventEntryLinkEntity(val eventId: String, val entryId: String,
    val role: FinanceFlowRole, val allocatedMinor: Long,
    @PrimaryKey val id: String = java.util.UUID.randomUUID().toString(),
    val createdAt: Long = System.currentTimeMillis(), val updatedAt: Long = createdAt,
    val voidedAt: Long? = null, val revision: Long = 1)

@Entity(tableName = "finance_expected_flows", foreignKeys = [
    ForeignKey(entity = FinanceEventEntity::class, parentColumns = ["id"], childColumns = ["eventId"])
], indices = [Index("eventId")])
data class FinanceExpectedFlowEntity(
    @PrimaryKey val id: String, val eventId: String, val role: FinanceFlowRole,
    val amountMinor: Long?, val note: String, val createdAt: Long, val cancelledAt: Long? = null
)

/** One real entry may allocate to several events; amounts must exactly partition that entry. */
data class FinanceAllocation(val eventId: String, val role: FinanceFlowRole, val amountMinor: Long)
data class FinanceExpectedFlowInput(val id: String? = null, val role: FinanceFlowRole,
    val amountMinor: Long? = null, val note: String = "", val cancelled: Boolean = false)
data class FinanceEventInput(
    val nature: FinanceNature = FinanceNature.PERSONAL, val personalShareMinor: Long? = null,
    val role: FinanceFlowRole = FinanceFlowRole.PAYMENT, val existingEventId: String? = null,
    /** null preserves existing expectations; a list explicitly edits the active set. */
    val expectedFlows: List<FinanceExpectedFlowInput>? = null,
    val statPolicy: FinanceStatPolicy = FinanceStatPolicy.INCLUDE,
    val budgetPolicy: FinanceBudgetPolicy = FinanceBudgetPolicy.INCLUDE,
    val relatedTransferId: String? = null,
    val expectedEventUpdatedAt: Long? = null
)
