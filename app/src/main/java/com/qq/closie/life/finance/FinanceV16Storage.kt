package com.qq.closie.life.finance

import com.qq.closie.life.data.database.LifeDatabase

/** Caller holds the restore lease and the single Room transaction. */
internal suspend fun readFinanceV16Snapshot(db: LifeDatabase): FinanceV16Snapshot {
    val dao = db.financeV16Dao()
    return FinanceV16Snapshot(
        partyRows = dao.partyRows(),
        economicRows = dao.economicRows(),
        fundingRows = dao.fundingRows(),
        shareRows = dao.shareRows(),
        obligationRows = dao.obligationRows(),
        settlementRows = dao.settlementRows(),
        resolutionRows = dao.resolutionRows(),
        purchaseRows = dao.purchaseRows(),
        purchaseLineRows = dao.purchaseLineRows(),
        purchaseAllocationRows = dao.purchaseAllocationRows(),
        costAdjustmentRows = dao.costAdjustmentRows(),
        entryTimingRows = dao.entryTimingRows(),
        accountPolicyRows = dao.accountPolicyRows(),
        observationRows = dao.observationRows(),
        reconciliationRows = dao.reconciliationRows(),
        creditCycleRows = dao.creditCycleRows(),
        draftRows = dao.draftRows(),
        draftTagRows = dao.draftTagRows(),
        draftExpectedRows = dao.draftExpectedRows(),
        economicDraftRows = dao.economicDraftRows(),
        economicDraftFundingRows = dao.economicDraftFundingRows(),
        economicDraftShareRows = dao.economicDraftShareRows(),
        categoryIconRows = dao.categoryIconRows(),
        savedQueryRows = dao.savedQueryRows(),
        budgetRows = dao.budgetRows(),
        categoryPolicyRows = dao.categoryPolicyRows(),
        externalIdentityRows = dao.externalIdentityRows(),
        instrumentRows = dao.instrumentRows(),
        accountBindingRows = dao.accountBindingRows(),
        evidenceRows = dao.evidenceRows(),
        extractionRows = dao.extractionRows(),
        evidenceProposalRows = dao.evidenceProposalRows(),
        mediaLinkRows = dao.mediaLinkRows(),
        reviewSessionRows = dao.reviewSessionRows(),
        reviewExpectedRows = dao.reviewExpectedRows(),
        purposeGrantRows = dao.purposeGrantRows(),
        grantEventRows = dao.grantEventRows(),
        grantEntryRows = dao.grantEntryRows(),
        commandReceiptRows = dao.commandReceiptRows(),
        controlRows = dao.controlRows(),
        recoveryGroupRows = dao.recoveryGroupRows(),
        recoveryEntryRows = dao.recoveryEntryRows(),
        recoveryLinkRows = dao.recoveryLinkRows(),
        recoveryEventRows = dao.recoveryEventRows(),
        recoveryExpectedRows = dao.recoveryExpectedRows(),
        recoveryTransferRows = dao.recoveryTransferRows(),
        recoveryTagRows = dao.recoveryTagRows(),
        recoveryMemberRows = dao.recoveryMemberRows()
    )
}
