package com.qq.closie.life.finance

/** Persistence policy for notification proposals. Canonical entries are deliberately outside its scope.
 * Rule descriptions are explicit user-authored suggestions, not notification-derived text. */
object FinanceProposalPrivacy {
    fun sanitize(row: FinanceProposalEntity, userDescriptions: Set<String> = emptySet()): FinanceProposalEntity {
        val source = when (row.source) {
            FinanceSource.WECHAT -> "微信"
            FinanceSource.ALIPAY -> "支付宝"
            FinanceSource.VISIBLE_DETAIL -> "交易"
            FinanceSource.SCREENSHOT -> "主动截图"
            FinanceSource.SELECTED_IMAGE -> "所选图片"
            FinanceSource.LEGACY_IMPORT -> return row
        }
        val action = when {
            row.role == FinanceFlowRole.REFUND -> "退款"
            row.kind == FinanceProposalKind.INCOME -> "收款"
            row.kind == FinanceProposalKind.TRANSFER -> "转账待核对"
            else -> "支出"
        }
        return row.copy(evidenceText = "", description = row.description.takeIf { it in userDescriptions } ?: source + action)
    }

    fun userDescriptions(actions: List<FinanceRuleActionEntity>): Set<String> = actions
        .filter { it.kind == FinanceActionKind.NORMALIZE_DESCRIPTION }.map { it.value }.toSet()

    fun sanitize(snapshot: FinanceV4Snapshot): FinanceV4Snapshot {
        val descriptions = userDescriptions(snapshot.actions)
        return snapshot.copy(proposals = snapshot.proposals.map { sanitize(it, descriptions) })
    }
}
