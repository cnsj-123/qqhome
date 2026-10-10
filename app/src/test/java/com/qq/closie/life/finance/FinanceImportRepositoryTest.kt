package com.qq.closie.life.finance

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FinanceImportRepositoryTest : FinanceRoomTest() {
    @get:Rule val files = TemporaryFolder()
    private val imports get() = FinanceImportRepository(db, finance)
    private suspend fun stage(rows: List<List<String>>, name: String = "synthetic.csv"): FinanceImportPreview {
        val file = files.newFile(name)
        if (name.endsWith(".xlsx")) SyntheticLegacyLedger.xlsx(file, rows) else SyntheticLegacyLedger.csv(file, rows)
        return imports.stage(file, name)
    }

    @Test fun confirmedXlsxBatchCreatesOnlyRealFlowsFeeAndHierarchyThenUndoRetiresAllSemantics() = runTest {
        val preview = stage(listOf(
            SyntheticLegacyLedger.row("金额" to "12.34", "分类" to "服饰", "子分类" to "衣服", "标签" to "原始 单元格"),
            SyntheticLegacyLedger.row("类型" to "转账", "金额" to "20", "转入账户" to "合成银行", "转账手续费" to "0.29"),
            SyntheticLegacyLedger.row("备注" to "报销付款", "金额" to "3", "是否为报销" to "是", "报销金额" to "1"),
            SyntheticLegacyLedger.row("备注" to "退款证据", "金额" to "4", "退款金额" to "2", "不计入收支" to "是")),
            "synthetic.xlsx")
        assertTrue(finance.snapshot().entries.isEmpty())
        val mappings = listOf(FinanceAccountMapping("合成钱包"), FinanceAccountMapping("合成银行", kind = FinanceAccountKind.BANK_CARD))
        val checked = imports.preview(preview.batch.id, mappings)
        val selected = FinanceImportReview.selected(checked.rows,
            FinanceImportReviewPolicy(reimbursement = true, refund = true, fees = true))
        assertEquals(4, selected.size)
        assertEquals(4, imports.commit(preview.batch.id, mappings, selected, emptySet(), false, true))
        val state = readFinanceSnapshot(db, true)
        assertEquals(6, state.entries.size) // Three real ordinary flows + transfer pair + real fee.
        assertEquals(1, state.transfers.size)
        assertEquals(1, state.entries.count { it.direction == FinanceDirection.INFLOW })
        assertEquals(listOf("原始 单元格"), FinanceProjection.ledger(state).single { it.entry.amountMinor == 1_234L }.tags)
        assertEquals("服饰 / 衣服", FinanceProjection.ledger(state).single { it.entry.amountMinor == 1_234L }.category)
        assertTrue(state.categories.any { it.parentId != null && it.name == "衣服" })
        assertEquals(29L, state.entries.single { it.description == "转账手续费" }.amountMinor)
        assertTrue(state.v4!!.events.any { it.nature == FinanceNature.REIMBURSABLE })
        assertTrue(state.entries.any { it.description == "退款证据" && it.statPolicy == FinanceStatPolicy.EXCLUDE })
        assertTrue(state.v4!!.expected.isEmpty())
        val wallet = state.accounts.single { it.importSourceName == "合成钱包" }
        assertEquals(checked.rows.minOf { it.occurredAt!! }, wallet.balanceAnchorAt)
        assertEquals(-3_963L, FinanceProjection.balance(wallet, state.entries))
        assertEquals(FinanceTotals(1_263, 0), FinanceProjection.totals(FinancePersonalProjection.rows(state, FinanceProjection.ledger(state)))["CNY"])
        assertEquals(0, imports.commit(preview.batch.id, mappings, selected, emptySet(), false, true))
        val undo = imports.undo(preview.batch.id)
        assertEquals(FinanceUndoResult(6, 0), undo)
        val retired = readFinanceSnapshot(db, true)
        assertTrue(retired.entries.all { it.voidedAt != null })
        assertTrue(retired.v4!!.events.all { it.voidedAt != null })
        assertTrue(retired.v4!!.activeLinks.isEmpty())
        assertEquals(4, retired.v4!!.links.size)
        assertTrue(retired.v4!!.staging.all { it.status == FinanceRowStatus.UNDONE && it.rawPayload.isNotBlank() })
        assertEquals(FinanceUndoResult(0, 0), imports.undo(preview.batch.id))
        FinanceIntegrity.validate(retired)
    }

    @Test fun csvSameFileIdempotencyWithinFileAndExistingCanonicalDuplicatesNeedExplicitOverride() = runTest {
        val a = account()
        val row = SyntheticLegacyLedger.row("备注" to "重复候选")
        val preview = stage(listOf(row, row))
        val file = files.root.resolve("synthetic.csv")
        assertEquals(preview.batch.id, imports.stage(file, "renamed.csv").batch.id)
        assertEquals(1, imports.history().size)
        val mappings = listOf(FinanceAccountMapping("合成钱包", existingId = a.id))
        val first = preview.rows.first()
        finance.saveEntry(accountId = a.id, direction = FinanceDirection.OUTFLOW, amountMinor = first.amountMinor!!,
            description = first.description, occurredAt = first.occurredAt!!)
        val checked = imports.preview(preview.batch.id, mappings)
        assertTrue(checked.rows.all { it.status == FinanceRowStatus.DUPLICATE })
        assertTrue(FinanceImportReview.selected(checked.rows, FinanceImportReviewPolicy()).isEmpty())
        val before = readFinanceSnapshot(db, true)
        assertTrue(runCatching {
            imports.commit(preview.batch.id, mappings, setOf(first.id), emptySet(), false, false)
        }.isFailure)
        assertEquals(before, readFinanceSnapshot(db, true))
        assertEquals(1, imports.commit(preview.batch.id, mappings, setOf(first.id), setOf(first.id), false, false))
        assertEquals(2, finance.snapshot().entries.size)
        assertEquals(1, finance.snapshot().accounts.size)
    }

    @Test fun currentBalanceAnchorKeepsBackfilledEntriesInHistoryWithoutChangingCurrentBalance() = runTest {
        val preview = stage(listOf(SyntheticLegacyLedger.row("金额" to "12.34")))
        val mapping = FinanceAccountMapping("合成钱包", fromHistory = false, openingMinor = 4_567, anchorAt = 2_000_000_000_000)
        val checked = imports.preview(preview.batch.id, listOf(mapping))
        imports.commit(preview.batch.id, listOf(mapping), checked.rows.map { it.id }.toSet(), emptySet(), false, false)
        val state = finance.snapshot()
        val a = state.accounts.single()
        assertEquals(mapping.anchorAt, a.balanceAnchorAt)
        assertEquals(4_567L, FinanceProjection.balance(a, state.entries))
        assertEquals(1, FinanceProjection.ledger(state).size)
        assertEquals(1_234L, FinanceProjection.totals(FinancePersonalProjection.rows(state, FinanceProjection.ledger(state)))["CNY"]!!.expenseMinor)
    }

    @Test fun lateCanonicalFailureRollsBackEntriesRelationsJournalAndStagingAsOneBatch() = runTest {
        val a = account()
        val preview = stage(listOf(SyntheticLegacyLedger.row("备注" to "先成功"),
            SyntheticLegacyLedger.row("类型" to "转账", "转入账户" to "合成银行", "备注" to "后失败")))
        // Source and target map to the same real account: rejected by the domain after the first row wrote.
        val mappings = listOf(FinanceAccountMapping("合成钱包", existingId = a.id),
            FinanceAccountMapping("合成银行", existingId = a.id))
        val before = readFinanceSnapshot(db, true)
        assertTrue(runCatching {
            imports.commit(preview.batch.id, mappings, preview.rows.map { it.id }.toSet(), emptySet(), false, false)
        }.isFailure)
        assertEquals(before, readFinanceSnapshot(db, true))
    }

    @Test fun undoKeepsLaterEditedOrLinkedImportedEntriesAndTheirLiveRelations() = runTest {
        val preview = stage(listOf(SyntheticLegacyLedger.row()))
        val mappings = listOf(FinanceAccountMapping("合成钱包"))
        imports.commit(preview.batch.id, mappings, preview.rows.map { it.id }.toSet(), emptySet(), false, false)
        val entry = finance.snapshot().entries.single()
        finance.saveEntry(entry.id, entry.accountId, entry.direction, entry.amountMinor, "后来纠正", entry.occurredAt,
            event = FinanceEventInput(), expectedRevision = entry.revision)
        assertEquals(FinanceUndoResult(0, 1), imports.undo(preview.batch.id))
        val state = finance.snapshot()
        assertNull(state.entries.single().voidedAt)
        assertEquals(1, state.v4!!.activeLinks.size)
        assertEquals(2, state.v4!!.links.size)
        assertNull(state.v4!!.events.single().voidedAt)
    }
}
