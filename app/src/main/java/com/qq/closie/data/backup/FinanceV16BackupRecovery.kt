package com.qq.closie.data.backup

import com.qq.closie.life.data.database.LifeDatabase
import com.qq.closie.life.finance.*

/** Only the restore coordinator may call this, inside its existing atomic DB commit. */
internal object FinanceV16BackupRecovery {
    suspend fun restore(db: LifeDatabase, saved: FinanceV16Snapshot) {
        val dao = db.financeV16Dao()
        val current = readFinanceV16Snapshot(db)
        val now = System.currentTimeMillis()
        run {
            val incoming = saved.partyRows
            val ids = incoming.map { it.id }.toSet()
            dao.putParty(current.partyRows.filter { it.id !in ids }.map { it.copy(voidedAt = it.voidedAt ?: now) })
            dao.putParty(incoming)
        }
        run {
            val incoming = saved.economicRows
            val ids = incoming.map { it.eventId }.toSet()
            dao.putEconomic(current.economicRows.filter { it.eventId !in ids }.map { it.copy(voidedAt = it.voidedAt ?: now) })
            dao.putEconomic(incoming)
        }
        run {
            val incoming = saved.fundingRows
            val ids = incoming.map { it.id }.toSet()
            dao.putFunding(current.fundingRows.filter { it.id !in ids }.map { it.copy(voidedAt = it.voidedAt ?: now) })
            dao.putFunding(incoming)
        }
        run {
            val incoming = saved.shareRows
            val ids = incoming.map { it.id }.toSet()
            dao.putShare(current.shareRows.filter { it.id !in ids }.map { it.copy(voidedAt = it.voidedAt ?: now) })
            dao.putShare(incoming)
        }
        run {
            val incoming = saved.obligationRows
            val ids = incoming.map { it.id }.toSet()
            dao.putObligation(current.obligationRows.filter { it.id !in ids }.map { it.copy(voidedAt = it.voidedAt ?: now) })
            dao.putObligation(incoming)
        }
        run {
            val incoming = saved.settlementRows
            val ids = incoming.map { it.id }.toSet()
            dao.putSettlement(current.settlementRows.filter { it.id !in ids }.map { it.copy(voidedAt = it.voidedAt ?: now) })
            dao.putSettlement(incoming)
        }
        run {
            val incoming = saved.resolutionRows
            val ids = incoming.map { it.id }.toSet()
            dao.putResolution(current.resolutionRows.filter { it.id !in ids }.map { it.copy(voidedAt = it.voidedAt ?: now) })
            dao.putResolution(incoming)
        }
        run {
            val incoming = saved.purchaseRows
            val ids = incoming.map { it.id }.toSet()
            dao.putPurchase(current.purchaseRows.filter { it.id !in ids }.map { it.copy(voidedAt = it.voidedAt ?: now) })
            dao.putPurchase(incoming)
        }
        run {
            val incoming = saved.purchaseLineRows
            val ids = incoming.map { it.id }.toSet()
            dao.putPurchaseLine(current.purchaseLineRows.filter { it.id !in ids }.map { it.copy(voidedAt = it.voidedAt ?: now) })
            dao.putPurchaseLine(incoming)
        }
        run {
            val incoming = saved.purchaseAllocationRows
            val ids = incoming.map { it.id }.toSet()
            dao.putPurchaseAllocation(current.purchaseAllocationRows.filter { it.id !in ids }.map { it.copy(voidedAt = it.voidedAt ?: now) })
            dao.putPurchaseAllocation(incoming)
        }
        run {
            val incoming = saved.costAdjustmentRows
            val ids = incoming.map { it.id }.toSet()
            dao.putCostAdjustment(current.costAdjustmentRows.filter { it.id !in ids }.map { it.copy(voidedAt = it.voidedAt ?: now) })
            dao.putCostAdjustment(incoming)
        }
        run {
            val incoming = saved.entryTimingRows
            val ids = incoming.map { it.entryId }.toSet()
            dao.putEntryTiming(current.entryTimingRows.filter { it.entryId !in ids }.map { it.copy(voidedAt = it.voidedAt ?: now) })
            dao.putEntryTiming(incoming)
        }
        run {
            val incoming = saved.accountPolicyRows
            val ids = incoming.map { it.accountId }.toSet()
            dao.putAccountPolicy(current.accountPolicyRows.filter { it.accountId !in ids }.map { it.copy(voidedAt = it.voidedAt ?: now) })
            dao.putAccountPolicy(incoming)
        }
        run {
            val incoming = saved.observationRows
            val ids = incoming.map { it.id }.toSet()
            dao.putObservation(current.observationRows.filter { it.id !in ids }.map { it.copy(voidedAt = it.voidedAt ?: now) })
            dao.putObservation(incoming)
        }
        run {
            val incoming = saved.reconciliationRows.sortedBy { it.confirmationOrder }.mapIndexed { index, row -> row.copy(confirmationOrder = Math.addExact(current.reconciliationRows.maxOfOrNull { it.confirmationOrder } ?: 0L, index.toLong() + 1L)) }
            val ids = incoming.map { it.id }.toSet()
            dao.putReconciliation(current.reconciliationRows.filter { it.id !in ids }.map { it.copy(voidedAt = it.voidedAt ?: now) })
            dao.putReconciliation(incoming)
        }
        run {
            val incoming = saved.creditCycleRows
            val ids = incoming.map { it.accountId }.toSet()
            dao.putCreditCycle(current.creditCycleRows.filter { it.accountId !in ids }.map { it.copy(voidedAt = it.voidedAt ?: now) })
            dao.putCreditCycle(incoming)
        }
        run {
            val incoming = saved.draftRows
            val ids = incoming.map { it.id }.toSet()
            dao.putDraft(current.draftRows.filter { it.id !in ids }.map { it.copy(voidedAt = it.voidedAt ?: now) })
            dao.putDraft(incoming)
        }
        run {
            dao.clearDraftTagsForRestore()
            val incoming = saved.draftTagRows
            dao.putDraftTag(incoming)
        }
        run {
            dao.clearDraftExpectedForRestore()
            val incoming = saved.draftExpectedRows
            dao.putDraftExpected(incoming)
        }
        run {
            val incoming = saved.economicDraftRows
            val ids = incoming.map { it.id }.toSet()
            dao.putEconomicDraft(current.economicDraftRows.filter { it.id !in ids }.map { it.copy(voidedAt = it.voidedAt ?: now) })
            dao.putEconomicDraft(incoming)
        }
        run {
            dao.clearEconomicDraftFundingForRestore()
            val incoming = saved.economicDraftFundingRows
            dao.putEconomicDraftFunding(incoming)
        }
        run {
            dao.clearEconomicDraftSharesForRestore()
            val incoming = saved.economicDraftShareRows
            dao.putEconomicDraftShare(incoming)
        }
        run {
            val incoming = saved.categoryIconRows
            val ids = incoming.map { it.categoryId }.toSet()
            dao.putCategoryIcon(current.categoryIconRows.filter { it.categoryId !in ids }.map { it.copy(voidedAt = it.voidedAt ?: now) })
            dao.putCategoryIcon(incoming)
        }
        run {
            val incoming = saved.savedQueryRows
            val ids = incoming.map { it.id }.toSet()
            dao.putSavedQuery(current.savedQueryRows.filter { it.id !in ids }.map { it.copy(voidedAt = it.voidedAt ?: now) })
            dao.putSavedQuery(incoming)
        }
        run {
            val incoming = saved.budgetRows
            val ids = incoming.map { it.id }.toSet()
            dao.putBudget(current.budgetRows.filter { it.id !in ids }.map { it.copy(voidedAt = it.voidedAt ?: now) })
            dao.putBudget(incoming)
        }
        run {
            val incoming = saved.categoryPolicyRows
            val ids = incoming.map { it.categoryId }.toSet()
            dao.putCategoryPolicy(current.categoryPolicyRows.filter { it.categoryId !in ids }.map { it.copy(voidedAt = it.voidedAt ?: now) })
            dao.putCategoryPolicy(incoming)
        }
        run {
            val incoming = saved.externalIdentityRows.map { it.copy(matchUsable = false) }
            val ids = incoming.map { it.id }.toSet()
            dao.putExternalIdentity(current.externalIdentityRows.filter { it.id !in ids }.map { it.copy(voidedAt = it.voidedAt ?: now) })
            dao.putExternalIdentity(incoming)
        }
        run {
            val incoming = saved.instrumentRows
            val ids = incoming.map { it.id }.toSet()
            dao.putInstrument(current.instrumentRows.filter { it.id !in ids }.map { it.copy(voidedAt = it.voidedAt ?: now) })
            dao.putInstrument(incoming)
        }
        run {
            val incoming = saved.accountBindingRows
            val ids = incoming.map { it.id }.toSet()
            dao.putAccountBinding(current.accountBindingRows.filter { it.id !in ids }.map { it.copy(voidedAt = it.voidedAt ?: now) })
            dao.putAccountBinding(incoming)
        }
        run {
            val incoming = saved.evidenceRows
            val ids = incoming.map { it.id }.toSet()
            dao.putEvidence(current.evidenceRows.filter { it.id !in ids }.map { it.copy(voidedAt = it.voidedAt ?: now) })
            dao.putEvidence(incoming)
        }
        run {
            val incoming = saved.extractionRows
            val ids = incoming.map { it.id }.toSet()
            dao.putExtraction(current.extractionRows.filter { it.id !in ids }.map { it.copy(voidedAt = it.voidedAt ?: now) })
            dao.putExtraction(incoming)
        }
        run {
            dao.clearEvidenceProposalsForRestore()
            val incoming = saved.evidenceProposalRows
            dao.putEvidenceProposal(incoming)
        }
        run {
            val incoming = saved.mediaLinkRows
            val ids = incoming.map { it.id }.toSet()
            dao.putMediaLink(current.mediaLinkRows.filter { it.id !in ids }.map { it.copy(voidedAt = it.voidedAt ?: now) })
            dao.putMediaLink(incoming)
        }
        run {
            val incoming = saved.reviewSessionRows.map { it.copy(state = FinanceReviewState.LATER) }
            val ids = incoming.map { it.proposalId }.toSet()
            dao.putReviewSession(current.reviewSessionRows.filter { it.proposalId !in ids }.map { it.copy(voidedAt = it.voidedAt ?: now) })
            dao.putReviewSession(incoming)
        }
        run {
            dao.clearReviewExpectedForRestore()
            val incoming = saved.reviewExpectedRows
            dao.putReviewExpected(incoming)
        }
        run {
            val incoming = saved.purposeGrantRows.map { it.copy(voidedAt = now, revision = Math.addExact(it.revision, 1L)) }
            val ids = incoming.map { it.id }.toSet()
            dao.putPurposeGrant(current.purposeGrantRows.filter { it.id !in ids }.map { it.copy(voidedAt = it.voidedAt ?: now) })
            dao.putPurposeGrant(incoming)
        }
        run {
            dao.clearGrantEventsForRestore()
            val incoming = saved.grantEventRows
            dao.putGrantEvent(incoming)
        }
        run {
            dao.clearGrantEntriesForRestore()
            val incoming = saved.grantEntryRows
            dao.putGrantEntry(incoming)
        }
        run {
            val incoming = saved.commandReceiptRows
            dao.putCommandReceipt(incoming)
        }
        run {
            val incoming = saved.recoveryGroupRows
            dao.putRecoveryGroup(incoming)
        }
        run {
            val incoming = saved.recoveryEntryRows
            val ids = incoming.map { listOf(it.groupId, it.entryId) }.toSet()
            dao.putRecoveryEntry(current.recoveryEntryRows.filter { listOf(it.groupId, it.entryId) !in ids }.map { it.copy(voidedAt = it.voidedAt ?: now) })
            dao.putRecoveryEntry(incoming)
        }
        run {
            val incoming = saved.recoveryLinkRows
            val ids = incoming.map { listOf(it.groupId, it.linkId) }.toSet()
            dao.putRecoveryLink(current.recoveryLinkRows.filter { listOf(it.groupId, it.linkId) !in ids }.map { it.copy(voidedAt = it.voidedAt ?: now) })
            dao.putRecoveryLink(incoming)
        }
        run {
            val incoming = saved.recoveryEventRows
            val ids = incoming.map { listOf(it.groupId, it.eventId) }.toSet()
            dao.putRecoveryEvent(current.recoveryEventRows.filter { listOf(it.groupId, it.eventId) !in ids }.map { it.copy(voidedAt = it.voidedAt ?: now) })
            dao.putRecoveryEvent(incoming)
        }
        run {
            val incoming = saved.recoveryExpectedRows
            dao.putRecoveryExpected(incoming)
        }
        run {
            val incoming = saved.recoveryTransferRows
            val ids = incoming.map { listOf(it.groupId, it.transferId) }.toSet()
            dao.putRecoveryTransfer(current.recoveryTransferRows.filter { listOf(it.groupId, it.transferId) !in ids }.map { it.copy(voidedAt = it.voidedAt ?: now) })
            dao.putRecoveryTransfer(incoming)
        }
        run {
            val incoming = saved.recoveryTagRows
            dao.putRecoveryTag(incoming)
        }
        run {
            val incoming = saved.recoveryMemberRows
            val ids = incoming.map { listOf(it.groupId, it.kind, it.memberId) }.toSet()
            dao.putRecoveryMember(current.recoveryMemberRows.filter { listOf(it.groupId, it.kind, it.memberId) !in ids }.map { it.copy(voidedAt = it.voidedAt ?: now) })
            dao.putRecoveryMember(incoming)
        }
        val epoch = maxOf(current.controlRows.singleOrNull()?.restoreEpoch ?: 0L, saved.controlRows.singleOrNull()?.restoreEpoch ?: 0L)
        dao.putControl(listOf(FinanceControlEntity(1, Math.addExact(epoch, 1L))))
    }
}
