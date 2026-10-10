package com.qq.closie.life.finance

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FinanceRelationLifecycleTest : FinanceRoomTest() {
    @Test fun reassociationPreservesIdentityHistoryRetiresOrphanAndCancelsAllExpectations() = runTest {
        val a = account()
        val first = payment(a.id)
        val second = payment(a.id, 10_000)
        val originalId = eventId(first.id)
        val targetId = eventId(second.id)
        val original = db.financeIntakeDao().event(originalId)!!
        finance.saveExpectedFlows(originalId, listOf(
            FinanceExpectedFlowInput(role = FinanceFlowRole.REFUND, amountMinor = 100, note = "一"),
            FinanceExpectedFlowInput(role = FinanceFlowRole.SETTLEMENT, note = "二")), original.updatedAt)
        val oldLink = db.financeIntakeDao().linksForEntry(first.id).single()
        finance.allocateEntry(first.id, listOf(FinanceAllocation(targetId, FinanceFlowRole.PAYMENT, first.amountMinor)), first.revision)
        val state = readFinanceSnapshot(db, true)
        val old = state.v4!!.links.single { it.id == oldLink.id }
        assertNotNull(old.voidedAt)
        assertEquals(oldLink.createdAt, old.createdAt)
        assertEquals(oldLink.revision + 1, old.revision)
        assertEquals(3, state.v4!!.links.size)
        assertEquals(2, state.v4!!.activeLinks.size)
        assertNotNull(state.v4!!.events.single { it.id == originalId }.voidedAt)
        assertTrue(state.v4!!.expected.all { it.cancelledAt != null })
        assertTrue(state.v4!!.changes.any { it.objectId == old.id && it.operation == "LINK_RETIRE" &&
            it.beforeRevision == 1L && it.afterRevision == 2L })
        assertEquals(-40_000L, FinanceProjection.balance(a, state.entries))
        FinanceIntegrity.validate(state)
    }

    @Test fun singleFlowCanPartitionAcrossEventsAndRemovingOneRetainsHistory() = runTest {
        val a = account()
        val flow = payment(a.id, 30_000)
        val other = payment(a.id, 5_000)
        val one = eventId(flow.id)
        val two = eventId(other.id)
        finance.allocateEntry(flow.id, listOf(
            FinanceAllocation(one, FinanceFlowRole.PAYMENT, 10_000),
            FinanceAllocation(two, FinanceFlowRole.PAYMENT, 20_000)), flow.revision)
        var state = finance.snapshot()
        assertEquals(30_000L, state.v4!!.activeLinks.filter { it.entryId == flow.id }.sumOf { it.allocatedMinor })
        assertEquals(35_000L, FinanceProjection.totals(FinancePersonalProjection.rows(state, FinanceProjection.ledger(state)))["CNY"]!!.expenseMinor)
        finance.allocateEntry(flow.id, listOf(FinanceAllocation(two, FinanceFlowRole.PAYMENT, 30_000)),
            state.entries.single { it.id == flow.id }.revision)
        state = finance.snapshot()
        assertNotNull(state.v4!!.events.single { it.id == one }.voidedAt)
        assertEquals(3, state.v4!!.links.count { it.entryId == flow.id && it.voidedAt != null })
        assertEquals(1, state.v4!!.activeLinks.count { it.entryId == flow.id })
    }

    @Test fun multipleOptionalExpectedFlowsEditAndCancelWithoutAffectingTruthOrBalances() = runTest {
        val a = account()
        val entry = payment(a.id, nature = FinanceNature.SPLIT, share = 10_000)
        val id = eventId(entry.id)
        val before = finance.snapshot()
        finance.saveExpectedFlows(id, listOf(
            FinanceExpectedFlowInput(role = FinanceFlowRole.SETTLEMENT, amountMinor = 10_000, note = "第一人"),
            FinanceExpectedFlowInput(role = FinanceFlowRole.SETTLEMENT, note = "金额未知"),
            FinanceExpectedFlowInput(role = FinanceFlowRole.REIMBURSEMENT, amountMinor = 5_000, note = "另一关系")),
            before.v4!!.events.single().updatedAt)
        val after = finance.snapshot()
        assertEquals(before.entries, after.entries)
        assertEquals(FinanceProjection.balance(a, before.entries), FinanceProjection.balance(a, after.entries))
        assertEquals(10_000L, FinanceProjection.totals(FinancePersonalProjection.rows(after, FinanceProjection.ledger(after)))["CNY"]!!.expenseMinor)
        assertEquals(3, after.v4!!.expected.size)
        assertNull(after.v4!!.expected.single { it.note == "金额未知" }.amountMinor)
        val keep = after.v4!!.expected.first()
        finance.saveExpectedFlows(id, listOf(FinanceExpectedFlowInput(keep.id, keep.role, 12_000, "已调整")),
            after.v4!!.events.single().updatedAt)
        val edited = finance.snapshot().v4!!
        assertEquals(1, edited.expected.count { it.cancelledAt == null })
        assertEquals(2, edited.expected.count { it.cancelledAt != null })
        assertEquals("已调整", edited.expected.single { it.cancelledAt == null }.note)
        assertTrue(runCatching { finance.saveExpectedFlows(id, emptyList(), after.v4!!.events.single().updatedAt) }.isFailure)
    }

    @Test fun voidEntryRetiresItsLinksEventAndExpectedRelations() = runTest {
        val a = account()
        val entry = payment(a.id)
        val id = eventId(entry.id)
        finance.saveExpectedFlows(id, listOf(FinanceExpectedFlowInput(role = FinanceFlowRole.REFUND)),
            db.financeIntakeDao().event(id)!!.updatedAt)
        finance.voidEntry(entry.id)
        finance.voidEntry(entry.id)
        val state = finance.snapshot()
        assertTrue(state.v4!!.activeLinks.isEmpty())
        assertEquals(1, state.v4!!.links.size)
        assertNotNull(state.v4!!.events.single().voidedAt)
        assertNotNull(state.v4!!.expected.single().cancelledAt)
        assertEquals(0L, FinanceProjection.balance(a, state.entries))
        assertTrue(FinancePersonalProjection.rows(state, FinanceProjection.ledger(state)).isEmpty())
    }

    @Test fun attachEditAlsoRetainsPriorLinkAndInvalidAllocationRollsBackEverything() = runTest {
        val a = account()
        val entry = payment(a.id)
        val target = payment(a.id, 100)
        val oldId = eventId(entry.id)
        finance.saveEntry(entry.id, a.id, FinanceDirection.OUTFLOW, 30_000, "纠正关联", 2_000,
            event = FinanceEventInput(existingEventId = eventId(target.id)), expectedRevision = entry.revision)
        val saved = readFinanceSnapshot(db, true)
        assertNotNull(saved.v4!!.events.single { it.id == oldId }.voidedAt)
        assertEquals(2, saved.v4!!.links.count { it.entryId == entry.id })
        val current = saved.entries.single { it.id == entry.id }
        assertTrue(runCatching {
            finance.allocateEntry(entry.id, listOf(FinanceAllocation("missing", FinanceFlowRole.PAYMENT, 30_000)), current.revision)
        }.isFailure)
        assertEquals(saved, readFinanceSnapshot(db, true))
        assertTrue(runCatching {
            finance.allocateEntry(entry.id, listOf(FinanceAllocation(eventId(target.id), FinanceFlowRole.REFUND, 30_000)), current.revision)
        }.isFailure)
        assertEquals(saved, readFinanceSnapshot(db, true))
    }

    @Test fun voidingPaymentLeavesRealRefundAndExposesItWithoutNegativeConsumption() = runTest {
        val a = account()
        val paid = payment(a.id)
        val refund = finance.saveEntry(accountId = a.id, direction = FinanceDirection.INFLOW,
            amountMinor = 10_000, description = "合成退款", occurredAt = 3_000,
            event = FinanceEventInput(role = FinanceFlowRole.REFUND, existingEventId = eventId(paid.id)))
        val before = finance.snapshot()
        assertEquals(FinanceTotals(20_000, 0), FinanceProjection.totals(
            FinancePersonalProjection.rows(before, FinanceProjection.ledger(before)))["CNY"])
        finance.voidEntry(paid.id)
        val after = finance.snapshot()
        FinanceIntegrity.validate(after)
        assertEquals(refund, after.entries.single { it.id == refund.id })
        assertEquals(10_000L, FinanceProjection.balance(a, after.entries))
        val ledger = FinanceProjection.ledger(after)
        assertEquals(FinanceTotals(0, 10_000), FinancePersonalProjection.realTotals(ledger)["CNY"])
        assertTrue(FinancePersonalProjection.rows(after, ledger).isEmpty())
        val unresolved = FinancePersonalProjection.unassignedAdjustments(after, ledger).single()
        assertEquals(FinanceAdjustmentIssue.NO_ACTIVE_PAYMENT_COST, unresolved.issue)
        assertEquals(10_000L, unresolved.amountMinor)
        assertEquals(refund.id, unresolved.row.entry.id)
        assertTrue(after.v4!!.activeLinks.all { it.role == FinanceFlowRole.REFUND })
    }

    @Test fun voidTransferAlsoRetiresRealFeeAndCancelsFeeExpectations() = runTest {
        val a = account()
        val b = account("合成银行")
        val transfer = finance.saveTransfer(sourceAccountId = a.id, targetAccountId = b.id,
            amountMinor = 10_000, description = "合成转账", occurredAt = 2_000)
        val fee = finance.saveEntry(accountId = a.id, direction = FinanceDirection.OUTFLOW,
            amountMinor = 100, description = "合成手续费", occurredAt = 2_000,
            event = FinanceEventInput(role = FinanceFlowRole.FEE, relatedTransferId = transfer.id))
        val id = eventId(fee.id)
        finance.saveExpectedFlows(id, listOf(FinanceExpectedFlowInput(role = FinanceFlowRole.REFUND)),
            db.financeIntakeDao().event(id)!!.updatedAt)
        finance.voidTransfer(transfer.id)
        val state = finance.snapshot()
        assertTrue(state.entries.all { it.voidedAt != null })
        assertTrue(state.v4!!.activeLinks.isEmpty())
        assertNotNull(state.v4!!.expected.single().cancelledAt)
        assertNotNull(state.v4!!.events.single().voidedAt)
        assertEquals(0L, FinanceProjection.balance(a, state.entries))
        assertEquals(0L, FinanceProjection.balance(b, state.entries))
    }

    @Test fun integrityRejectsActiveOrphansAndExpectationsButAllowsHistoricalRelations() = runTest {
        val a = account()
        payment(a.id)
        val saved = finance.snapshot()
        val event = saved.v4!!.events.single()
        assertTrue(runCatching { FinanceIntegrity.validate(saved.copy(v4 = saved.v4!!.copy(links = emptyList()))) }.isFailure)
        val retired = event.copy(voidedAt = 3_000)
        val historical = saved.v4!!.links.single().copy(voidedAt = 3_000, updatedAt = 3_000, revision = 2)
        val invalid = saved.copy(v4 = saved.v4!!.copy(events = listOf(retired), links = listOf(historical),
            expected = listOf(FinanceExpectedFlowEntity("e", event.id, FinanceFlowRole.REFUND, null, "", 1_000))))
        assertTrue(runCatching { FinanceIntegrity.validate(invalid) }.isFailure)
        FinanceIntegrity.validate(invalid.copy(v4 = invalid.v4!!.copy(expected = invalid.v4!!.expected.map { it.copy(cancelledAt = 3_000) })))
    }
}
