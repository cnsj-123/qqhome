package com.qq.closie.life.finance

import com.google.gson.JsonParser
import java.math.BigDecimal

enum class LegacyBusinessState(val label: String) {
    ORDINARY("普通流水"), PENDING_REIMBURSEMENT("待报销"),
    SETTLED_REIMBURSEMENT("旧账已报销／待迁移业务状态"), PROXY("代拍／代付"),
    SPLIT("分账"), REFUND("退款相关"), REVIEW("需核对")
}

/** Import-only interpretation of preserved source fields. Never queried by canonical projections.
 * Ancillary reimbursement/refund amounts are evidence, not new cash flows or cancellations. */
object LegacyBusinessSemantics {
    fun state(row: FinanceImportRowEntity): LegacyBusinessState {
        val raw = runCatching { JsonParser.parseString(row.rawPayload).asJsonObject }.getOrNull()
            ?: return LegacyBusinessState.REVIEW
        fun field(key: String): String = runCatching { raw.get(key)?.asString?.trim().orEmpty() }.getOrDefault("")
        val status = field("账单状态")
        if (status == "已报销") return LegacyBusinessState.SETTLED_REIMBURSEMENT
        if (row.requiresIndividualReview || row.status == FinanceRowStatus.INVALID ||
            status !in setOf("", "正常", "未报销", "待报销", "已退款", "退款账单")) return LegacyBusinessState.REVIEW
        val reimbursementAmount = field("报销金额").ifBlank { "0" }.toBigDecimalOrNull()
            ?: return LegacyBusinessState.REVIEW
        val refundAmount = field("退款金额").ifBlank { "0" }.toBigDecimalOrNull()
            ?: return LegacyBusinessState.REVIEW
        if (reimbursementAmount < BigDecimal.ZERO || refundAmount < BigDecimal.ZERO) return LegacyBusinessState.REVIEW
        // Partial/unstated settlement cannot become an outstanding v4 reimbursement event.
        if (reimbursementAmount > BigDecimal.ZERO || field("报销日期").isNotBlank()) return LegacyBusinessState.REVIEW
        val semantics = buildSet {
            val labels = listOf(row.category.trim(), row.subcategory.trim()) + row.tagsText.split(Regex("[,，;；\\s]+"))
            if (labels.any { it in setOf("代拍", "代付") }) add(LegacyBusinessState.PROXY)
            if ("分账" in labels) add(LegacyBusinessState.SPLIT)
            if (row.reimbursable || status in setOf("未报销", "待报销")) add(LegacyBusinessState.PENDING_REIMBURSEMENT)
        }
        if (semantics.size > 1 || (row.kind == FinanceProposalKind.TRANSFER && (semantics.isNotEmpty() || row.hasRefund)))
            return LegacyBusinessState.REVIEW
        if (LegacyBusinessState.PENDING_REIMBURSEMENT in semantics && row.kind != FinanceProposalKind.EXPENSE)
            return LegacyBusinessState.REVIEW
        return semantics.singleOrNull() ?: if (row.hasRefund) LegacyBusinessState.REFUND else LegacyBusinessState.ORDINARY
    }

    fun canCommit(row: FinanceImportRowEntity): Boolean = state(row) !in setOf(
        LegacyBusinessState.SETTLED_REIMBURSEMENT, LegacyBusinessState.REVIEW)

    fun nature(row: FinanceImportRowEntity): FinanceNature = when (state(row)) {
        LegacyBusinessState.PENDING_REIMBURSEMENT -> FinanceNature.REIMBURSABLE
        LegacyBusinessState.PROXY -> FinanceNature.PROXY_PURCHASE
        LegacyBusinessState.SPLIT -> FinanceNature.SPLIT
        LegacyBusinessState.REFUND -> if (row.kind == FinanceProposalKind.INCOME) FinanceNature.OTHER else FinanceNature.PERSONAL
        LegacyBusinessState.ORDINARY -> FinanceNature.PERSONAL
        else -> error("旧账业务状态尚未完成核对或迁移")
    }

    fun consumptionCategory(value: String): String = value.takeUnless { it.trim() in setOf("代拍", "代付", "分账") }.orEmpty()

    fun categoryPath(row: FinanceImportRowEntity): Pair<String, String> {
        val parent = consumptionCategory(row.category)
        val child = consumptionCategory(row.subcategory)
        return if (parent.isBlank()) child to "" else parent to child
    }
}
