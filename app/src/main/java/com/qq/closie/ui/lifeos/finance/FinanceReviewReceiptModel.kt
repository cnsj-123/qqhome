package com.qq.closie.ui.lifeos.finance

import com.qq.closie.life.finance.*

/** Disposable presentation of one proposal; never stored, never changes canonical money. */
internal data class FinanceReviewReceiptModel(
    val proposalId: String, val revision: Long, val amountMinor: Long?, val direction: FinanceProposalKind,
    val occurredAt: Long, val source: String, val accountName: String?, val categoryPath: String?,
    val safeDescription: String, val nature: FinanceNature, val role: FinanceFlowRole,
    val ambiguityReason: String?, val confirmation: FinanceProposalConfirmation?
) {
    val directlyConfirmable get() = confirmation != null
    companion object {
        fun from(proposal: FinanceProposalEntity, snapshot: FinanceSnapshot, tags: List<String> = emptyList(),
            userDescriptions: Set<String> = emptySet(), draft: FinanceEntryDraft? = null,
            reviewedDuplicateIds: Set<String> = emptySet()): FinanceReviewReceiptModel {
            val safe = FinanceProposalPrivacy.sanitize(proposal, userDescriptions)
            val tree = FinanceCategoryTree(snapshot.categories)
            val content = draft ?: FinanceEntryDraft(FinanceEditorKind.valueOf(proposal.kind.name),
                proposal.amountMinor?.let { FinanceMoney.input(it) }.orEmpty(), proposal.accountId.orEmpty(),
                proposal.targetAccountId.orEmpty(), FinanceTime.input(proposal.occurredAt), safe.description,
                proposal.category, tags.joinToString("，"), proposal.occurredAt, proposal.subcategory,
                FinanceRelationshipDraft.fromProposal(proposal), tree.find(proposal.category, proposal.subcategory)?.id)
            val account = snapshot.accounts.find { it.id == content.accountId && it.archivedAt == null && it.currencyCode == "CNY" }
            val category = tree.node(content.categoryId) ?: tree.find(content.category, content.subcategory)
            val amount = runCatching { FinanceCalculator.evaluate(content.amount) }.getOrNull()
            val occurred = runCatching { FinanceTime.resolve(content.occurredAt, content.preservedOccurredAt) }.getOrNull()
            val event = content.relationship.eventId?.let { id -> snapshot.v4?.events?.find { it.id == id && it.voidedAt == null } }
            val duplicates = amount?.let { FinanceDuplicateReview.candidates(proposal.id, FinanceProposalKind.valueOf(content.kind.name),
                it, occurred ?: proposal.occurredAt, snapshot.v4?.proposals.orEmpty(), snapshot) }.orEmpty()
            val reason = when {
                FinanceEvidenceCorrelation.blocking(proposal, snapshot.v4?.proposals.orEmpty(),snapshot.v16).isNotEmpty() -> "付款编号可能重复或冲突，请核对来源身份；同一笔请忽略重复小票"
                !reviewedDuplicateIds.containsAll(duplicates) -> "存在相近记录，请打开完整小票核对；同一笔请忽略，其他交易须明确确认"
                proposal.status !in setOf(FinanceProposalStatus.ACTIVE, FinanceProposalStatus.SNOOZED) -> "这笔已处理，请重新打开"
                FinanceEvidenceOrigin.requiresManualAccount(proposal) && proposal.role == FinanceFlowRole.REFUND &&
                    (content.kind != FinanceEditorKind.INCOME || content.relationship.role != FinanceFlowRole.REFUND) -> "退款须保留退款性质并关联原资金事项"
                FinanceEvidenceOrigin.requiresManualAccount(proposal) && proposal.kind == FinanceProposalKind.TRANSFER &&
                    content.kind != FinanceEditorKind.TRANSFER -> "还款候选须核对两端账户"
                amount == null || amount <= 0 -> "金额需要核对"
                occurred == null -> "时间需要核对"
                account == null -> "需要选择账户"
                content.kind == FinanceEditorKind.TRANSFER -> "转账需要核对两端账户"
                category == null -> "需要选择分类"
                content.relationship.role in setOf(FinanceFlowRole.REFUND, FinanceFlowRole.REIMBURSEMENT, FinanceFlowRole.SETTLEMENT,
                    FinanceFlowRole.REBATE, FinanceFlowRole.CASHBACK) && event == null -> "请选择对应的资金事项"
                else -> null
            }
            val confirmation = if (reason != null) null else runCatching {
                content.copy(categoryId = category!!.id).toProposalConfirmation().copy(reviewedDuplicateIds = reviewedDuplicateIds)
            }.getOrNull()
            return FinanceReviewReceiptModel(proposal.id, proposal.revision, amount, FinanceProposalKind.valueOf(content.kind.name),
                occurred ?: proposal.occurredAt, proposal.discoverySourceLabel() + " · " + FinanceEvidenceOrigin.label(proposal),
                account?.name, tree.fullPath(category?.id), content.description,
                content.relationship.nature, content.relationship.role, reason ?: if (confirmation == null) "关系信息需要完善" else null, confirmation)
        }
    }
}

/** Shared command translation for editable receipts and the default overlay; repository validates truth. */
internal fun FinanceEntryDraft.toProposalConfirmation() = FinanceProposalConfirmation(
    FinanceProposalKind.valueOf(kind.name), accountId, targetAccountId.ifBlank { null },
    FinanceCalculator.evaluate(amount), description, FinanceTime.resolve(occurredAt, preservedOccurredAt),
    category, subcategory, tags.split('，', ',').map(String::trim).filter(String::isNotBlank),
    relationship.resolve("CNY"), categoryId,personalReductionMinor=costReductionText.takeIf(String::isNotBlank)?.let {FinanceMoney.parse(it,"CNY",true)})
