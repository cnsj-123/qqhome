package com.qq.closie.life.finance

import androidx.room.*
import com.qq.closie.life.shopping.*

enum class FinanceShareRelation { PERSONAL_CONFIRMED, GIFT_CONFIRMED, REPAYABLE_CONFIRMED, UNRESOLVED }
enum class FinanceResolutionKind { HISTORICAL_SETTLED, WAIVED }
enum class FinanceTimePrecision { UNKNOWN, DATE, INSTANT, LEGACY }
enum class FinanceLiabilityConvention { SIGNED, POSITIVE_DEBT }
enum class FinanceBalanceBasis { LEGACY_CASH, KNOWN_POSTED }
enum class FinanceBurden { FIXED, FLEXIBLE }
enum class FinanceReferenceKind { NONE, ORDER, PAYMENT, REFUND }
enum class FinanceReviewState { QUEUED, EDITING, LATER, COMPLETED }
enum class FinanceDraftKind { EXPENSE, INCOME, TRANSFER }
enum class FinanceReadPurpose { HOME, SHARE, LOCAL_QUERY, AI_READ, CLOUD_SEND }
enum class FinanceRecoveryMemberKind { ECONOMIC, FUNDING, SHARE, OBLIGATION, SETTLEMENT, RESOLUTION, COST_ADJUSTMENT, PURCHASE_ALLOCATION }

@Entity(tableName = "finance_parties", primaryKeys = ["id"])
data class FinancePartyEntity(
    val id: String,
    val name: String,
    val isSelf: Boolean = false,
    val createdAt: Long,
    val updatedAt: Long,
    val revision: Long = 1,
    val voidedAt: Long? = null
)

@Entity(tableName = "finance_economics", primaryKeys = ["eventId"], indices = [Index("eventId"), Index("categoryId")], foreignKeys = [ForeignKey(entity = FinanceEventEntity::class, parentColumns = ["id"], childColumns = ["eventId"]), ForeignKey(entity = FinanceCategoryEntity::class, parentColumns = ["id"], childColumns = ["categoryId"])])
data class FinanceEconomicEntity(
    val eventId: String,
    val valueMinor: Long? = null,
    val valueBasis: String,
    val categoryId: String? = null,
    val allocationsComplete: Boolean = false,
    val statPolicy: FinanceStatPolicy = FinanceStatPolicy.INCLUDE,
    val budgetPolicy: FinanceBudgetPolicy = FinanceBudgetPolicy.INCLUDE,
    val createdAt: Long,
    val updatedAt: Long,
    val revision: Long = 1,
    val voidedAt: Long? = null
)

@Entity(tableName = "finance_funding", primaryKeys = ["id"], indices = [Index("eventId"), Index("partyId"), Index("entryLinkId")], foreignKeys = [ForeignKey(entity = FinanceEventEntity::class, parentColumns = ["id"], childColumns = ["eventId"]), ForeignKey(entity = FinancePartyEntity::class, parentColumns = ["id"], childColumns = ["partyId"]), ForeignKey(entity = FinanceEventEntryLinkEntity::class, parentColumns = ["id"], childColumns = ["entryLinkId"])])
data class FinanceFundingEntity(
    val id: String,
    val eventId: String,
    val partyId: String,
    val amountMinor: Long,
    val entryLinkId: String? = null,
    val basis: String,
    val createdAt: Long,
    val updatedAt: Long,
    val revision: Long = 1,
    val voidedAt: Long? = null
)

@Entity(tableName = "finance_shares", primaryKeys = ["id"], indices = [Index("eventId"), Index("beneficiaryId"), Index("bearerId"), Index("fundingId")], foreignKeys = [ForeignKey(entity = FinanceEventEntity::class, parentColumns = ["id"], childColumns = ["eventId"]), ForeignKey(entity = FinancePartyEntity::class, parentColumns = ["id"], childColumns = ["beneficiaryId"]), ForeignKey(entity = FinancePartyEntity::class, parentColumns = ["id"], childColumns = ["bearerId"]), ForeignKey(entity = FinanceFundingEntity::class, parentColumns = ["id"], childColumns = ["fundingId"])])
data class FinanceShareEntity(
    val id: String,
    val eventId: String,
    val amountMinor: Long? = null,
    val beneficiaryId: String,
    val bearerId: String? = null,
    val fundingId: String? = null,
    val relation: FinanceShareRelation,
    val createdAt: Long,
    val updatedAt: Long,
    val revision: Long = 1,
    val voidedAt: Long? = null
)

@Entity(tableName = "finance_obligations", primaryKeys = ["id"], indices = [Index("eventId"), Index("shareId"), Index("creditorId"), Index("debtorId")], foreignKeys = [ForeignKey(entity = FinanceEventEntity::class, parentColumns = ["id"], childColumns = ["eventId"]), ForeignKey(entity = FinanceShareEntity::class, parentColumns = ["id"], childColumns = ["shareId"]), ForeignKey(entity = FinancePartyEntity::class, parentColumns = ["id"], childColumns = ["creditorId"]), ForeignKey(entity = FinancePartyEntity::class, parentColumns = ["id"], childColumns = ["debtorId"])])
data class FinanceObligationEntity(
    val id: String,
    val eventId: String,
    val shareId: String? = null,
    val creditorId: String,
    val debtorId: String,
    val amountMinor: Long,
    val currencyCode: String,
    val createdAt: Long,
    val updatedAt: Long,
    val revision: Long = 1,
    val voidedAt: Long? = null
)

@Entity(tableName = "finance_settlements", primaryKeys = ["id"], indices = [Index("obligationId"), Index("entryId")], foreignKeys = [ForeignKey(entity = FinanceObligationEntity::class, parentColumns = ["id"], childColumns = ["obligationId"]), ForeignKey(entity = FinanceEntryEntity::class, parentColumns = ["id"], childColumns = ["entryId"])])
data class FinanceSettlementEntity(
    val id: String,
    val obligationId: String,
    val entryId: String,
    val amountMinor: Long,
    val createdAt: Long,
    val updatedAt: Long,
    val revision: Long = 1,
    val voidedAt: Long? = null
)

@Entity(tableName = "finance_resolutions", primaryKeys = ["id"], indices = [Index("obligationId")], foreignKeys = [ForeignKey(entity = FinanceObligationEntity::class, parentColumns = ["id"], childColumns = ["obligationId"])])
data class FinanceResolutionEntity(
    val id: String,
    val obligationId: String,
    val kind: FinanceResolutionKind,
    val amountMinor: Long,
    val note: String,
    val createdAt: Long,
    val updatedAt: Long,
    val revision: Long = 1,
    val voidedAt: Long? = null
)

@Entity(tableName = "finance_purchase_allocations", primaryKeys = ["id"], indices = [Index("lineId"), Index("eventId")], foreignKeys = [ForeignKey(entity = ShoppingPurchaseLineEntity::class, parentColumns = ["id"], childColumns = ["lineId"]), ForeignKey(entity = FinanceEventEntity::class, parentColumns = ["id"], childColumns = ["eventId"])])
data class FinancePurchaseAllocationEntity(
    val id: String,
    val lineId: String,
    val eventId: String,
    val valueMinor: Long,
    val personalMinor: Long? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val revision: Long = 1,
    val voidedAt: Long? = null
)

@Entity(tableName = "finance_cost_adjustments", primaryKeys = ["id"], indices = [Index("eventId"), Index("entryLinkId"), Index("lineId")], foreignKeys = [ForeignKey(entity = FinanceEventEntity::class, parentColumns = ["id"], childColumns = ["eventId"]), ForeignKey(entity = FinanceEventEntryLinkEntity::class, parentColumns = ["id"], childColumns = ["entryLinkId"]), ForeignKey(entity = ShoppingPurchaseLineEntity::class, parentColumns = ["id"], childColumns = ["lineId"])])
data class FinanceCostAdjustmentEntity(
    val id: String,
    val eventId: String,
    val entryLinkId: String,
    val personalReductionMinor: Long,
    val lineId: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val revision: Long = 1,
    val voidedAt: Long? = null
)

@Entity(tableName = "finance_entry_timing", primaryKeys = ["entryId"], indices = [Index("entryId")], foreignKeys = [ForeignKey(entity = FinanceEntryEntity::class, parentColumns = ["id"], childColumns = ["entryId"])])
data class FinanceEntryTimingEntity(
    val entryId: String,
    val postedAt: Long? = null,
    val cashPrecision: FinanceTimePrecision = FinanceTimePrecision.LEGACY,
    val postedPrecision: FinanceTimePrecision = FinanceTimePrecision.UNKNOWN,
    val postedSource: String,
    val createdAt: Long,
    val updatedAt: Long,
    val revision: Long = 1,
    val voidedAt: Long? = null
)

@Entity(tableName = "finance_account_policies", primaryKeys = ["accountId"], indices = [Index("accountId")], foreignKeys = [ForeignKey(entity = FinanceAccountEntity::class, parentColumns = ["id"], childColumns = ["accountId"])])
data class FinanceAccountPolicyEntity(
    val accountId: String,
    val includedInNet: Boolean = true,
    val liabilityConvention: FinanceLiabilityConvention = FinanceLiabilityConvention.SIGNED,
    val anchorBasis: FinanceBalanceBasis = FinanceBalanceBasis.LEGACY_CASH,
    val createdAt: Long,
    val updatedAt: Long,
    val revision: Long = 1,
    val voidedAt: Long? = null
)

@Entity(tableName = "finance_observations", primaryKeys = ["id"], indices = [Index("accountId")], foreignKeys = [ForeignKey(entity = FinanceAccountEntity::class, parentColumns = ["id"], childColumns = ["accountId"])])
data class FinanceObservationEntity(
    val id: String,
    val accountId: String,
    val amountMinor: Long,
    val observedAt: Long,
    val basis: FinanceBalanceBasis,
    val precision: FinanceTimePrecision,
    val note: String,
    val createdAt: Long,
    val updatedAt: Long,
    val revision: Long = 1,
    val voidedAt: Long? = null
)

@Entity(tableName = "finance_reconciliations", primaryKeys = ["id"], indices = [Index("observationId")], foreignKeys = [ForeignKey(entity = FinanceObservationEntity::class, parentColumns = ["id"], childColumns = ["observationId"])])
data class FinanceReconciliationEntity(
    val id: String,
    val observationId: String,
    val bookMinor: Long? = null,
    val fingerprint: String,
    val algorithmVersion: Int = 1,
    val note: String,
    val confirmationOrder: Long = 1,
    val createdAt: Long,
    val updatedAt: Long,
    val revision: Long = 1,
    val voidedAt: Long? = null
)

@Entity(tableName = "finance_credit_cycles", primaryKeys = ["accountId"], indices = [Index("accountId")], foreignKeys = [ForeignKey(entity = FinanceAccountEntity::class, parentColumns = ["id"], childColumns = ["accountId"])])
data class FinanceCreditCycleEntity(
    val accountId: String,
    val closingDay: Int,
    val dueDay: Int,
    val zoneId: String,
    val createdAt: Long,
    val updatedAt: Long,
    val revision: Long = 1,
    val voidedAt: Long? = null
)

@Entity(tableName = "finance_drafts", primaryKeys = ["id"])
data class FinanceDraftEntity(
    val id: String,
    val accountId: String? = null,
    val kind: FinanceDraftKind = FinanceDraftKind.EXPENSE,
    val targetAccountId: String? = null,
    val amountExpression: String,
    val description: String,
    val occurredAt: Long,
    val occurredText: String,
    val categoryId: String? = null,
    val categoryName: String = "",
    val subcategoryName: String = "",
    val currencyCode: String,
    val continuous: Boolean = false,
    val epoch: Long,
    val editGeneration: Long = 0,
    val nature: FinanceNature = FinanceNature.PERSONAL,
    val role: FinanceFlowRole = FinanceFlowRole.PAYMENT,
    val eventId: String? = null,
    val shareText: String = "",
    val excluded: Boolean = false,
    val excludedBudget: Boolean = false,
    val expectedEdited: Boolean = false,
    val eventUpdatedAt: Long? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val revision: Long = 1,
    val voidedAt: Long? = null
)

@Entity(tableName = "finance_draft_tags", primaryKeys = ["draftId", "name"], indices = [Index("draftId")], foreignKeys = [ForeignKey(entity = FinanceDraftEntity::class, parentColumns = ["id"], childColumns = ["draftId"])])
data class FinanceDraftTagEntity(
    val draftId: String,
    val name: String
)

@Entity(tableName = "finance_draft_expected", primaryKeys = ["draftId", "position"], indices = [Index("draftId")], foreignKeys = [ForeignKey(entity = FinanceDraftEntity::class, parentColumns = ["id"], childColumns = ["draftId"])])
data class FinanceDraftExpectedEntity(
    val draftId: String,
    val position: Int,
    val expectedId: String? = null,
    val role: FinanceFlowRole,
    val amountText: String,
    val note: String,
    val cancelled: Boolean
)

@Entity(tableName = "finance_economic_drafts", primaryKeys = ["id"], indices = [Index("eventId"), Index("categoryId")], foreignKeys = [ForeignKey(entity = FinanceEventEntity::class, parentColumns = ["id"], childColumns = ["eventId"]), ForeignKey(entity = FinanceCategoryEntity::class, parentColumns = ["id"], childColumns = ["categoryId"])])
data class FinanceEconomicDraftEntity(
    val id: String,
    val eventId: String? = null,
    val expectedRevision: Long? = null,
    val eventVersion: Long? = null,
    val epoch: Long,
    val description: String,
    val currencyCode: String,
    val occurredText: String,
    val preservedOccurredAt: Long? = null,
    val valueText: String,
    val valueBasis: String,
    val categoryId: String? = null,
    val allocationsComplete: Boolean,
    val statExcluded: Boolean,
    val budgetExcluded: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
    val revision: Long = 1,
    val voidedAt: Long? = null
)

@Entity(tableName = "finance_economic_draft_funding", primaryKeys = ["draftId", "localId"], indices = [Index("draftId")], foreignKeys = [ForeignKey(entity = FinanceEconomicDraftEntity::class, parentColumns = ["id"], childColumns = ["draftId"])])
data class FinanceEconomicDraftFundingEntity(
    val draftId: String,
    val localId: String,
    val position: Int,
    val partySelection: String,
    val amountText: String,
    val entryLinkSelection: String? = null,
    val basis: String
)

@Entity(tableName = "finance_economic_draft_shares", primaryKeys = ["draftId", "localId"], indices = [Index("draftId")], foreignKeys = [ForeignKey(entity = FinanceEconomicDraftEntity::class, parentColumns = ["id"], childColumns = ["draftId"])])
data class FinanceEconomicDraftShareEntity(
    val draftId: String,
    val localId: String,
    val position: Int,
    val amountText: String,
    val beneficiarySelection: String,
    val bearerSelection: String,
    val fundingLocalId: String? = null,
    val relation: FinanceShareRelation
)

@Entity(tableName = "finance_category_icons", primaryKeys = ["categoryId"], indices = [Index("categoryId")], foreignKeys = [ForeignKey(entity = FinanceCategoryEntity::class, parentColumns = ["id"], childColumns = ["categoryId"])])
data class FinanceCategoryIconEntity(
    val categoryId: String,
    val iconKey: String,
    val createdAt: Long,
    val updatedAt: Long,
    val revision: Long = 1,
    val voidedAt: Long? = null
)

@Entity(tableName = "finance_saved_queries", primaryKeys = ["id"])
data class FinanceSavedQueryEntity(
    val id: String,
    val name: String,
    val specVersion: Int = 1,
    val specJson: String,
    val createdAt: Long,
    val updatedAt: Long,
    val revision: Long = 1,
    val voidedAt: Long? = null
)

@Entity(tableName = "finance_budgets", primaryKeys = ["id"], indices = [Index("categoryId")], foreignKeys = [ForeignKey(entity = FinanceCategoryEntity::class, parentColumns = ["id"], childColumns = ["categoryId"])])
data class FinanceBudgetEntity(
    val id: String,
    val name: String,
    val currencyCode: String,
    val limitMinor: Long,
    val categoryId: String? = null,
    val startAt: Long,
    val endExclusive: Long,
    val createdAt: Long,
    val updatedAt: Long,
    val revision: Long = 1,
    val voidedAt: Long? = null
)

@Entity(tableName = "finance_category_policies", primaryKeys = ["categoryId"], indices = [Index("categoryId")], foreignKeys = [ForeignKey(entity = FinanceCategoryEntity::class, parentColumns = ["id"], childColumns = ["categoryId"])])
data class FinanceCategoryPolicyEntity(
    val categoryId: String,
    val burden: FinanceBurden,
    val createdAt: Long,
    val updatedAt: Long,
    val revision: Long = 1,
    val voidedAt: Long? = null
)

@Entity(tableName = "finance_external_identities", primaryKeys = ["id"])
data class FinanceExternalIdentityEntity(
    val id: String,
    val sourceFamily: String,
    val label: String,
    val digestNamespace: String,
    val localDigest: String? = null,
    val matchUsable: Boolean = true,
    val createdAt: Long,
    val updatedAt: Long,
    val revision: Long = 1,
    val voidedAt: Long? = null
)

@Entity(tableName = "finance_instruments", primaryKeys = ["id"], indices = [Index("identityId")], foreignKeys = [ForeignKey(entity = FinanceExternalIdentityEntity::class, parentColumns = ["id"], childColumns = ["identityId"])])
data class FinanceInstrumentEntity(
    val id: String,
    val identityId: String,
    val label: String,
    val kind: String,
    val createdAt: Long,
    val updatedAt: Long,
    val revision: Long = 1,
    val voidedAt: Long? = null
)

@Entity(tableName = "finance_account_bindings", primaryKeys = ["id"], indices = [Index("instrumentId"), Index("accountId")], foreignKeys = [ForeignKey(entity = FinanceInstrumentEntity::class, parentColumns = ["id"], childColumns = ["instrumentId"]), ForeignKey(entity = FinanceAccountEntity::class, parentColumns = ["id"], childColumns = ["accountId"])])
data class FinanceAccountBindingEntity(
    val id: String,
    val instrumentId: String,
    val accountId: String,
    val validFrom: Long,
    val validUntil: Long? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val revision: Long = 1,
    val voidedAt: Long? = null
)

@Entity(tableName = "finance_evidence", primaryKeys = ["id"], indices = [Index("identityId"), Index("instrumentId"), Index("bindingId")], foreignKeys = [ForeignKey(entity = FinanceExternalIdentityEntity::class, parentColumns = ["id"], childColumns = ["identityId"]), ForeignKey(entity = FinanceInstrumentEntity::class, parentColumns = ["id"], childColumns = ["instrumentId"]), ForeignKey(entity = FinanceAccountBindingEntity::class, parentColumns = ["id"], childColumns = ["bindingId"])])
data class FinanceEvidenceEntity(
    val id: String,
    val provider: String,
    val sourceFamily: String,
    val identityId: String? = null,
    val instrumentId: String? = null,
    val referenceKind: FinanceReferenceKind,
    val referenceDigest: String? = null,
    val digestNamespace: String,
    val capturedAt: Long,
    val summary: String,
    val identityRevision: Long? = null,
    val bindingId: String? = null,
    val bindingRevision: Long? = null,
    val relatedOrderDigest: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val revision: Long = 1,
    val voidedAt: Long? = null
)

@Entity(tableName = "finance_extractions", primaryKeys = ["id"], indices = [Index("evidenceId")], foreignKeys = [ForeignKey(entity = FinanceEvidenceEntity::class, parentColumns = ["id"], childColumns = ["evidenceId"])])
data class FinanceExtractionEntity(
    val id: String,
    val evidenceId: String,
    val extractorVersion: Int,
    val amountMinor: Long? = null,
    val currencyCode: String? = null,
    val occurredAt: Long? = null,
    val direction: FinanceDirection? = null,
    val issue: String,
    val createdAt: Long,
    val updatedAt: Long,
    val revision: Long = 1,
    val voidedAt: Long? = null
)

@Entity(tableName = "finance_evidence_proposals", primaryKeys = ["evidenceId", "proposalId"], indices = [Index("evidenceId"), Index("proposalId")], foreignKeys = [ForeignKey(entity = FinanceEvidenceEntity::class, parentColumns = ["id"], childColumns = ["evidenceId"]), ForeignKey(entity = FinanceProposalEntity::class, parentColumns = ["id"], childColumns = ["proposalId"])])
data class FinanceEvidenceProposalEntity(
    val evidenceId: String,
    val proposalId: String
)

@Entity(tableName = "finance_media_links", primaryKeys = ["id"], indices = [Index("eventId"), Index("purchaseId"), Index("proposalId"), Index("mediaAssetId")], foreignKeys = [ForeignKey(entity = FinanceEventEntity::class, parentColumns = ["id"], childColumns = ["eventId"]), ForeignKey(entity = ShoppingPurchaseEntity::class, parentColumns = ["id"], childColumns = ["purchaseId"]), ForeignKey(entity = FinanceProposalEntity::class, parentColumns = ["id"], childColumns = ["proposalId"])])
data class FinanceMediaLinkEntity(
    val id: String,
    val mediaAssetId: String,
    val eventId: String? = null,
    val purchaseId: String? = null,
    val proposalId: String? = null,
    val sourceHash: String,
    val label: String,
    val createdAt: Long,
    val updatedAt: Long,
    val revision: Long = 1,
    val voidedAt: Long? = null
)

@Entity(tableName = "finance_review_sessions", primaryKeys = ["proposalId"], indices = [Index("proposalId")], foreignKeys = [ForeignKey(entity = FinanceProposalEntity::class, parentColumns = ["id"], childColumns = ["proposalId"])])
data class FinanceReviewSessionEntity(
    val proposalId: String,
    val baseRevision: Long,
    val epoch: Long,
    val amountText: String,
    val accountId: String? = null,
    val category: String,
    val subcategory: String,
    val description: String,
    val state: FinanceReviewState,
    val kind: FinanceDraftKind = FinanceDraftKind.EXPENSE,
    val targetAccountId: String? = null,
    val occurredAt: Long = 0,
    val occurredText: String = "",
    val categoryId: String? = null,
    val tagsText: String = "",
    val nature: FinanceNature = FinanceNature.PERSONAL,
    val role: FinanceFlowRole = FinanceFlowRole.PAYMENT,
    val eventId: String? = null,
    val eventUpdatedAt: Long? = null,
    val shareText: String = "",
    val excluded: Boolean = false,
    val excludedBudget: Boolean = false,
    val expectedEdited: Boolean = false,
    val costReductionText: String = "",
    val createdAt: Long,
    val updatedAt: Long,
    val revision: Long = 1,
    val voidedAt: Long? = null
)

@Entity(tableName = "finance_review_expected", primaryKeys = ["proposalId", "position"], indices = [Index("proposalId")], foreignKeys = [ForeignKey(entity = FinanceReviewSessionEntity::class, parentColumns = ["proposalId"], childColumns = ["proposalId"])])
data class FinanceReviewExpectedEntity(
    val proposalId: String,
    val position: Int,
    val expectedId: String? = null,
    val role: FinanceFlowRole,
    val amountText: String,
    val note: String,
    val cancelled: Boolean
)

@Entity(tableName = "finance_purpose_grants", primaryKeys = ["id"])
data class FinancePurposeGrantEntity(
    val id: String,
    val purpose: FinanceReadPurpose,
    val providerId: String,
    val allowAmount: Boolean,
    val allowDate: Boolean,
    val allowDescription: Boolean,
    val expiresAt: Long,
    val createdAt: Long,
    val updatedAt: Long,
    val revision: Long = 1,
    val voidedAt: Long? = null
)

@Entity(tableName = "finance_grant_events", primaryKeys = ["grantId", "eventId"], indices = [Index("grantId"), Index("eventId")], foreignKeys = [ForeignKey(entity = FinancePurposeGrantEntity::class, parentColumns = ["id"], childColumns = ["grantId"]), ForeignKey(entity = FinanceEventEntity::class, parentColumns = ["id"], childColumns = ["eventId"])])
data class FinanceGrantEventEntity(
    val grantId: String,
    val eventId: String
)

@Entity(tableName = "finance_grant_entries", primaryKeys = ["grantId", "entryId"], indices = [Index("grantId"), Index("entryId")], foreignKeys = [ForeignKey(entity = FinancePurposeGrantEntity::class, parentColumns = ["id"], childColumns = ["grantId"]), ForeignKey(entity = FinanceEntryEntity::class, parentColumns = ["id"], childColumns = ["entryId"])])
data class FinanceGrantEntryEntity(
    val grantId: String,
    val entryId: String
)

@Entity(tableName = "finance_command_receipts", primaryKeys = ["operationId"])
data class FinanceCommandReceiptEntity(
    val operationId: String,
    val command: String,
    val inputDigest: String,
    val resultId: String,
    val epoch: Long,
    val createdAt: Long
)

@Entity(tableName = "finance_control", primaryKeys = ["id"])
data class FinanceControlEntity(
    val id: Int,
    val restoreEpoch: Long
)

@Entity(tableName = "finance_recovery_groups", primaryKeys = ["id"])
data class FinanceRecoveryGroupEntity(
    val id: String,
    val kind: String,
    val epoch: Long,
    val createdAt: Long,
    val consumedAt: Long? = null,
    val afterFingerprint: String
)

@Entity(tableName = "finance_recovery_entries", primaryKeys = ["groupId", "entryId"], indices = [Index("groupId")], foreignKeys = [ForeignKey(entity = FinanceRecoveryGroupEntity::class, parentColumns = ["id"], childColumns = ["groupId"])])
data class FinanceRecoveryEntryEntity(
    val groupId: String,
    val entryId: String,
    val accountId: String,
    val accountCurrencyCode: String,
    val direction: FinanceDirection,
    val amountMinor: Long,
    val description: String,
    val occurredAt: Long,
    val recordedAt: Long,
    val updatedAt: Long,
    val categoryId: String? = null,
    val voidedAt: Long? = null,
    val statPolicy: FinanceStatPolicy,
    val budgetPolicy: FinanceBudgetPolicy,
    val revision: Long,
    val importBatchId: String? = null
)

@Entity(tableName = "finance_recovery_links", primaryKeys = ["groupId", "linkId"], indices = [Index("groupId")], foreignKeys = [ForeignKey(entity = FinanceRecoveryGroupEntity::class, parentColumns = ["id"], childColumns = ["groupId"])])
data class FinanceRecoveryLinkEntity(
    val groupId: String,
    val linkId: String,
    val eventId: String,
    val entryId: String,
    val role: FinanceFlowRole,
    val allocatedMinor: Long,
    val createdAt: Long,
    val updatedAt: Long,
    val revision: Long = 1,
    val voidedAt: Long? = null
)

@Entity(tableName = "finance_recovery_events", primaryKeys = ["groupId", "eventId"], indices = [Index("groupId")], foreignKeys = [ForeignKey(entity = FinanceRecoveryGroupEntity::class, parentColumns = ["id"], childColumns = ["groupId"])])
data class FinanceRecoveryEventEntity(
    val groupId: String,
    val eventId: String,
    val updatedAt: Long,
    val voidedAt: Long? = null
)

@Entity(tableName = "finance_recovery_expected", primaryKeys = ["groupId", "expectedId"], indices = [Index("groupId")], foreignKeys = [ForeignKey(entity = FinanceRecoveryGroupEntity::class, parentColumns = ["id"], childColumns = ["groupId"])])
data class FinanceRecoveryExpectedEntity(
    val groupId: String,
    val expectedId: String,
    val cancelledAt: Long? = null
)

@Entity(tableName = "finance_recovery_transfers", primaryKeys = ["groupId", "transferId"], indices = [Index("groupId")], foreignKeys = [ForeignKey(entity = FinanceRecoveryGroupEntity::class, parentColumns = ["id"], childColumns = ["groupId"])])
data class FinanceRecoveryTransferEntity(
    val groupId: String,
    val transferId: String,
    val updatedAt: Long,
    val voidedAt: Long? = null
)

@Entity(tableName = "finance_recovery_tags", primaryKeys = ["groupId", "entryId", "tagId"], indices = [Index("groupId")], foreignKeys = [ForeignKey(entity = FinanceRecoveryGroupEntity::class, parentColumns = ["id"], childColumns = ["groupId"])])
data class FinanceRecoveryTagEntity(
    val groupId: String,
    val entryId: String,
    val tagId: String
)

@Entity(tableName = "finance_recovery_members", primaryKeys = ["groupId", "kind", "memberId"], indices = [Index("groupId")], foreignKeys = [ForeignKey(entity = FinanceRecoveryGroupEntity::class, parentColumns = ["id"], childColumns = ["groupId"])])
data class FinanceRecoveryMemberEntity(
    val groupId: String,
    val kind: FinanceRecoveryMemberKind,
    val memberId: String,
    val revision: Long,
    val updatedAt: Long,
    val voidedAt: Long? = null,
    val categoryId: String? = null
)
