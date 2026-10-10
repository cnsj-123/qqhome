package com.qq.closie.life.finance

enum class FinanceImportGroup(val label: String) {
    READY("普通记录"), REIMBURSEMENT("报销相关"), REFUND("退款相关"),
    FEES("转账手续费"), DUPLICATES("重复候选"), UNSUPPORTED("需单独处理")
}

data class FinanceImportReviewPolicy(val ready: Boolean = true, val reimbursement: Boolean = false,
    val refund: Boolean = false, val fees: Boolean = false)

/** Review is a selection policy over staging, never a mutation of canonical facts. */
object FinanceImportReview {
    fun groups(row: FinanceImportRowEntity): Set<FinanceImportGroup> = when {
        row.canonicalId != null || row.status in setOf(FinanceRowStatus.COMMITTED, FinanceRowStatus.UNDONE) -> emptySet()
        row.status == FinanceRowStatus.DUPLICATE -> setOf(FinanceImportGroup.DUPLICATES)
        row.requiresIndividualReview || row.status == FinanceRowStatus.INVALID || row.kind == null ->
            setOf(FinanceImportGroup.UNSUPPORTED)
        else -> buildSet {
            if (row.reimbursable) add(FinanceImportGroup.REIMBURSEMENT)
            if (row.hasRefund) add(FinanceImportGroup.REFUND)
            if (row.feeMinor > 0) add(FinanceImportGroup.FEES)
            if (isEmpty()) add(if (row.status == FinanceRowStatus.READY) FinanceImportGroup.READY else FinanceImportGroup.UNSUPPORTED)
        }
    }

    fun eligible(row: FinanceImportRowEntity) = row.canonicalId == null && row.kind != null &&
        row.amountMinor != null && row.amountMinor > 0 && row.occurredAt != null &&
        row.status !in setOf(FinanceRowStatus.INVALID, FinanceRowStatus.UNDONE, FinanceRowStatus.COMMITTED)

    fun selected(rows: List<FinanceImportRowEntity>, policy: FinanceImportReviewPolicy,
        overrides: Map<String, Boolean> = emptyMap()): Set<String> = rows.filter { row ->
        eligible(row) && (overrides[row.id] ?: groups(row).all {
            when (it) {
                FinanceImportGroup.READY -> policy.ready
                FinanceImportGroup.REIMBURSEMENT -> policy.reimbursement
                FinanceImportGroup.REFUND -> policy.refund
                FinanceImportGroup.FEES -> policy.fees
                FinanceImportGroup.DUPLICATES, FinanceImportGroup.UNSUPPORTED -> false
            }
        })
    }.map { it.id }.toSet()
}
