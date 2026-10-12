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
            userDescriptions: Set<String> = emptySet(), draft: FinanceEntryDraft? = null): FinanceReviewReceiptModel {
            val safe = FinanceProposalPrivacy.sanitize(proposal, userDescriptions)
            val tree = FinanceCategoryTree(snapshot.categories)
            val content = draft ?: FinanceEntryDraft(FinanceEditorKind.valueOf(proposal.kind.name),
                proposal.amountMinor?.let { FinanceMoney.input(it) }.orEmpty(), proposal.accountId.orEmpty(),
                proposal.targetAccountId.orEmpty(), FinanceTime.input(proposal.occurredAt), safe.description,
                proposal.category, tags.joinToString("，"), proposal.occurredAt, proposal.subcategory,
                FinanceRelationshipDraft.fromProposal(proposal), tree.find(proposal.category, proposal.subcategory)?.id)
            val account = snapshot.accounts.find { it.id == content.accountId && it.archivedAt == null && it.currencyCode == "CNY" }
            val category = tree.node(content.categoryId) ?: tree.find(content.category, content.subcategory)
            val amount = runCatching { FinanceMoney.parse(content.amount) }.getOrNull()
            val occurred = runCatching { FinanceTime.resolve(content.occurredAt, content.preservedOccurredAt) }.getOrNull()
            val event = content.relationship.eventId?.let { id -> snapshot.v4?.events?.find { it.id == id && it.voidedAt == null } }
            val reason = when {
                proposal.status !in setOf(FinanceProposalStatus.ACTIVE, FinanceProposalStatus.SNOOZED) -> "这笔已处理，请重新打开"
                amount == null || amount <= 0 -> "金额需要核对"
                occurred == null -> "时间需要核对"
                account == null -> "需要选择账户"
                content.kind == FinanceEditorKind.TRANSFER -> "转账需要核对两端账户"
                category == null -> "需要选择分类"
                content.description.isBlank() -> "请补充事由"
                content.relationship.role in setOf(FinanceFlowRole.REFUND, FinanceFlowRole.REIMBURSEMENT, FinanceFlowRole.SETTLEMENT,
                    FinanceFlowRole.REBATE, FinanceFlowRole.CASHBACK) && event == null -> "请选择对应的资金事项"
                else -> null
            }
            val confirmation = if (reason != null) null else runCatching {
                content.copy(categoryId = category!!.id).toProposalConfirmation()
            }.getOrNull()
            return FinanceReviewReceiptModel(proposal.id, proposal.revision, amount, FinanceProposalKind.valueOf(content.kind.name),
                occurred ?: proposal.occurredAt, when (proposal.source) { FinanceSource.WECHAT -> "微信"; FinanceSource.ALIPAY -> "支付宝"; else -> "导入" },
                account?.name, tree.fullPath(category?.id), content.description,
                content.relationship.nature, content.relationship.role, reason ?: if (confirmation == null) "关系信息需要完善" else null, confirmation)
        }
    }
}

/** Shared command translation for editable receipts and the default overlay; repository validates truth. */
internal fun FinanceEntryDraft.toProposalConfirmation() = FinanceProposalConfirmation(
    FinanceProposalKind.valueOf(kind.name), accountId, targetAccountId.ifBlank { null },
    FinanceMoney.parse(amount), description, FinanceTime.resolve(occurredAt, preservedOccurredAt),
    category, subcategory, tags.split('，', ',').map(String::trim).filter(String::isNotBlank),
    relationship.resolve("CNY"), categoryId)
