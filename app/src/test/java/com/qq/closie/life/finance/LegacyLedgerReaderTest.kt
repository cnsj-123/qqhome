package com.qq.closie.life.finance

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LegacyLedgerReaderTest {
    @get:Rule val files = TemporaryFolder()
    @Test fun csvPreservesExactMoneyHierarchyQuotesFlagsAndRelationshipEvidence() {
        val row = SyntheticLegacyLedger.row("金额" to "0.29", "分类" to "服饰", "子分类" to "衣服",
            "备注" to "逗号,引号\"及\n换行", "标签" to "两个 标签", "是否为报销" to "是",
            "报销金额" to "0.10", "退款金额" to "0.05", "不计入收支" to "是", "不计入预算" to "是")
        val file = files.newFile("synthetic.csv")
        SyntheticLegacyLedger.csv(file, listOf(row, row, SyntheticLegacyLedger.row("金额" to "0.001")))
        val table = LegacyLedgerReader.read(file, file.name)
        assertEquals(row, table.rows[1])
        val staged = LegacyLedgerReader.stage(table, "batch")
        assertEquals(29L, staged[0].amountMinor)
        assertEquals("服饰", staged[0].category)
        assertEquals("衣服", staged[0].subcategory)
        assertTrue(staged[0].reimbursable && staged[0].hasRefund)
        assertEquals(FinanceStatPolicy.EXCLUDE, staged[0].statPolicy)
        assertEquals(FinanceBudgetPolicy.EXCLUDE, staged[0].budgetPolicy)
        assertTrue(staged[0].rawPayload.contains("报销金额"))
        assertEquals(FinanceRowStatus.REVIEW, staged[0].status)
        assertEquals(FinanceRowStatus.DUPLICATE, staged[1].status)
        assertEquals(FinanceRowStatus.INVALID, staged[2].status)
        assertNull(staged[2].amountMinor)
    }

    @Test fun incorrectSingleRowXlsxDimensionDoesNotTruncateActualWorksheetRows() {
        val rows = (1..512).map { SyntheticLegacyLedger.row("备注" to "合成行$it", "金额" to "123.45") }
        val file = files.newFile("wrong-dimension.xlsx")
        SyntheticLegacyLedger.xlsx(file, rows, "A1:V1")
        val table = LegacyLedgerReader.read(file, file.name)
        assertEquals(513, table.rows.size)
        assertEquals(rows.first(), table.rows[1])
        assertEquals(rows.last(), table.rows.last())
        val staged = LegacyLedgerReader.stage(table, "batch")
        assertEquals(512, staged.size)
        assertTrue(staged.all { it.amountMinor == 12_345L && it.status == FinanceRowStatus.READY })
    }

    @Test fun regularXlsxTransferFeeAndUnsupportedDebtorAreTypedForReview() {
        val file = files.newFile("synthetic.xlsx")
        SyntheticLegacyLedger.xlsx(file, listOf(
            SyntheticLegacyLedger.row("类型" to "转账", "转入账户" to "合成银行", "转账手续费" to "0.10"),
            SyntheticLegacyLedger.row("债务人" to "合成人物"),
            SyntheticLegacyLedger.row("类型" to "无法解释")), "A1:V4")
        val rows = LegacyLedgerReader.stage(LegacyLedgerReader.read(file, file.name), "batch")
        assertEquals(FinanceProposalKind.TRANSFER, rows[0].kind)
        assertEquals(10L, rows[0].feeMinor)
        assertEquals("合成银行", rows[0].targetAccount)
        assertFalse(rows[0].requiresIndividualReview)
        assertTrue(rows[1].requiresIndividualReview)
        assertTrue(rows[2].requiresIndividualReview)
        assertNull(rows[2].kind)
    }
}
