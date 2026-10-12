package com.qq.closie.life.finance

import org.junit.Assert.*
import org.junit.Test

class FinanceRelationsProjectionTest {
    @Test fun partialReturnsAndProxyProfitAreDerivedWithoutChangingGrossEntries() {
        val account = FinanceAccountEntity("a", "合成卡", FinanceAccountKind.BANK_CARD, "CNY", 0, 0, 0)
        val reimburse = FinanceEventEntity("r", "合成出差", FinanceNature.REIMBURSABLE, "CNY", 1, 1, 1, 0)
        val proxy = FinanceEventEntity("p", "合成代付", FinanceNature.PROXY_PURCHASE, "CNY", 2, 2, 2)
        fun entry(id: String, amount: Long, direction: FinanceDirection) =
            FinanceEntryEntity(id, "a", direction, amount, "合成流水", 3, 3, 3)
        val entries = listOf(entry("rp", 84_200, FinanceDirection.OUTFLOW), entry("rr", 50_000, FinanceDirection.INFLOW),
            entry("pp", 79_500, FinanceDirection.OUTFLOW), entry("ps", 78_000, FinanceDirection.INFLOW), entry("pr", 2_000, FinanceDirection.INFLOW))
        val links = listOf(FinanceEventEntryLinkEntity("r", "rp", FinanceFlowRole.PAYMENT, 84_200),
            FinanceEventEntryLinkEntity("r", "rr", FinanceFlowRole.REIMBURSEMENT, 50_000),
            FinanceEventEntryLinkEntity("p", "pp", FinanceFlowRole.PAYMENT, 79_500),
            FinanceEventEntryLinkEntity("p", "ps", FinanceFlowRole.SETTLEMENT, 78_000),
            FinanceEventEntryLinkEntity("p", "pr", FinanceFlowRole.REBATE, 2_000))
        val snapshot = FinanceSnapshot(accounts = listOf(account), entries = entries,
            v4 = FinanceV4Snapshot(events = listOf(reimburse, proxy), links = links))
        val matters = FinanceRelationsProjection.matters(snapshot).associateBy { it.event.id }
        assertEquals(84_200L, matters.getValue("r").paidMinor)
        assertEquals(34_200L, matters.getValue("r").pendingMinor)
        assertEquals(500L, matters.getValue("p").differenceMinor)
        assertEquals(0L, matters.getValue("p").pendingMinor)
        assertEquals(entries, snapshot.entries)
        assertEquals(-33_700L, FinanceProjection.balance(account, snapshot.entries))
    }
}
