package com.qq.closie.life.finance

import org.junit.Assert.*
import org.junit.Test

class FinanceImportReviewTest {
    private fun rows() = LegacyLedgerReader.stage(LegacyLedgerTable("synthetic", listOf(LegacyLedgerReader.headers) +
        (1..300).map { SyntheticLegacyLedger.row("备注" to "报销$it", "是否为报销" to "是") } +
        listOf(SyntheticLegacyLedger.row("备注" to "普通"),
            SyntheticLegacyLedger.row("备注" to "退款", "退款金额" to "2"),
            SyntheticLegacyLedger.row("备注" to "手续费", "类型" to "转账", "转入账户" to "合成银行", "转账手续费" to "1"),
            SyntheticLegacyLedger.row("备注" to "交叉问题", "是否为报销" to "是", "退款金额" to "3"),
            SyntheticLegacyLedger.row("备注" to "普通"), SyntheticLegacyLedger.row("债务人" to "合成人物"),
            SyntheticLegacyLedger.row("金额" to "bad"),
            SyntheticLegacyLedger.row("分类" to "代拍"),
            SyntheticLegacyLedger.row("分类" to "分账"),
            SyntheticLegacyLedger.row("是否为报销" to "是", "账单状态" to "已报销"))), "batch")
    @Test fun ordinaryRowsDefaultSelectAndHundredsOfKnownIssueRowsNeedOnlyBatchPolicy() {
        val rows = rows()
        assertEquals(1, FinanceImportReview.selected(rows, FinanceImportReviewPolicy()).size)
        val reimbursement = FinanceImportReview.selected(rows, FinanceImportReviewPolicy(reimbursement = true))
        assertEquals(301, reimbursement.size)
        val allKnown = FinanceImportReview.selected(rows, FinanceImportReviewPolicy(reimbursement = true, refund = true, fees = true))
        assertEquals(304, allKnown.size)
        assertFalse(rows.single { it.description == "交叉问题" }.id in reimbursement)
        assertTrue(rows.single { it.description == "交叉问题" }.id in allKnown)
        assertTrue(rows.filter { it.status == FinanceRowStatus.DUPLICATE || it.requiresIndividualReview }.none { it.id in allKnown })
        assertTrue(FinanceImportGroup.entries.all { group -> rows.any { group in FinanceImportReview.groups(it) } })
    }

    @Test fun duplicatesNeedIndividualOverrideInvalidRowsCannotBeSelected() {
        val rows = rows()
        val duplicate = rows.single { it.status == FinanceRowStatus.DUPLICATE }
        val invalid = rows.single { it.status == FinanceRowStatus.INVALID }
        val ordinary = rows.single { it.status == FinanceRowStatus.READY && it.description == "普通" }
        val selected = FinanceImportReview.selected(rows, FinanceImportReviewPolicy(),
            mapOf(duplicate.id to true, invalid.id to true, ordinary.id to false))
        assertEquals(setOf(duplicate.id), selected)
    }

    @Test fun settledUnknownAccountCannotBeForcedIntoPendingAndNatureIsNotCategory() {
        val source = LegacyLedgerReader.stage(LegacyLedgerTable("synthetic", listOf(LegacyLedgerReader.headers,
            SyntheticLegacyLedger.row("账单状态" to "已报销", "报销金额" to "12.34"),
            SyntheticLegacyLedger.row("分类" to "代拍", "子分类" to "书籍"),
            SyntheticLegacyLedger.row("分类" to "分账"))), "batch")
        assertEquals(LegacyBusinessState.SETTLED_REIMBURSEMENT, LegacyBusinessSemantics.state(source[0]))
        assertFalse(FinanceImportReview.eligible(source[0]))
        assertFalse(source[0].id in FinanceImportReview.selected(source, FinanceImportReviewPolicy(), mapOf(source[0].id to true)))
        assertEquals(FinanceNature.PROXY_PURCHASE, LegacyBusinessSemantics.nature(source[1]))
        assertEquals("书籍" to "", LegacyBusinessSemantics.categoryPath(source[1]))
        assertEquals(FinanceNature.SPLIT, LegacyBusinessSemantics.nature(source[2]))
    }
}
