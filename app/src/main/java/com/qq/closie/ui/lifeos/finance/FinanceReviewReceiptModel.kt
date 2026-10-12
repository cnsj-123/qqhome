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
            userDescriptions: Set<String> = emptySet()): FinanceReviewReceiptModel {
            val safe = FinanceProposalPrivacy.sanitize(proposal, userDescriptions)
            val account = snapshot.accounts.find { it.id == proposal.accountId && it.archivedAt == null && it.currencyCode == "CNY" }
            val category = snapshot.categories.find { it.name == proposal.category && it.parentId == null }
                ?.let { root -> if (proposal.subcategory.isBlank()) root else snapshot.categories.find {
                    it.parentId == root.id && it.name == proposal.subcategory } }
            val reason = when {
                proposal.status !in setOf(FinanceProposalStatus.ACTIVE, FinanceProposalStatus.SNOOZED) -> "这笔已处理，请重新打开"
                proposal.amountMinor == null || proposal.amountMinor <= 0 -> "金额需要核对"
                account == null -> "需要选择账户"
                proposal.kind == FinanceProposalKind.TRANSFER -> "转账需要核对两端账户"
                category == null -> "需要选择分类"
                proposal.role in setOf(FinanceFlowRole.REFUND, FinanceFlowRole.REIMBURSEMENT, FinanceFlowRole.SETTLEMENT,
                    FinanceFlowRole.REBATE, FinanceFlowRole.CASHBACK) -> "请选择对应的资金事项"
                else -> null
            }
            val confirmation = if (reason != null) null else FinanceProposalConfirmation(proposal.kind, account!!.id,
                null, proposal.amountMinor!!, safe.description, proposal.occurredAt, proposal.category,
                proposal.subcategory, tags, FinanceEventInput(nature = proposal.nature, role = proposal.role))
            return FinanceReviewReceiptModel(proposal.id, proposal.revision, proposal.amountMinor, proposal.kind,
                proposal.occurredAt, when (proposal.source) { FinanceSource.WECHAT -> "微信"; FinanceSource.ALIPAY -> "支付宝"; else -> "导入" },
                account?.name, FinanceProjection.categoryPath(snapshot.categories, category?.id), safe.description,
                proposal.nature, proposal.role, reason, confirmation)
        }
    }
}
