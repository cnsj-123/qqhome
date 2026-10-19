package com.qq.closie.life.data.database.dao

import androidx.room.*
import com.qq.closie.life.finance.*
import com.qq.closie.life.shopping.*
import kotlinx.coroutines.flow.Flow

@Dao
interface FinanceV16Dao {
    @Query("SELECT * FROM finance_parties") suspend fun partyRows(): List<FinancePartyEntity>
    @Upsert suspend fun putParty(rows: List<FinancePartyEntity>)
    @Query("SELECT * FROM finance_economics") suspend fun economicRows(): List<FinanceEconomicEntity>
    @Upsert suspend fun putEconomic(rows: List<FinanceEconomicEntity>)
    @Query("SELECT * FROM finance_funding") suspend fun fundingRows(): List<FinanceFundingEntity>
    @Upsert suspend fun putFunding(rows: List<FinanceFundingEntity>)
    @Query("SELECT * FROM finance_shares") suspend fun shareRows(): List<FinanceShareEntity>
    @Upsert suspend fun putShare(rows: List<FinanceShareEntity>)
    @Query("SELECT * FROM finance_obligations") suspend fun obligationRows(): List<FinanceObligationEntity>
    @Upsert suspend fun putObligation(rows: List<FinanceObligationEntity>)
    @Query("SELECT * FROM finance_settlements") suspend fun settlementRows(): List<FinanceSettlementEntity>
    @Upsert suspend fun putSettlement(rows: List<FinanceSettlementEntity>)
    @Query("SELECT * FROM finance_resolutions") suspend fun resolutionRows(): List<FinanceResolutionEntity>
    @Upsert suspend fun putResolution(rows: List<FinanceResolutionEntity>)
    @Query("SELECT * FROM shopping_purchases") suspend fun purchaseRows(): List<ShoppingPurchaseEntity>
    @Upsert suspend fun putPurchase(rows: List<ShoppingPurchaseEntity>)
    @Query("SELECT * FROM shopping_purchase_lines") suspend fun purchaseLineRows(): List<ShoppingPurchaseLineEntity>
    @Upsert suspend fun putPurchaseLine(rows: List<ShoppingPurchaseLineEntity>)
    @Query("SELECT * FROM finance_purchase_allocations") suspend fun purchaseAllocationRows(): List<FinancePurchaseAllocationEntity>
    @Upsert suspend fun putPurchaseAllocation(rows: List<FinancePurchaseAllocationEntity>)
    @Query("SELECT * FROM finance_cost_adjustments") suspend fun costAdjustmentRows(): List<FinanceCostAdjustmentEntity>
    @Upsert suspend fun putCostAdjustment(rows: List<FinanceCostAdjustmentEntity>)
    @Query("SELECT * FROM finance_entry_timing") suspend fun entryTimingRows(): List<FinanceEntryTimingEntity>
    @Upsert suspend fun putEntryTiming(rows: List<FinanceEntryTimingEntity>)
    @Query("SELECT * FROM finance_account_policies") suspend fun accountPolicyRows(): List<FinanceAccountPolicyEntity>
    @Upsert suspend fun putAccountPolicy(rows: List<FinanceAccountPolicyEntity>)
    @Query("SELECT * FROM finance_observations") suspend fun observationRows(): List<FinanceObservationEntity>
    @Upsert suspend fun putObservation(rows: List<FinanceObservationEntity>)
    @Query("SELECT * FROM finance_reconciliations") suspend fun reconciliationRows(): List<FinanceReconciliationEntity>
    @Upsert suspend fun putReconciliation(rows: List<FinanceReconciliationEntity>)
    @Query("SELECT * FROM finance_credit_cycles") suspend fun creditCycleRows(): List<FinanceCreditCycleEntity>
    @Upsert suspend fun putCreditCycle(rows: List<FinanceCreditCycleEntity>)
    @Query("SELECT * FROM finance_drafts") suspend fun draftRows(): List<FinanceDraftEntity>
    @Upsert suspend fun putDraft(rows: List<FinanceDraftEntity>)
    @Query("SELECT * FROM finance_draft_tags") suspend fun draftTagRows(): List<FinanceDraftTagEntity>
    @Upsert suspend fun putDraftTag(rows: List<FinanceDraftTagEntity>)
    @Query("SELECT * FROM finance_draft_expected") suspend fun draftExpectedRows(): List<FinanceDraftExpectedEntity>
    @Upsert suspend fun putDraftExpected(rows: List<FinanceDraftExpectedEntity>)
    @Query("SELECT * FROM finance_economic_drafts") suspend fun economicDraftRows(): List<FinanceEconomicDraftEntity>
    @Upsert suspend fun putEconomicDraft(rows: List<FinanceEconomicDraftEntity>)
    @Query("SELECT * FROM finance_economic_draft_funding") suspend fun economicDraftFundingRows(): List<FinanceEconomicDraftFundingEntity>
    @Upsert suspend fun putEconomicDraftFunding(rows: List<FinanceEconomicDraftFundingEntity>)
    @Query("SELECT * FROM finance_economic_draft_shares") suspend fun economicDraftShareRows(): List<FinanceEconomicDraftShareEntity>
    @Upsert suspend fun putEconomicDraftShare(rows: List<FinanceEconomicDraftShareEntity>)
    @Query("SELECT * FROM finance_category_icons") suspend fun categoryIconRows(): List<FinanceCategoryIconEntity>
    @Upsert suspend fun putCategoryIcon(rows: List<FinanceCategoryIconEntity>)
    @Query("SELECT * FROM finance_saved_queries") suspend fun savedQueryRows(): List<FinanceSavedQueryEntity>
    @Upsert suspend fun putSavedQuery(rows: List<FinanceSavedQueryEntity>)
    @Query("SELECT * FROM finance_budgets") suspend fun budgetRows(): List<FinanceBudgetEntity>
    @Upsert suspend fun putBudget(rows: List<FinanceBudgetEntity>)
    @Query("SELECT * FROM finance_category_policies") suspend fun categoryPolicyRows(): List<FinanceCategoryPolicyEntity>
    @Upsert suspend fun putCategoryPolicy(rows: List<FinanceCategoryPolicyEntity>)
    @Query("SELECT * FROM finance_external_identities") suspend fun externalIdentityRows(): List<FinanceExternalIdentityEntity>
    @Upsert suspend fun putExternalIdentity(rows: List<FinanceExternalIdentityEntity>)
    @Query("SELECT * FROM finance_instruments") suspend fun instrumentRows(): List<FinanceInstrumentEntity>
    @Upsert suspend fun putInstrument(rows: List<FinanceInstrumentEntity>)
    @Query("SELECT * FROM finance_account_bindings") suspend fun accountBindingRows(): List<FinanceAccountBindingEntity>
    @Upsert suspend fun putAccountBinding(rows: List<FinanceAccountBindingEntity>)
    @Query("SELECT * FROM finance_evidence") suspend fun evidenceRows(): List<FinanceEvidenceEntity>
    @Upsert suspend fun putEvidence(rows: List<FinanceEvidenceEntity>)
    @Query("SELECT * FROM finance_extractions") suspend fun extractionRows(): List<FinanceExtractionEntity>
    @Upsert suspend fun putExtraction(rows: List<FinanceExtractionEntity>)
    @Query("SELECT * FROM finance_evidence_proposals") suspend fun evidenceProposalRows(): List<FinanceEvidenceProposalEntity>
    @Upsert suspend fun putEvidenceProposal(rows: List<FinanceEvidenceProposalEntity>)
    @Query("SELECT * FROM finance_media_links") suspend fun mediaLinkRows(): List<FinanceMediaLinkEntity>
    @Upsert suspend fun putMediaLink(rows: List<FinanceMediaLinkEntity>)
    @Query("SELECT * FROM finance_review_sessions") suspend fun reviewSessionRows(): List<FinanceReviewSessionEntity>
    @Upsert suspend fun putReviewSession(rows: List<FinanceReviewSessionEntity>)
    @Query("SELECT * FROM finance_review_expected") suspend fun reviewExpectedRows(): List<FinanceReviewExpectedEntity>
    @Upsert suspend fun putReviewExpected(rows: List<FinanceReviewExpectedEntity>)
    @Query("SELECT * FROM finance_purpose_grants") suspend fun purposeGrantRows(): List<FinancePurposeGrantEntity>
    @Upsert suspend fun putPurposeGrant(rows: List<FinancePurposeGrantEntity>)
    @Query("SELECT * FROM finance_grant_events") suspend fun grantEventRows(): List<FinanceGrantEventEntity>
    @Upsert suspend fun putGrantEvent(rows: List<FinanceGrantEventEntity>)
    @Query("SELECT * FROM finance_grant_entries") suspend fun grantEntryRows(): List<FinanceGrantEntryEntity>
    @Upsert suspend fun putGrantEntry(rows: List<FinanceGrantEntryEntity>)
    @Query("SELECT * FROM finance_command_receipts") suspend fun commandReceiptRows(): List<FinanceCommandReceiptEntity>
    @Upsert suspend fun putCommandReceipt(rows: List<FinanceCommandReceiptEntity>)
    @Query("SELECT * FROM finance_control") suspend fun controlRows(): List<FinanceControlEntity>
    @Upsert suspend fun putControl(rows: List<FinanceControlEntity>)
    @Query("SELECT * FROM finance_recovery_groups") suspend fun recoveryGroupRows(): List<FinanceRecoveryGroupEntity>
    @Upsert suspend fun putRecoveryGroup(rows: List<FinanceRecoveryGroupEntity>)
    @Query("SELECT * FROM finance_recovery_entries") suspend fun recoveryEntryRows(): List<FinanceRecoveryEntryEntity>
    @Upsert suspend fun putRecoveryEntry(rows: List<FinanceRecoveryEntryEntity>)
    @Query("SELECT * FROM finance_recovery_links") suspend fun recoveryLinkRows(): List<FinanceRecoveryLinkEntity>
    @Upsert suspend fun putRecoveryLink(rows: List<FinanceRecoveryLinkEntity>)
    @Query("SELECT * FROM finance_recovery_events") suspend fun recoveryEventRows(): List<FinanceRecoveryEventEntity>
    @Upsert suspend fun putRecoveryEvent(rows: List<FinanceRecoveryEventEntity>)
    @Query("SELECT * FROM finance_recovery_expected") suspend fun recoveryExpectedRows(): List<FinanceRecoveryExpectedEntity>
    @Upsert suspend fun putRecoveryExpected(rows: List<FinanceRecoveryExpectedEntity>)
    @Query("SELECT * FROM finance_recovery_transfers") suspend fun recoveryTransferRows(): List<FinanceRecoveryTransferEntity>
    @Upsert suspend fun putRecoveryTransfer(rows: List<FinanceRecoveryTransferEntity>)
    @Query("SELECT * FROM finance_recovery_tags") suspend fun recoveryTagRows(): List<FinanceRecoveryTagEntity>
    @Upsert suspend fun putRecoveryTag(rows: List<FinanceRecoveryTagEntity>)
    @Query("SELECT * FROM finance_recovery_members") suspend fun recoveryMemberRows(): List<FinanceRecoveryMemberEntity>
    @Upsert suspend fun putRecoveryMember(rows: List<FinanceRecoveryMemberEntity>)
    @Query("DELETE FROM finance_draft_tags") suspend fun clearDraftTagsForRestore()
    @Query("DELETE FROM finance_economic_draft_funding") suspend fun clearEconomicDraftFundingForRestore()
    @Query("DELETE FROM finance_economic_draft_shares") suspend fun clearEconomicDraftSharesForRestore()
    @Query("DELETE FROM finance_economic_draft_funding WHERE draftId = :id") suspend fun clearEconomicDraftFunding(id:String)
    @Query("DELETE FROM finance_economic_draft_shares WHERE draftId = :id") suspend fun clearEconomicDraftShares(id:String)
    @Query("DELETE FROM finance_review_expected") suspend fun clearReviewExpectedForRestore()
    @Query("DELETE FROM finance_review_expected WHERE proposalId = :id") suspend fun clearReviewExpected(id: String)
    @Query("DELETE FROM finance_draft_tags WHERE draftId = :id") suspend fun clearDraftTags(id: String)
    @Query("DELETE FROM finance_draft_expected") suspend fun clearDraftExpectedForRestore()
    @Query("DELETE FROM finance_draft_expected WHERE draftId = :id") suspend fun clearDraftExpected(id: String)
    @Query("DELETE FROM finance_evidence_proposals") suspend fun clearEvidenceProposalsForRestore()
    @Query("DELETE FROM finance_grant_events") suspend fun clearGrantEventsForRestore()
    @Query("DELETE FROM finance_grant_events WHERE grantId = :id") suspend fun clearGrantEvents(id: String)
    @Query("DELETE FROM finance_grant_entries") suspend fun clearGrantEntriesForRestore()
    @Query("DELETE FROM finance_grant_entries WHERE grantId = :id") suspend fun clearGrantEntries(id: String)
    @Query("SELECT (SELECT COUNT(*) FROM finance_parties) + (SELECT COUNT(*) FROM finance_economics) + (SELECT COUNT(*) FROM finance_funding) + (SELECT COUNT(*) FROM finance_shares) + (SELECT COUNT(*) FROM finance_obligations) + (SELECT COUNT(*) FROM finance_settlements) + (SELECT COUNT(*) FROM finance_resolutions) + (SELECT COUNT(*) FROM shopping_purchases) + (SELECT COUNT(*) FROM shopping_purchase_lines) + (SELECT COUNT(*) FROM finance_purchase_allocations) + (SELECT COUNT(*) FROM finance_cost_adjustments) + (SELECT COUNT(*) FROM finance_entry_timing) + (SELECT COUNT(*) FROM finance_account_policies) + (SELECT COUNT(*) FROM finance_observations) + (SELECT COUNT(*) FROM finance_reconciliations) + (SELECT COUNT(*) FROM finance_credit_cycles) + (SELECT COUNT(*) FROM finance_drafts) + (SELECT COUNT(*) FROM finance_draft_tags) + (SELECT COUNT(*) FROM finance_draft_expected) + (SELECT COUNT(*) FROM finance_economic_drafts) + (SELECT COUNT(*) FROM finance_economic_draft_funding) + (SELECT COUNT(*) FROM finance_economic_draft_shares) + (SELECT COUNT(*) FROM finance_category_icons) + (SELECT COUNT(*) FROM finance_saved_queries) + (SELECT COUNT(*) FROM finance_budgets) + (SELECT COUNT(*) FROM finance_category_policies) + (SELECT COUNT(*) FROM finance_external_identities) + (SELECT COUNT(*) FROM finance_instruments) + (SELECT COUNT(*) FROM finance_account_bindings) + (SELECT COUNT(*) FROM finance_evidence) + (SELECT COUNT(*) FROM finance_extractions) + (SELECT COUNT(*) FROM finance_evidence_proposals) + (SELECT COUNT(*) FROM finance_media_links) + (SELECT COUNT(*) FROM finance_review_sessions) + (SELECT COUNT(*) FROM finance_review_expected) + (SELECT COUNT(*) FROM finance_purpose_grants) + (SELECT COUNT(*) FROM finance_grant_events) + (SELECT COUNT(*) FROM finance_grant_entries) + (SELECT COUNT(*) FROM finance_command_receipts) + (SELECT COUNT(*) FROM finance_control) + (SELECT COUNT(*) FROM finance_recovery_groups) + (SELECT COUNT(*) FROM finance_recovery_entries) + (SELECT COUNT(*) FROM finance_recovery_links) + (SELECT COUNT(*) FROM finance_recovery_events) + (SELECT COUNT(*) FROM finance_recovery_expected) + (SELECT COUNT(*) FROM finance_recovery_transfers) + (SELECT COUNT(*) FROM finance_recovery_tags) + (SELECT COUNT(*) FROM finance_recovery_members)")
    fun observeChanges(): Flow<Long>
}
