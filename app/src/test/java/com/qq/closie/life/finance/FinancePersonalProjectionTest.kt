package com.qq.closie.life.finance

import org.junit.Assert.*
import org.junit.Test

class FinancePersonalProjectionTest {
    private fun entry(id: String, amount: Long, direction: FinanceDirection = FinanceDirection.OUTFLOW,
        category: String? = "clothing") = FinanceEntryEntity(id, "a", direction, amount, id, 2_000, 1_000, 1_000, category)
    private val account = FinanceAccountEntity("a", "合成账户", FinanceAccountKind.CASH, "CNY", 0, 0, 0)
    private val categories = listOf(FinanceCategoryEntity("root", "穿着"),
        FinanceCategoryEntity("clothing", "衣服", "root"), FinanceCategoryEntity("food", "餐饮"),
        FinanceCategoryEntity("refund", "退款原分类"))
    private fun snapshot(nature: FinanceNature, share: Long? = null, flows: List<FinanceEntryEntity>,
        roles: List<FinanceFlowRole>) = FinanceSnapshot(accounts = listOf(account), entries = flows, categories = categories,
        tags = listOf(FinanceTagEntity("t", "穿着标签"), FinanceTagEntity("wrong", "到账标签")),
        entryTags = flows.map { FinanceEntryTagCrossRef(it.id, if (it.direction == FinanceDirection.OUTFLOW) "t" else "wrong") },
        v4 = FinanceV4Snapshot(events = listOf(FinanceEventEntity("event", "合成事件", nature, "CNY", 2_000, 1_000, 1_000, share)),
            links = flows.zip(roles).map { (flow, role) -> FinanceEventEntryLinkEntity("event", flow.id, role, flow.amountMinor, createdAt = 1_000, updatedAt = 1_000) }))
    private fun rows(data: FinanceSnapshot) = FinancePersonalProjection.rows(data, FinanceProjection.ledger(data))

    @Test fun personalPaymentAndEachLinkedAdjustmentReconcileWithPaymentDimensions() {
        for (role in listOf(FinanceFlowRole.REFUND, FinanceFlowRole.REBATE, FinanceFlowRole.CASHBACK)) {
            val data = snapshot(FinanceNature.PERSONAL, flows = listOf(entry("pay", 30_000),
                entry("adjust", 10_000, FinanceDirection.INFLOW, "refund")), roles = listOf(FinanceFlowRole.PAYMENT, role))
            FinanceIntegrity.validate(data)
            val projected = rows(data)
            assertEquals(FinanceTotals(20_000, 0), FinanceProjection.totals(projected)["CNY"])
            assertEquals(listOf(FinanceDimensionTotal("穿着 / 衣服", "CNY", FinanceTotals(20_000, 0))), FinanceProjection.categoryTotals(projected))
            assertEquals(FinanceTotals(20_000, 0), FinanceProjection.parentCategoryTotals(projected, categories).single().totals)
            assertEquals(listOf("穿着标签"), FinanceProjection.tagTotals(projected).map { it.label })
            assertEquals(20_000L, FinanceProjection.tagTotals(projected).single().totals.expenseMinor)
            // A refund selected in a later period still uses full payment context.
            assertEquals("穿着 / 衣服", FinancePersonalProjection.rows(data,
                FinanceProjection.ledger(data).filter { it.entry.id == "adjust" }).single().category)
            assertEquals(-20_000L, FinanceProjection.balance(account, data.entries))
            assertTrue(FinancePersonalProjection.unassignedAdjustments(data, FinanceProjection.ledger(data)).isEmpty())
        }
    }

    @Test fun incompatiblePaymentCategoriesUseExplicitAdjustmentBucketWithoutChangingTotal() {
        val data = snapshot(FinanceNature.PERSONAL, flows = listOf(entry("one", 20_000), entry("two", 10_000, category = "food"),
            entry("refund", 10_000, FinanceDirection.INFLOW, "refund")),
            roles = listOf(FinanceFlowRole.PAYMENT, FinanceFlowRole.PAYMENT, FinanceFlowRole.REFUND))
        val projected = rows(data)
        val breakdown = FinanceProjection.categoryTotals(projected)
        assertEquals(-10_000L, breakdown.single { it.label == "调整待归属" }.totals.expenseMinor)
        assertFalse(breakdown.any { it.label == "退款原分类" })
        assertEquals(20_000L, breakdown.sumOf { it.totals.expenseMinor })
        assertEquals(FinanceProjection.totals(projected)["CNY"]!!.expenseMinor, breakdown.sumOf { it.totals.expenseMinor })
        assertEquals(-10_000L, FinanceProjection.parentCategoryTotals(projected, categories).single { it.label == "调整待归属" }.totals.expenseMinor)
    }

    @Test fun excessAdjustmentIsWhollyUnassignedAndNeverCreatesNegativeConsumption() {
        for (nature in listOf(FinanceNature.PERSONAL, FinanceNature.STORED_VALUE)) {
            for (role in listOf(FinanceFlowRole.REFUND, FinanceFlowRole.REBATE, FinanceFlowRole.CASHBACK)) {
                val data = snapshot(nature, flows = listOf(entry("pay", 30_000),
                    entry("adjust", 38_000, FinanceDirection.INFLOW, "refund")), roles = listOf(FinanceFlowRole.PAYMENT, role))
                FinanceIntegrity.validate(data)
                val projected = rows(data)
                assertEquals(FinanceTotals(30_000, 0), FinanceProjection.totals(projected)["CNY"])
                assertEquals(30_000L, FinanceProjection.categoryTotals(projected).sumOf { it.totals.expenseMinor })
                val unresolved = FinancePersonalProjection.unassignedAdjustments(data, FinanceProjection.ledger(data)).single()
                assertEquals(FinanceAdjustmentIssue.EXCEEDS_PAYMENT_COST, unresolved.issue)
                assertEquals(38_000L, unresolved.amountMinor)
                assertEquals(role, unresolved.role)
                assertEquals(data.entries.single { it.id == "adjust" }, unresolved.row.entry)
                assertEquals(FinanceTotals(30_000, 38_000), FinancePersonalProjection.realTotals(FinanceProjection.ledger(data))["CNY"])
                assertEquals(8_000L, FinanceProjection.balance(account, data.entries))
            }
        }
    }

    @Test fun cumulativeRefundRebateAndCashbackAreCheckedAgainstFullEventEvenInFilteredPeriod() {
        val data = snapshot(FinanceNature.PERSONAL, flows = listOf(entry("pay", 30_000),
            entry("refund", 10_000, FinanceDirection.INFLOW), entry("rebate", 20_000, FinanceDirection.INFLOW),
            entry("cashback", 8_000, FinanceDirection.INFLOW)), roles = listOf(FinanceFlowRole.PAYMENT,
            FinanceFlowRole.REFUND, FinanceFlowRole.REBATE, FinanceFlowRole.CASHBACK))
        assertEquals(FinanceTotals(30_000, 0), FinanceProjection.totals(rows(data))["CNY"])
        val ledger = FinanceProjection.ledger(data)
        assertEquals(38_000L, FinancePersonalProjection.unassignedAdjustments(data, ledger).sumOf { it.amountMinor })
        val onlyRefund = ledger.filter { it.entry.id == "refund" }
        assertTrue(FinancePersonalProjection.rows(data, onlyRefund).isEmpty())
        assertEquals(FinanceAdjustmentIssue.EXCEEDS_PAYMENT_COST,
            FinancePersonalProjection.unassignedAdjustments(data, onlyRefund).single().issue)
        assertEquals(FinanceProjection.totals(rows(data)), FinanceProjection.totals(rows(data.copy(entries = data.entries.reversed()))))
        val exactlyPaid = snapshot(FinanceNature.PERSONAL, flows = listOf(entry("pay", 30_000),
            entry("refund", 10_000, FinanceDirection.INFLOW), entry("rebate", 20_000, FinanceDirection.INFLOW)),
            roles = listOf(FinanceFlowRole.PAYMENT, FinanceFlowRole.REFUND, FinanceFlowRole.REBATE))
        assertEquals(FinanceTotals(0, 0), FinanceProjection.totals(rows(exactlyPaid))["CNY"])
        assertTrue(FinancePersonalProjection.unassignedAdjustments(exactlyPaid, FinanceProjection.ledger(exactlyPaid)).isEmpty())
    }

    @Test fun feeCategoryAndTagDoNotMakePaymentAttributionAmbiguous() {
        val base = snapshot(FinanceNature.PERSONAL, flows = listOf(entry("pay", 30_000),
            entry("fee", 300, category = "fee"), entry("refund", 10_000, FinanceDirection.INFLOW, "refund")),
            roles = listOf(FinanceFlowRole.PAYMENT, FinanceFlowRole.FEE, FinanceFlowRole.REFUND))
        val data = base.copy(categories = base.categories + FinanceCategoryEntity("fee", "手续费"),
            tags = base.tags + FinanceTagEntity("fee-tag", "手续费标签"),
            entryTags = base.entryTags.map { if (it.entryId == "fee") it.copy(tagId = "fee-tag") else it })
        FinanceIntegrity.validate(data)
        val projected = rows(data)
        assertEquals(FinanceTotals(20_300, 0), FinanceProjection.totals(projected)["CNY"])
        val categoryTotals = FinanceProjection.categoryTotals(projected)
        assertEquals(20_000L, categoryTotals.single { it.label == "穿着 / 衣服" }.totals.expenseMinor)
        assertEquals(300L, categoryTotals.single { it.label == "手续费" }.totals.expenseMinor)
        assertFalse(categoryTotals.any { it.label == "调整待归属" })
        val tagTotals = FinanceProjection.tagTotals(projected)
        assertEquals(20_000L, tagTotals.single { it.label == "穿着标签" }.totals.expenseMinor)
        assertEquals(300L, tagTotals.single { it.label == "手续费标签" }.totals.expenseMinor)
        assertFalse(tagTotals.any { it.label in setOf("调整待归属", "到账标签") })
        assertTrue(FinancePersonalProjection.unassignedAdjustments(data, FinanceProjection.ledger(data)).isEmpty())
    }

    @Test fun feeAloneCannotSupplyPaymentCostForAnAdjustment() {
        val data = snapshot(FinanceNature.PERSONAL, flows = listOf(entry("fee", 300),
            entry("refund", 100, FinanceDirection.INFLOW)), roles = listOf(FinanceFlowRole.FEE, FinanceFlowRole.REFUND))
        assertEquals(FinanceTotals(300, 0), FinanceProjection.totals(rows(data))["CNY"])
        assertEquals(FinanceAdjustmentIssue.NO_ACTIVE_PAYMENT_COST,
            FinancePersonalProjection.unassignedAdjustments(data, FinanceProjection.ledger(data)).single().issue)
        assertEquals(-200L, FinanceProjection.balance(account, data.entries))
    }

    @Test fun excludedCashbackCannotMakeIncludedRefundUnassigned() {
        val data = snapshot(FinanceNature.PERSONAL, flows = listOf(entry("pay", 30_000),
            entry("refund", 10_000, FinanceDirection.INFLOW, "refund"),
            entry("cashback", 25_000, FinanceDirection.INFLOW).copy(statPolicy = FinanceStatPolicy.EXCLUDE)),
            roles = listOf(FinanceFlowRole.PAYMENT, FinanceFlowRole.REFUND, FinanceFlowRole.CASHBACK))
        FinanceIntegrity.validate(data)
        val ledger = FinanceProjection.ledger(data)
        val projected = rows(data)
        assertEquals(FinanceTotals(20_000, 0), FinanceProjection.totals(projected)["CNY"])
        assertEquals(setOf("pay", "refund"), projected.map { it.entry.id }.toSet())
        val refund = projected.single { it.entry.id == "refund" }
        assertEquals("穿着 / 衣服", refund.category)
        assertEquals(listOf(FinanceDimensionTotal("穿着 / 衣服", "CNY", FinanceTotals(20_000, 0))),
            FinanceProjection.categoryTotals(projected))
        assertEquals(listOf("穿着标签"), FinanceProjection.tagTotals(listOf(refund)).map { it.label })
        assertTrue(FinancePersonalProjection.unassignedAdjustments(data, ledger).isEmpty())
        assertEquals(data.entries.single { it.id == "cashback" }, ledger.single { it.entry.id == "cashback" }.entry)
        assertEquals(FinanceTotals(30_000, 35_000), FinancePersonalProjection.realTotals(ledger)["CNY"])
        assertEquals(5_000L, FinanceProjection.balance(account, data.entries))
    }

    @Test fun excludedExcessRefundRemainsRealInflowWithoutPersonalStatisticsOrWarning() {
        val data = snapshot(FinanceNature.PERSONAL, flows = listOf(entry("pay", 30_000),
            entry("refund", 38_000, FinanceDirection.INFLOW).copy(statPolicy = FinanceStatPolicy.EXCLUDE)),
            roles = listOf(FinanceFlowRole.PAYMENT, FinanceFlowRole.REFUND))
        FinanceIntegrity.validate(data)
        val ledger = FinanceProjection.ledger(data)
        val projected = rows(data)
        assertEquals(FinanceTotals(30_000, 0), FinanceProjection.totals(projected)["CNY"])
        assertEquals("pay", projected.single().entry.id)
        assertTrue(FinancePersonalProjection.unassignedAdjustments(data, ledger).isEmpty())
        assertEquals(data.entries.single { it.id == "refund" }, ledger.single { it.entry.id == "refund" }.entry)
        assertEquals(FinanceTotals(30_000, 38_000), FinancePersonalProjection.realTotals(ledger)["CNY"])
        assertEquals(8_000L, FinanceProjection.balance(account, data.entries))
    }

    @Test fun excludedPaymentCannotProduceNegativeIncludedPersonalCost() {
        val base = snapshot(FinanceNature.PERSONAL, flows = listOf(entry("pay", 30_000),
            entry("refund", 10_000, FinanceDirection.INFLOW)), roles = listOf(FinanceFlowRole.PAYMENT, FinanceFlowRole.REFUND))
        val data = base.copy(entries = base.entries.map { if (it.id == "pay") it.copy(statPolicy = FinanceStatPolicy.EXCLUDE) else it })
        FinanceIntegrity.validate(data)
        val ledger = FinanceProjection.ledger(data)
        assertTrue(rows(data).isEmpty())
        val unresolved = FinancePersonalProjection.unassignedAdjustments(data, ledger).single()
        assertEquals(FinanceAdjustmentIssue.NO_ACTIVE_PAYMENT_COST, unresolved.issue)
        assertEquals(10_000L, unresolved.amountMinor)
        assertEquals(FinanceTotals(30_000, 10_000), FinancePersonalProjection.realTotals(ledger)["CNY"])
        assertEquals(-20_000L, FinanceProjection.balance(account, data.entries))
    }

    @Test fun splitAndReimbursementCountOnlyConfirmedShareProxyNeverBecomesPersonalConsumption() {
        for (nature in listOf(FinanceNature.SPLIT, FinanceNature.REIMBURSABLE)) {
            val data = snapshot(nature, 10_001, listOf(entry("one", 20_000), entry("two", 10_000)),
                listOf(FinanceFlowRole.PAYMENT, FinanceFlowRole.PAYMENT))
            assertEquals(10_001L, FinanceProjection.totals(rows(data))["CNY"]!!.expenseMinor)
            assertEquals(-30_000L, FinanceProjection.balance(account, data.entries))
            assertTrue(rows(data.copy(v4 = data.v4!!.copy(events = data.v4!!.events.map { it.copy(personalShareMinor = null) }))).isEmpty())
        }
        val proxy = snapshot(FinanceNature.PROXY_PURCHASE, flows = listOf(entry("pay", 30_000)),
            roles = listOf(FinanceFlowRole.PAYMENT))
        assertTrue(rows(proxy).isEmpty())
        assertEquals(-30_000L, FinanceProjection.balance(account, proxy.entries))
    }

    @Test fun actualSettlementAndReimbursementDoNotAddIncomeToConfirmedPersonalShare() {
        for ((nature, role) in listOf(FinanceNature.SPLIT to FinanceFlowRole.SETTLEMENT,
            FinanceNature.REIMBURSABLE to FinanceFlowRole.REIMBURSEMENT)) {
            val data = snapshot(nature, 10_000, flows = listOf(entry("pay", 30_000),
                entry("incoming", 20_000, FinanceDirection.INFLOW, null)),
                roles = listOf(FinanceFlowRole.PAYMENT, role))
            FinanceIntegrity.validate(data)
            assertEquals(FinanceTotals(10_000, 0), FinanceProjection.totals(rows(data))["CNY"])
            assertEquals(FinanceTotals(30_000, 20_000), FinancePersonalProjection.realTotals(FinanceProjection.ledger(data))["CNY"])
            assertEquals(-10_000L, FinanceProjection.balance(account, data.entries))
        }
    }

    @Test fun historicalLinkAndExcludedFlowCannotDoubleCountCurrentPersonalStatistics() {
        val data = snapshot(FinanceNature.PERSONAL, flows = listOf(entry("pay", 30_000)), roles = listOf(FinanceFlowRole.PAYMENT))
        val old = data.v4!!.links.single().copy(id = "historical", allocatedMinor = 50_000, voidedAt = 3_000, updatedAt = 3_000, revision = 2)
        val history = data.copy(v4 = data.v4!!.copy(links = data.v4!!.links + old))
        FinanceIntegrity.validate(history)
        assertEquals(30_000L, FinanceProjection.totals(rows(history))["CNY"]!!.expenseMinor)
        assertTrue(rows(history.copy(entries = history.entries.map { it.copy(statPolicy = FinanceStatPolicy.EXCLUDE) })).isEmpty())
    }
}
