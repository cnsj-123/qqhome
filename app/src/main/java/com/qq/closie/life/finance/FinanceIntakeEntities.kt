package com.qq.closie.life.finance

import androidx.room.*

enum class FinanceProposalStatus { ACTIVE, SNOOZED, CONFIRMED, REJECTED, STALE }
enum class FinanceSource { LEGACY_IMPORT, WECHAT, ALIPAY }
enum class FinanceProposalKind { EXPENSE, INCOME, TRANSFER }
enum class FinanceImportStatus { STAGED, COMMITTED, UNDONE }
enum class FinanceRowStatus { READY, REVIEW, INVALID, DUPLICATE, COMMITTED, UNDONE }
enum class FinanceConditionKind { SOURCE, TEXT_CONTAINS, DIRECTION, AMOUNT_MIN, AMOUNT_MAX, ACCOUNT }
enum class FinanceActionKind { SUGGEST_ACCOUNT, SUGGEST_CATEGORY, ADD_TAG, NORMALIZE_DESCRIPTION, SUGGEST_BUSINESS_NATURE }

@Entity(tableName = "finance_import_batches", indices = [Index(value = ["digest"], unique = true)])
data class FinanceImportBatchEntity(
    @PrimaryKey val id: String, val digest: String, val fileName: String, val createdAt: Long,
    val status: FinanceImportStatus = FinanceImportStatus.STAGED, val committedAt: Long? = null
)

/** Scoped source evidence only. No canonical queries may interpret rawPayload. */
@Entity(tableName = "finance_import_rows", foreignKeys = [
    ForeignKey(entity = FinanceImportBatchEntity::class, parentColumns = ["id"], childColumns = ["batchId"])
], indices = [Index("batchId"), Index("fingerprint")])
data class FinanceImportRowEntity(
    @PrimaryKey val id: String, val batchId: String, val rowNumber: Int, val fingerprint: String,
    val rawPayload: String, val status: FinanceRowStatus, val issue: String,
    val sourceAccount: String, val targetAccount: String, val kind: FinanceProposalKind?,
    val amountMinor: Long?, val occurredAt: Long?, val description: String,
    val category: String, val subcategory: String, val tagsText: String,
    val feeMinor: Long, val reimbursable: Boolean, val hasRefund: Boolean,
    val statPolicy: FinanceStatPolicy, val budgetPolicy: FinanceBudgetPolicy,
    val canonicalId: String? = null, val requiresIndividualReview: Boolean = false
)

@Entity(tableName = "finance_proposals", indices = [
    Index(value = ["sourceKey"], unique = true), Index("status"), Index("createdAt")
])
data class FinanceProposalEntity(
    @PrimaryKey val id: String, val source: FinanceSource, val sourceKey: String,
    val evidenceText: String, val createdAt: Long, val occurredAt: Long,
    val kind: FinanceProposalKind, val amountMinor: Long?, val description: String,
    val accountId: String? = null, val targetAccountId: String? = null,
    val category: String = "", val subcategory: String = "",
    val nature: FinanceNature = FinanceNature.PERSONAL,
    val status: FinanceProposalStatus = FinanceProposalStatus.ACTIVE,
    val canonicalId: String? = null, val revision: Long = 0,
    val role: FinanceFlowRole = if (kind == FinanceProposalKind.INCOME) FinanceFlowRole.OTHER else FinanceFlowRole.PAYMENT
)

@Entity(tableName = "finance_proposal_tags", primaryKeys = ["proposalId", "name"], foreignKeys = [
    ForeignKey(entity = FinanceProposalEntity::class, parentColumns = ["id"], childColumns = ["proposalId"])
])
data class FinanceProposalTagEntity(val proposalId: String, val name: String)

@Entity(tableName = "finance_rules")
data class FinanceRuleEntity(@PrimaryKey val id: String, val name: String, val priority: Int,
    val enabled: Boolean, val updatedAt: Long, val deletedAt: Long? = null)

@Entity(tableName = "finance_rule_conditions", primaryKeys = ["ruleId", "position"], foreignKeys = [
    ForeignKey(entity = FinanceRuleEntity::class, parentColumns = ["id"], childColumns = ["ruleId"])
])
data class FinanceRuleConditionEntity(val ruleId: String, val position: Int, val kind: FinanceConditionKind, val value: String)

@Entity(tableName = "finance_rule_actions", primaryKeys = ["ruleId", "position"], foreignKeys = [
    ForeignKey(entity = FinanceRuleEntity::class, parentColumns = ["id"], childColumns = ["ruleId"])
])
data class FinanceRuleActionEntity(val ruleId: String, val position: Int, val kind: FinanceActionKind, val value: String)

/** Canonical mutation journal / durable post-commit signal, not a second business store. */
@Entity(tableName = "finance_changes", indices = [Index("objectId"), Index("batchId")])
data class FinanceChangeEntity(@PrimaryKey val id: String, val objectId: String, val operation: String,
    val authority: String, val createdAt: Long, val batchId: String? = null,
    val beforeRevision: Long? = null, val afterRevision: Long? = null)

/** Kept separate and nullable in old archives: absence never means erase pending user work. */
data class FinanceV4Snapshot(
    val events: List<FinanceEventEntity> = emptyList(),
    val links: List<FinanceEventEntryLinkEntity> = emptyList(),
    val expected: List<FinanceExpectedFlowEntity> = emptyList(),
    val batches: List<FinanceImportBatchEntity> = emptyList(),
    val staging: List<FinanceImportRowEntity> = emptyList(),
    val proposals: List<FinanceProposalEntity> = emptyList(),
    val proposalTags: List<FinanceProposalTagEntity> = emptyList(),
    val rules: List<FinanceRuleEntity> = emptyList(),
    val conditions: List<FinanceRuleConditionEntity> = emptyList(),
    val actions: List<FinanceRuleActionEntity> = emptyList(),
    val changes: List<FinanceChangeEntity> = emptyList()
)

/** Canonical readers use active links; archives retain the complete list. */
val FinanceV4Snapshot.activeLinks get() = links.filter { it.voidedAt == null }
