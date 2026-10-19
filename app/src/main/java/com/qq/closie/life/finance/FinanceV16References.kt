package com.qq.closie.life.finance

/** Checks are split per table to keep JVM method bytecode bounded. */
internal object FinanceV16References {
    fun validate(s: FinanceSnapshot, v: FinanceV16Snapshot) {
        checkFinancePartyEntity(s,v)
        checkFinanceEconomicEntity(s,v)
        checkFinanceFundingEntity(s,v)
        checkFinanceShareEntity(s,v)
        checkFinanceObligationEntity(s,v)
        checkFinanceSettlementEntity(s,v)
        checkFinanceResolutionEntity(s,v)
        checkShoppingPurchaseEntity(s,v)
        checkShoppingPurchaseLineEntity(s,v)
        checkFinancePurchaseAllocationEntity(s,v)
        checkFinanceCostAdjustmentEntity(s,v)
        checkFinanceEntryTimingEntity(s,v)
        checkFinanceAccountPolicyEntity(s,v)
        checkFinanceObservationEntity(s,v)
        checkFinanceReconciliationEntity(s,v)
        checkFinanceCreditCycleEntity(s,v)
        checkFinanceDraftEntity(s,v)
        checkFinanceDraftTagEntity(s,v)
        checkFinanceDraftExpectedEntity(s,v)
        checkFinanceEconomicDraftEntity(s,v)
        checkFinanceEconomicDraftFundingEntity(s,v)
        checkFinanceEconomicDraftShareEntity(s,v)
        checkFinanceCategoryIconEntity(s,v)
        checkFinanceSavedQueryEntity(s,v)
        checkFinanceBudgetEntity(s,v)
        checkFinanceCategoryPolicyEntity(s,v)
        checkFinanceExternalIdentityEntity(s,v)
        checkFinanceInstrumentEntity(s,v)
        checkFinanceAccountBindingEntity(s,v)
        checkFinanceEvidenceEntity(s,v)
        checkFinanceExtractionEntity(s,v)
        checkFinanceEvidenceProposalEntity(s,v)
        checkFinanceMediaLinkEntity(s,v)
        checkFinanceReviewSessionEntity(s,v)
        checkFinanceReviewExpectedEntity(s,v)
        checkFinancePurposeGrantEntity(s,v)
        checkFinanceGrantEventEntity(s,v)
        checkFinanceGrantEntryEntity(s,v)
        checkFinanceCommandReceiptEntity(s,v)
        checkFinanceControlEntity(s,v)
        checkFinanceRecoveryGroupEntity(s,v)
        checkFinanceRecoveryEntryEntity(s,v)
        checkFinanceRecoveryLinkEntity(s,v)
        checkFinanceRecoveryEventEntity(s,v)
        checkFinanceRecoveryExpectedEntity(s,v)
        checkFinanceRecoveryTransferEntity(s,v)
        checkFinanceRecoveryTagEntity(s,v)
        checkFinanceRecoveryMemberEntity(s,v)
    }
    private fun checkFinancePartyEntity(s: FinanceSnapshot, v: FinanceV16Snapshot) {
        require(v.partyRows.map { listOf(it.id) }.let { keys -> keys.distinct().size == keys.size && keys.none { key -> key.any { it == null || (it is String && it.isBlank()) } } }) { "finance_parties: 身份重复或缺失" }


    }

    private fun checkFinanceEconomicEntity(s: FinanceSnapshot, v: FinanceV16Snapshot) {
        require(v.economicRows.map { listOf(it.eventId) }.let { keys -> keys.distinct().size == keys.size && keys.none { key -> key.any { it == null || (it is String && it.isBlank()) } } }) { "finance_economics: 身份重复或缺失" }
        val economicRows_eventId = (s.v4?.events.orEmpty()).map { it.id }.toSet()
        require(v.economicRows.all { row -> row.eventId == null || row.eventId in economicRows_eventId }) { "finance_economics: eventId 引用缺失" }
        val economicRows_categoryId = (s.categories).map { it.id }.toSet()
        require(v.economicRows.all { row -> row.categoryId == null || row.categoryId in economicRows_categoryId }) { "finance_economics: categoryId 引用缺失" }

    }

    private fun checkFinanceFundingEntity(s: FinanceSnapshot, v: FinanceV16Snapshot) {
        require(v.fundingRows.map { listOf(it.id) }.let { keys -> keys.distinct().size == keys.size && keys.none { key -> key.any { it == null || (it is String && it.isBlank()) } } }) { "finance_funding: 身份重复或缺失" }
        val fundingRows_eventId = (s.v4?.events.orEmpty()).map { it.id }.toSet()
        require(v.fundingRows.all { row -> row.eventId == null || row.eventId in fundingRows_eventId }) { "finance_funding: eventId 引用缺失" }
        val fundingRows_partyId = (v.partyRows).map { it.id }.toSet()
        require(v.fundingRows.all { row -> row.partyId == null || row.partyId in fundingRows_partyId }) { "finance_funding: partyId 引用缺失" }
        val fundingRows_entryLinkId = (s.v4?.links.orEmpty()).map { it.id }.toSet()
        require(v.fundingRows.all { row -> row.entryLinkId == null || row.entryLinkId in fundingRows_entryLinkId }) { "finance_funding: entryLinkId 引用缺失" }

    }

    private fun checkFinanceShareEntity(s: FinanceSnapshot, v: FinanceV16Snapshot) {
        require(v.shareRows.map { listOf(it.id) }.let { keys -> keys.distinct().size == keys.size && keys.none { key -> key.any { it == null || (it is String && it.isBlank()) } } }) { "finance_shares: 身份重复或缺失" }
        val shareRows_eventId = (s.v4?.events.orEmpty()).map { it.id }.toSet()
        require(v.shareRows.all { row -> row.eventId == null || row.eventId in shareRows_eventId }) { "finance_shares: eventId 引用缺失" }
        val shareRows_beneficiaryId = (v.partyRows).map { it.id }.toSet()
        require(v.shareRows.all { row -> row.beneficiaryId == null || row.beneficiaryId in shareRows_beneficiaryId }) { "finance_shares: beneficiaryId 引用缺失" }
        val shareRows_bearerId = (v.partyRows).map { it.id }.toSet()
        require(v.shareRows.all { row -> row.bearerId == null || row.bearerId in shareRows_bearerId }) { "finance_shares: bearerId 引用缺失" }
        val shareRows_fundingId = (v.fundingRows).map { it.id }.toSet()
        require(v.shareRows.all { row -> row.fundingId == null || row.fundingId in shareRows_fundingId }) { "finance_shares: fundingId 引用缺失" }
        require(v.shareRows.all { it.relation in FinanceShareRelation.entries })
    }

    private fun checkFinanceObligationEntity(s: FinanceSnapshot, v: FinanceV16Snapshot) {
        require(v.obligationRows.map { listOf(it.id) }.let { keys -> keys.distinct().size == keys.size && keys.none { key -> key.any { it == null || (it is String && it.isBlank()) } } }) { "finance_obligations: 身份重复或缺失" }
        val obligationRows_eventId = (s.v4?.events.orEmpty()).map { it.id }.toSet()
        require(v.obligationRows.all { row -> row.eventId == null || row.eventId in obligationRows_eventId }) { "finance_obligations: eventId 引用缺失" }
        val obligationRows_shareId = (v.shareRows).map { it.id }.toSet()
        require(v.obligationRows.all { row -> row.shareId == null || row.shareId in obligationRows_shareId }) { "finance_obligations: shareId 引用缺失" }
        val obligationRows_creditorId = (v.partyRows).map { it.id }.toSet()
        require(v.obligationRows.all { row -> row.creditorId == null || row.creditorId in obligationRows_creditorId }) { "finance_obligations: creditorId 引用缺失" }
        val obligationRows_debtorId = (v.partyRows).map { it.id }.toSet()
        require(v.obligationRows.all { row -> row.debtorId == null || row.debtorId in obligationRows_debtorId }) { "finance_obligations: debtorId 引用缺失" }

    }

    private fun checkFinanceSettlementEntity(s: FinanceSnapshot, v: FinanceV16Snapshot) {
        require(v.settlementRows.map { listOf(it.id) }.let { keys -> keys.distinct().size == keys.size && keys.none { key -> key.any { it == null || (it is String && it.isBlank()) } } }) { "finance_settlements: 身份重复或缺失" }
        val settlementRows_obligationId = (v.obligationRows).map { it.id }.toSet()
        require(v.settlementRows.all { row -> row.obligationId == null || row.obligationId in settlementRows_obligationId }) { "finance_settlements: obligationId 引用缺失" }
        val settlementRows_entryId = (s.entries).map { it.id }.toSet()
        require(v.settlementRows.all { row -> row.entryId == null || row.entryId in settlementRows_entryId }) { "finance_settlements: entryId 引用缺失" }

    }

    private fun checkFinanceResolutionEntity(s: FinanceSnapshot, v: FinanceV16Snapshot) {
        require(v.resolutionRows.map { listOf(it.id) }.let { keys -> keys.distinct().size == keys.size && keys.none { key -> key.any { it == null || (it is String && it.isBlank()) } } }) { "finance_resolutions: 身份重复或缺失" }
        val resolutionRows_obligationId = (v.obligationRows).map { it.id }.toSet()
        require(v.resolutionRows.all { row -> row.obligationId == null || row.obligationId in resolutionRows_obligationId }) { "finance_resolutions: obligationId 引用缺失" }
        require(v.resolutionRows.all { it.kind in FinanceResolutionKind.entries })
    }

    private fun checkShoppingPurchaseEntity(s: FinanceSnapshot, v: FinanceV16Snapshot) {
        require(v.purchaseRows.map { listOf(it.id) }.let { keys -> keys.distinct().size == keys.size && keys.none { key -> key.any { it == null || (it is String && it.isBlank()) } } }) { "shopping_purchases: 身份重复或缺失" }


    }

    private fun checkShoppingPurchaseLineEntity(s: FinanceSnapshot, v: FinanceV16Snapshot) {
        require(v.purchaseLineRows.map { listOf(it.id) }.let { keys -> keys.distinct().size == keys.size && keys.none { key -> key.any { it == null || (it is String && it.isBlank()) } } }) { "shopping_purchase_lines: 身份重复或缺失" }
        val purchaseLineRows_purchaseId = (v.purchaseRows).map { it.id }.toSet()
        require(v.purchaseLineRows.all { row -> row.purchaseId == null || row.purchaseId in purchaseLineRows_purchaseId }) { "shopping_purchase_lines: purchaseId 引用缺失" }

    }

    private fun checkFinancePurchaseAllocationEntity(s: FinanceSnapshot, v: FinanceV16Snapshot) {
        require(v.purchaseAllocationRows.map { listOf(it.id) }.let { keys -> keys.distinct().size == keys.size && keys.none { key -> key.any { it == null || (it is String && it.isBlank()) } } }) { "finance_purchase_allocations: 身份重复或缺失" }
        val purchaseAllocationRows_lineId = (v.purchaseLineRows).map { it.id }.toSet()
        require(v.purchaseAllocationRows.all { row -> row.lineId == null || row.lineId in purchaseAllocationRows_lineId }) { "finance_purchase_allocations: lineId 引用缺失" }
        val purchaseAllocationRows_eventId = (s.v4?.events.orEmpty()).map { it.id }.toSet()
        require(v.purchaseAllocationRows.all { row -> row.eventId == null || row.eventId in purchaseAllocationRows_eventId }) { "finance_purchase_allocations: eventId 引用缺失" }

    }

    private fun checkFinanceCostAdjustmentEntity(s: FinanceSnapshot, v: FinanceV16Snapshot) {
        require(v.costAdjustmentRows.map { listOf(it.id) }.let { keys -> keys.distinct().size == keys.size && keys.none { key -> key.any { it == null || (it is String && it.isBlank()) } } }) { "finance_cost_adjustments: 身份重复或缺失" }
        val costAdjustmentRows_eventId = (s.v4?.events.orEmpty()).map { it.id }.toSet()
        require(v.costAdjustmentRows.all { row -> row.eventId == null || row.eventId in costAdjustmentRows_eventId }) { "finance_cost_adjustments: eventId 引用缺失" }
        val costAdjustmentRows_entryLinkId = (s.v4?.links.orEmpty()).map { it.id }.toSet()
        require(v.costAdjustmentRows.all { row -> row.entryLinkId == null || row.entryLinkId in costAdjustmentRows_entryLinkId }) { "finance_cost_adjustments: entryLinkId 引用缺失" }
        val costAdjustmentRows_lineId = (v.purchaseLineRows).map { it.id }.toSet()
        require(v.costAdjustmentRows.all { row -> row.lineId == null || row.lineId in costAdjustmentRows_lineId }) { "finance_cost_adjustments: lineId 引用缺失" }

    }

    private fun checkFinanceEntryTimingEntity(s: FinanceSnapshot, v: FinanceV16Snapshot) {
        require(v.entryTimingRows.map { listOf(it.entryId) }.let { keys -> keys.distinct().size == keys.size && keys.none { key -> key.any { it == null || (it is String && it.isBlank()) } } }) { "finance_entry_timing: 身份重复或缺失" }
        val entryTimingRows_entryId = (s.entries).map { it.id }.toSet()
        require(v.entryTimingRows.all { row -> row.entryId == null || row.entryId in entryTimingRows_entryId }) { "finance_entry_timing: entryId 引用缺失" }
        require(v.entryTimingRows.all { it.cashPrecision in FinanceTimePrecision.entries })
        require(v.entryTimingRows.all { it.postedPrecision in FinanceTimePrecision.entries })
    }

    private fun checkFinanceAccountPolicyEntity(s: FinanceSnapshot, v: FinanceV16Snapshot) {
        require(v.accountPolicyRows.map { listOf(it.accountId) }.let { keys -> keys.distinct().size == keys.size && keys.none { key -> key.any { it == null || (it is String && it.isBlank()) } } }) { "finance_account_policies: 身份重复或缺失" }
        val accountPolicyRows_accountId = (s.accounts).map { it.id }.toSet()
        require(v.accountPolicyRows.all { row -> row.accountId == null || row.accountId in accountPolicyRows_accountId }) { "finance_account_policies: accountId 引用缺失" }
        require(v.accountPolicyRows.all { it.liabilityConvention in FinanceLiabilityConvention.entries })
        require(v.accountPolicyRows.all { it.anchorBasis in FinanceBalanceBasis.entries })
    }

    private fun checkFinanceObservationEntity(s: FinanceSnapshot, v: FinanceV16Snapshot) {
        require(v.observationRows.map { listOf(it.id) }.let { keys -> keys.distinct().size == keys.size && keys.none { key -> key.any { it == null || (it is String && it.isBlank()) } } }) { "finance_observations: 身份重复或缺失" }
        val observationRows_accountId = (s.accounts).map { it.id }.toSet()
        require(v.observationRows.all { row -> row.accountId == null || row.accountId in observationRows_accountId }) { "finance_observations: accountId 引用缺失" }
        require(v.observationRows.all { it.basis in FinanceBalanceBasis.entries })
        require(v.observationRows.all { it.precision in FinanceTimePrecision.entries })
    }

    private fun checkFinanceReconciliationEntity(s: FinanceSnapshot, v: FinanceV16Snapshot) {
        require(v.reconciliationRows.map { listOf(it.id) }.let { keys -> keys.distinct().size == keys.size && keys.none { key -> key.any { it == null || (it is String && it.isBlank()) } } }) { "finance_reconciliations: 身份重复或缺失" }
        val reconciliationRows_observationId = (v.observationRows).map { it.id }.toSet()
        require(v.reconciliationRows.all { row -> row.observationId == null || row.observationId in reconciliationRows_observationId }) { "finance_reconciliations: observationId 引用缺失" }

    }

    private fun checkFinanceCreditCycleEntity(s: FinanceSnapshot, v: FinanceV16Snapshot) {
        require(v.creditCycleRows.map { listOf(it.accountId) }.let { keys -> keys.distinct().size == keys.size && keys.none { key -> key.any { it == null || (it is String && it.isBlank()) } } }) { "finance_credit_cycles: 身份重复或缺失" }
        val creditCycleRows_accountId = (s.accounts).map { it.id }.toSet()
        require(v.creditCycleRows.all { row -> row.accountId == null || row.accountId in creditCycleRows_accountId }) { "finance_credit_cycles: accountId 引用缺失" }

    }

    private fun checkFinanceDraftEntity(s: FinanceSnapshot, v: FinanceV16Snapshot) {
        require(v.draftRows.map { listOf(it.id) }.let { keys -> keys.distinct().size == keys.size && keys.none { key -> key.any { it == null || (it is String && it.isBlank()) } } }) { "finance_drafts: 身份重复或缺失" }

        require(v.draftRows.all { it.kind in FinanceDraftKind.entries })
    }

    private fun checkFinanceDraftTagEntity(s: FinanceSnapshot, v: FinanceV16Snapshot) {
        require(v.draftTagRows.map { listOf(it.draftId, it.name) }.let { keys -> keys.distinct().size == keys.size && keys.none { key -> key.any { it == null || (it is String && it.isBlank()) } } }) { "finance_draft_tags: 身份重复或缺失" }
        val draftTagRows_draftId = (v.draftRows).map { it.id }.toSet()
        require(v.draftTagRows.all { row -> row.draftId == null || row.draftId in draftTagRows_draftId }) { "finance_draft_tags: draftId 引用缺失" }

    }

    private fun checkFinanceDraftExpectedEntity(s: FinanceSnapshot, v: FinanceV16Snapshot) {
        require(v.draftExpectedRows.map { listOf(it.draftId, it.position) }.let { keys -> keys.distinct().size == keys.size && keys.none { key -> key.any { it == null || (it is String && it.isBlank()) } } }) { "finance_draft_expected: 身份重复或缺失" }
        val draftExpectedRows_draftId = (v.draftRows).map { it.id }.toSet()
        require(v.draftExpectedRows.all { row -> row.draftId == null || row.draftId in draftExpectedRows_draftId }) { "finance_draft_expected: draftId 引用缺失" }

    }

    private fun checkFinanceEconomicDraftEntity(s: FinanceSnapshot, v: FinanceV16Snapshot) {
        require(v.economicDraftRows.map { listOf(it.id) }.let { keys -> keys.distinct().size == keys.size && keys.none { key -> key.any { it == null || (it is String && it.isBlank()) } } }) { "finance_economic_drafts: 身份重复或缺失" }
        val economicDraftRows_eventId = (s.v4?.events.orEmpty()).map { it.id }.toSet()
        require(v.economicDraftRows.all { row -> row.eventId == null || row.eventId in economicDraftRows_eventId }) { "finance_economic_drafts: eventId 引用缺失" }
        val economicDraftRows_categoryId = (s.categories).map { it.id }.toSet()
        require(v.economicDraftRows.all { row -> row.categoryId == null || row.categoryId in economicDraftRows_categoryId }) { "finance_economic_drafts: categoryId 引用缺失" }

    }

    private fun checkFinanceEconomicDraftFundingEntity(s: FinanceSnapshot, v: FinanceV16Snapshot) {
        require(v.economicDraftFundingRows.map { listOf(it.draftId, it.localId) }.let { keys -> keys.distinct().size == keys.size && keys.none { key -> key.any { it == null || (it is String && it.isBlank()) } } }) { "finance_economic_draft_funding: 身份重复或缺失" }
        val economicDraftFundingRows_draftId = (v.economicDraftRows).map { it.id }.toSet()
        require(v.economicDraftFundingRows.all { row -> row.draftId == null || row.draftId in economicDraftFundingRows_draftId }) { "finance_economic_draft_funding: draftId 引用缺失" }

    }

    private fun checkFinanceEconomicDraftShareEntity(s: FinanceSnapshot, v: FinanceV16Snapshot) {
        require(v.economicDraftShareRows.map { listOf(it.draftId, it.localId) }.let { keys -> keys.distinct().size == keys.size && keys.none { key -> key.any { it == null || (it is String && it.isBlank()) } } }) { "finance_economic_draft_shares: 身份重复或缺失" }
        val economicDraftShareRows_draftId = (v.economicDraftRows).map { it.id }.toSet()
        require(v.economicDraftShareRows.all { row -> row.draftId == null || row.draftId in economicDraftShareRows_draftId }) { "finance_economic_draft_shares: draftId 引用缺失" }
        require(v.economicDraftShareRows.all { it.relation in FinanceShareRelation.entries })
    }

    private fun checkFinanceCategoryIconEntity(s: FinanceSnapshot, v: FinanceV16Snapshot) {
        require(v.categoryIconRows.map { listOf(it.categoryId) }.let { keys -> keys.distinct().size == keys.size && keys.none { key -> key.any { it == null || (it is String && it.isBlank()) } } }) { "finance_category_icons: 身份重复或缺失" }
        val categoryIconRows_categoryId = (s.categories).map { it.id }.toSet()
        require(v.categoryIconRows.all { row -> row.categoryId == null || row.categoryId in categoryIconRows_categoryId }) { "finance_category_icons: categoryId 引用缺失" }

    }

    private fun checkFinanceSavedQueryEntity(s: FinanceSnapshot, v: FinanceV16Snapshot) {
        require(v.savedQueryRows.map { listOf(it.id) }.let { keys -> keys.distinct().size == keys.size && keys.none { key -> key.any { it == null || (it is String && it.isBlank()) } } }) { "finance_saved_queries: 身份重复或缺失" }


    }

    private fun checkFinanceBudgetEntity(s: FinanceSnapshot, v: FinanceV16Snapshot) {
        require(v.budgetRows.map { listOf(it.id) }.let { keys -> keys.distinct().size == keys.size && keys.none { key -> key.any { it == null || (it is String && it.isBlank()) } } }) { "finance_budgets: 身份重复或缺失" }
        val budgetRows_categoryId = (s.categories).map { it.id }.toSet()
        require(v.budgetRows.all { row -> row.categoryId == null || row.categoryId in budgetRows_categoryId }) { "finance_budgets: categoryId 引用缺失" }

    }

    private fun checkFinanceCategoryPolicyEntity(s: FinanceSnapshot, v: FinanceV16Snapshot) {
        require(v.categoryPolicyRows.map { listOf(it.categoryId) }.let { keys -> keys.distinct().size == keys.size && keys.none { key -> key.any { it == null || (it is String && it.isBlank()) } } }) { "finance_category_policies: 身份重复或缺失" }
        val categoryPolicyRows_categoryId = (s.categories).map { it.id }.toSet()
        require(v.categoryPolicyRows.all { row -> row.categoryId == null || row.categoryId in categoryPolicyRows_categoryId }) { "finance_category_policies: categoryId 引用缺失" }
        require(v.categoryPolicyRows.all { it.burden in FinanceBurden.entries })
    }

    private fun checkFinanceExternalIdentityEntity(s: FinanceSnapshot, v: FinanceV16Snapshot) {
        require(v.externalIdentityRows.map { listOf(it.id) }.let { keys -> keys.distinct().size == keys.size && keys.none { key -> key.any { it == null || (it is String && it.isBlank()) } } }) { "finance_external_identities: 身份重复或缺失" }


    }

    private fun checkFinanceInstrumentEntity(s: FinanceSnapshot, v: FinanceV16Snapshot) {
        require(v.instrumentRows.map { listOf(it.id) }.let { keys -> keys.distinct().size == keys.size && keys.none { key -> key.any { it == null || (it is String && it.isBlank()) } } }) { "finance_instruments: 身份重复或缺失" }
        val instrumentRows_identityId = (v.externalIdentityRows).map { it.id }.toSet()
        require(v.instrumentRows.all { row -> row.identityId == null || row.identityId in instrumentRows_identityId }) { "finance_instruments: identityId 引用缺失" }

    }

    private fun checkFinanceAccountBindingEntity(s: FinanceSnapshot, v: FinanceV16Snapshot) {
        require(v.accountBindingRows.map { listOf(it.id) }.let { keys -> keys.distinct().size == keys.size && keys.none { key -> key.any { it == null || (it is String && it.isBlank()) } } }) { "finance_account_bindings: 身份重复或缺失" }
        val accountBindingRows_instrumentId = (v.instrumentRows).map { it.id }.toSet()
        require(v.accountBindingRows.all { row -> row.instrumentId == null || row.instrumentId in accountBindingRows_instrumentId }) { "finance_account_bindings: instrumentId 引用缺失" }
        val accountBindingRows_accountId = (s.accounts).map { it.id }.toSet()
        require(v.accountBindingRows.all { row -> row.accountId == null || row.accountId in accountBindingRows_accountId }) { "finance_account_bindings: accountId 引用缺失" }

    }

    private fun checkFinanceEvidenceEntity(s: FinanceSnapshot, v: FinanceV16Snapshot) {
        require(v.evidenceRows.map { listOf(it.id) }.let { keys -> keys.distinct().size == keys.size && keys.none { key -> key.any { it == null || (it is String && it.isBlank()) } } }) { "finance_evidence: 身份重复或缺失" }
        val evidenceRows_identityId = (v.externalIdentityRows).map { it.id }.toSet()
        require(v.evidenceRows.all { row -> row.identityId == null || row.identityId in evidenceRows_identityId }) { "finance_evidence: identityId 引用缺失" }
        val evidenceRows_instrumentId = (v.instrumentRows).map { it.id }.toSet()
        require(v.evidenceRows.all { row -> row.instrumentId == null || row.instrumentId in evidenceRows_instrumentId }) { "finance_evidence: instrumentId 引用缺失" }
        val evidenceRows_bindingId = (v.accountBindingRows).map { it.id }.toSet()
        require(v.evidenceRows.all { row -> row.bindingId == null || row.bindingId in evidenceRows_bindingId }) { "finance_evidence: bindingId 引用缺失" }
        require(v.evidenceRows.all { it.referenceKind in FinanceReferenceKind.entries })
    }

    private fun checkFinanceExtractionEntity(s: FinanceSnapshot, v: FinanceV16Snapshot) {
        require(v.extractionRows.map { listOf(it.id) }.let { keys -> keys.distinct().size == keys.size && keys.none { key -> key.any { it == null || (it is String && it.isBlank()) } } }) { "finance_extractions: 身份重复或缺失" }
        val extractionRows_evidenceId = (v.evidenceRows).map { it.id }.toSet()
        require(v.extractionRows.all { row -> row.evidenceId == null || row.evidenceId in extractionRows_evidenceId }) { "finance_extractions: evidenceId 引用缺失" }
        require(v.extractionRows.all { it.direction == null || it.direction in FinanceDirection.entries })
    }

    private fun checkFinanceEvidenceProposalEntity(s: FinanceSnapshot, v: FinanceV16Snapshot) {
        require(v.evidenceProposalRows.map { listOf(it.evidenceId, it.proposalId) }.let { keys -> keys.distinct().size == keys.size && keys.none { key -> key.any { it == null || (it is String && it.isBlank()) } } }) { "finance_evidence_proposals: 身份重复或缺失" }
        val evidenceProposalRows_evidenceId = (v.evidenceRows).map { it.id }.toSet()
        require(v.evidenceProposalRows.all { row -> row.evidenceId == null || row.evidenceId in evidenceProposalRows_evidenceId }) { "finance_evidence_proposals: evidenceId 引用缺失" }
        val evidenceProposalRows_proposalId = (s.v4?.proposals.orEmpty()).map { it.id }.toSet()
        require(v.evidenceProposalRows.all { row -> row.proposalId == null || row.proposalId in evidenceProposalRows_proposalId }) { "finance_evidence_proposals: proposalId 引用缺失" }

    }

    private fun checkFinanceMediaLinkEntity(s: FinanceSnapshot, v: FinanceV16Snapshot) {
        require(v.mediaLinkRows.map { listOf(it.id) }.let { keys -> keys.distinct().size == keys.size && keys.none { key -> key.any { it == null || (it is String && it.isBlank()) } } }) { "finance_media_links: 身份重复或缺失" }
        val mediaLinkRows_eventId = (s.v4?.events.orEmpty()).map { it.id }.toSet()
        require(v.mediaLinkRows.all { row -> row.eventId == null || row.eventId in mediaLinkRows_eventId }) { "finance_media_links: eventId 引用缺失" }
        val mediaLinkRows_purchaseId = (v.purchaseRows).map { it.id }.toSet()
        require(v.mediaLinkRows.all { row -> row.purchaseId == null || row.purchaseId in mediaLinkRows_purchaseId }) { "finance_media_links: purchaseId 引用缺失" }
        val mediaLinkRows_proposalId = (s.v4?.proposals.orEmpty()).map { it.id }.toSet()
        require(v.mediaLinkRows.all { row -> row.proposalId == null || row.proposalId in mediaLinkRows_proposalId }) { "finance_media_links: proposalId 引用缺失" }

    }

    private fun checkFinanceReviewSessionEntity(s: FinanceSnapshot, v: FinanceV16Snapshot) {
        require(v.reviewSessionRows.map { listOf(it.proposalId) }.let { keys -> keys.distinct().size == keys.size && keys.none { key -> key.any { it == null || (it is String && it.isBlank()) } } }) { "finance_review_sessions: 身份重复或缺失" }
        val reviewSessionRows_proposalId = (s.v4?.proposals.orEmpty()).map { it.id }.toSet()
        require(v.reviewSessionRows.all { row -> row.proposalId == null || row.proposalId in reviewSessionRows_proposalId }) { "finance_review_sessions: proposalId 引用缺失" }
        require(v.reviewSessionRows.all { it.state in FinanceReviewState.entries })
        require(v.reviewSessionRows.all { it.kind in FinanceDraftKind.entries })
    }

    private fun checkFinanceReviewExpectedEntity(s: FinanceSnapshot, v: FinanceV16Snapshot) {
        require(v.reviewExpectedRows.map { listOf(it.proposalId, it.position) }.let { keys -> keys.distinct().size == keys.size && keys.none { key -> key.any { it == null || (it is String && it.isBlank()) } } }) { "finance_review_expected: 身份重复或缺失" }
        val reviewExpectedRows_proposalId = (v.reviewSessionRows).map { it.proposalId }.toSet()
        require(v.reviewExpectedRows.all { row -> row.proposalId == null || row.proposalId in reviewExpectedRows_proposalId }) { "finance_review_expected: proposalId 引用缺失" }

    }

    private fun checkFinancePurposeGrantEntity(s: FinanceSnapshot, v: FinanceV16Snapshot) {
        require(v.purposeGrantRows.map { listOf(it.id) }.let { keys -> keys.distinct().size == keys.size && keys.none { key -> key.any { it == null || (it is String && it.isBlank()) } } }) { "finance_purpose_grants: 身份重复或缺失" }

        require(v.purposeGrantRows.all { it.purpose in FinanceReadPurpose.entries })
    }

    private fun checkFinanceGrantEventEntity(s: FinanceSnapshot, v: FinanceV16Snapshot) {
        require(v.grantEventRows.map { listOf(it.grantId, it.eventId) }.let { keys -> keys.distinct().size == keys.size && keys.none { key -> key.any { it == null || (it is String && it.isBlank()) } } }) { "finance_grant_events: 身份重复或缺失" }
        val grantEventRows_grantId = (v.purposeGrantRows).map { it.id }.toSet()
        require(v.grantEventRows.all { row -> row.grantId == null || row.grantId in grantEventRows_grantId }) { "finance_grant_events: grantId 引用缺失" }
        val grantEventRows_eventId = (s.v4?.events.orEmpty()).map { it.id }.toSet()
        require(v.grantEventRows.all { row -> row.eventId == null || row.eventId in grantEventRows_eventId }) { "finance_grant_events: eventId 引用缺失" }

    }

    private fun checkFinanceGrantEntryEntity(s: FinanceSnapshot, v: FinanceV16Snapshot) {
        require(v.grantEntryRows.map { listOf(it.grantId, it.entryId) }.let { keys -> keys.distinct().size == keys.size && keys.none { key -> key.any { it == null || (it is String && it.isBlank()) } } }) { "finance_grant_entries: 身份重复或缺失" }
        val grantEntryRows_grantId = (v.purposeGrantRows).map { it.id }.toSet()
        require(v.grantEntryRows.all { row -> row.grantId == null || row.grantId in grantEntryRows_grantId }) { "finance_grant_entries: grantId 引用缺失" }
        val grantEntryRows_entryId = (s.entries).map { it.id }.toSet()
        require(v.grantEntryRows.all { row -> row.entryId == null || row.entryId in grantEntryRows_entryId }) { "finance_grant_entries: entryId 引用缺失" }

    }

    private fun checkFinanceCommandReceiptEntity(s: FinanceSnapshot, v: FinanceV16Snapshot) {
        require(v.commandReceiptRows.map { listOf(it.operationId) }.let { keys -> keys.distinct().size == keys.size && keys.none { key -> key.any { it == null || (it is String && it.isBlank()) } } }) { "finance_command_receipts: 身份重复或缺失" }


    }

    private fun checkFinanceControlEntity(s: FinanceSnapshot, v: FinanceV16Snapshot) {
        require(v.controlRows.map { listOf(it.id) }.let { keys -> keys.distinct().size == keys.size }) { "finance_control: 身份重复或缺失" }


    }

    private fun checkFinanceRecoveryGroupEntity(s: FinanceSnapshot, v: FinanceV16Snapshot) {
        require(v.recoveryGroupRows.map { listOf(it.id) }.let { keys -> keys.distinct().size == keys.size && keys.none { key -> key.any { it == null || (it is String && it.isBlank()) } } }) { "finance_recovery_groups: 身份重复或缺失" }


    }

    private fun checkFinanceRecoveryEntryEntity(s: FinanceSnapshot, v: FinanceV16Snapshot) {
        require(v.recoveryEntryRows.map { listOf(it.groupId, it.entryId) }.let { keys -> keys.distinct().size == keys.size && keys.none { key -> key.any { it == null || (it is String && it.isBlank()) } } }) { "finance_recovery_entries: 身份重复或缺失" }
        val recoveryEntryRows_groupId = (v.recoveryGroupRows).map { it.id }.toSet()
        require(v.recoveryEntryRows.all { row -> row.groupId == null || row.groupId in recoveryEntryRows_groupId }) { "finance_recovery_entries: groupId 引用缺失" }
        require(v.recoveryEntryRows.all { it.direction in FinanceDirection.entries })
    }

    private fun checkFinanceRecoveryLinkEntity(s: FinanceSnapshot, v: FinanceV16Snapshot) {
        require(v.recoveryLinkRows.map { listOf(it.groupId, it.linkId) }.let { keys -> keys.distinct().size == keys.size && keys.none { key -> key.any { it == null || (it is String && it.isBlank()) } } }) { "finance_recovery_links: 身份重复或缺失" }
        val recoveryLinkRows_groupId = (v.recoveryGroupRows).map { it.id }.toSet()
        require(v.recoveryLinkRows.all { row -> row.groupId == null || row.groupId in recoveryLinkRows_groupId }) { "finance_recovery_links: groupId 引用缺失" }

    }

    private fun checkFinanceRecoveryEventEntity(s: FinanceSnapshot, v: FinanceV16Snapshot) {
        require(v.recoveryEventRows.map { listOf(it.groupId, it.eventId) }.let { keys -> keys.distinct().size == keys.size && keys.none { key -> key.any { it == null || (it is String && it.isBlank()) } } }) { "finance_recovery_events: 身份重复或缺失" }
        val recoveryEventRows_groupId = (v.recoveryGroupRows).map { it.id }.toSet()
        require(v.recoveryEventRows.all { row -> row.groupId == null || row.groupId in recoveryEventRows_groupId }) { "finance_recovery_events: groupId 引用缺失" }

    }

    private fun checkFinanceRecoveryExpectedEntity(s: FinanceSnapshot, v: FinanceV16Snapshot) {
        require(v.recoveryExpectedRows.map { listOf(it.groupId, it.expectedId) }.let { keys -> keys.distinct().size == keys.size && keys.none { key -> key.any { it == null || (it is String && it.isBlank()) } } }) { "finance_recovery_expected: 身份重复或缺失" }
        val recoveryExpectedRows_groupId = (v.recoveryGroupRows).map { it.id }.toSet()
        require(v.recoveryExpectedRows.all { row -> row.groupId == null || row.groupId in recoveryExpectedRows_groupId }) { "finance_recovery_expected: groupId 引用缺失" }

    }

    private fun checkFinanceRecoveryTransferEntity(s: FinanceSnapshot, v: FinanceV16Snapshot) {
        require(v.recoveryTransferRows.map { listOf(it.groupId, it.transferId) }.let { keys -> keys.distinct().size == keys.size && keys.none { key -> key.any { it == null || (it is String && it.isBlank()) } } }) { "finance_recovery_transfers: 身份重复或缺失" }
        val recoveryTransferRows_groupId = (v.recoveryGroupRows).map { it.id }.toSet()
        require(v.recoveryTransferRows.all { row -> row.groupId == null || row.groupId in recoveryTransferRows_groupId }) { "finance_recovery_transfers: groupId 引用缺失" }

    }

    private fun checkFinanceRecoveryTagEntity(s: FinanceSnapshot, v: FinanceV16Snapshot) {
        require(v.recoveryTagRows.map { listOf(it.groupId, it.entryId, it.tagId) }.let { keys -> keys.distinct().size == keys.size && keys.none { key -> key.any { it == null || (it is String && it.isBlank()) } } }) { "finance_recovery_tags: 身份重复或缺失" }
        val recoveryTagRows_groupId = (v.recoveryGroupRows).map { it.id }.toSet()
        require(v.recoveryTagRows.all { row -> row.groupId == null || row.groupId in recoveryTagRows_groupId }) { "finance_recovery_tags: groupId 引用缺失" }

    }

    private fun checkFinanceRecoveryMemberEntity(s: FinanceSnapshot, v: FinanceV16Snapshot) {
        require(v.recoveryMemberRows.map { listOf(it.groupId, it.kind, it.memberId) }.let { keys -> keys.distinct().size == keys.size && keys.none { key -> key.any { it == null || (it is String && it.isBlank()) } } }) { "finance_recovery_members: 身份重复或缺失" }
        val recoveryMemberRows_groupId = (v.recoveryGroupRows).map { it.id }.toSet()
        require(v.recoveryMemberRows.all { row -> row.groupId == null || row.groupId in recoveryMemberRows_groupId }) { "finance_recovery_members: groupId 引用缺失" }
        require(v.recoveryMemberRows.all { it.kind in FinanceRecoveryMemberKind.entries })
    }
}
