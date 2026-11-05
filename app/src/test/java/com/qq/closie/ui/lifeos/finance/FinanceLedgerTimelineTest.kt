package com.qq.closie.ui.lifeos.finance

import com.qq.closie.life.finance.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class FinanceLedgerTimelineTest {
    private val zone = ZoneOffset.UTC
    private val september = time("2026-09-12")
    private val october = time("2026-10-08")
    private val account = FinanceAccountEntity("bank", "合成账户", FinanceAccountKind.BANK_CARD,
        "CNY", 0, september, september)

    private fun time(date: String) = LocalDate.parse(date).atStartOfDay(zone).toInstant().toEpochMilli()
    private fun cash(id: String, date: Long, direction: FinanceDirection) =
        FinanceEntryEntity(id, account.id, direction, 4_000, "", date, date, date)
    private fun cost(event: String, date: Long, amount: Long? = 20_000) =
        PersonalCostRow("cost-$event", event, null, "CNY", amount, date, null, "合成事项")

    @Test fun externalPaymentBaseAndUnknownShareSurviveALaterRealRefund() {
        for (unknown in listOf(false, true)) {
            val event = FinanceEventEntity("external", "他人付款的事项", FinanceNature.SPLIT,
                "CNY", september, september, september)
            val refund = cash("refund", october, FinanceDirection.INFLOW)
            val link = FinanceEventEntryLinkEntity(event.id, refund.id, FinanceFlowRole.REFUND,
                refund.amountMinor, id = "refund-link", createdAt = october)
            val snapshot = FinanceSnapshot(accounts = listOf(account), entries = listOf(refund),
                v4 = FinanceV4Snapshot(events = listOf(event), links = listOf(link)),
                v16 = FinanceV16Snapshot(
                    partyRows = listOf(
                        FinancePartyEntity("self", "本人", true, september, september),
                        FinancePartyEntity("friend", "合成付款方", false, september, september)
                    ),
                    economicRows = listOf(FinanceEconomicEntity(event.id, valueMinor = 20_000,
                        valueBasis = "合成确认", allocationsComplete = true, createdAt = september, updatedAt = september)),
                    fundingRows = listOf(FinanceFundingEntity("funding", event.id, "friend", 20_000,
                        basis = "他人付款", createdAt = september, updatedAt = september)),
                    shareRows = listOf(FinanceShareEntity("share", event.id, 20_000,
                        beneficiaryId = "self", bearerId = if (unknown) null else "self", fundingId = "funding",
                        relation = if (unknown) FinanceShareRelation.UNRESOLVED else FinanceShareRelation.REPAYABLE_CONFIRMED,
                        createdAt = september, updatedAt = september)),
                    obligationRows = if (unknown) emptyList() else listOf(
                        FinanceObligationEntity("owed", event.id, "share", "friend", "self",
                            20_000, "CNY", september, september)
                    ),
                    costAdjustmentRows = listOf(FinanceCostAdjustmentEntity("adjustment", event.id, link.id,
                        if (unknown) 0 else 4_000, createdAt = october, updatedAt = october))
                ))
            FinanceIntegrity.validate(snapshot)
            val costs = FinanceCostProjection.rows(snapshot)
            val dates = FinanceProjection.economicDates(snapshot)
            val ledger = FinanceProjection.ledger(snapshot)
            val original = unrepresentedEconomicCosts(costs, snapshot, ledger, dates, zone).single()
            assertEquals(september, original.occurredAt)
            if (unknown) assertNull(original.amountMinor) else assertEquals(20_000L, original.amountMinor)
            assertEquals(october, costs.single { it.adjustment }.occurredAt)
            assertEquals(october, ledger.single().entry.occurredAt)
            assertFalse(dates.containsKey(refund.id))
        }
    }

    @Test fun onlyAPaymentActuallyShownForTheSameEventDayReplacesItsEconomicRow() {
        val payment = cash("payment", october, FinanceDirection.OUTFLOW)
        val link = FinanceEventEntryLinkEntity("event", payment.id, FinanceFlowRole.PAYMENT,
            payment.amountMinor, id = "payment-link", createdAt = october)
        val snapshot = FinanceSnapshot(accounts = listOf(account), entries = listOf(payment),
            v4 = FinanceV4Snapshot(links = listOf(link)))
        val costs = listOf(cost("event", september))
        val displayed = FinanceProjection.ledger(snapshot)
        val economicDates = mapOf(payment.id to september)

        assertTrue(unrepresentedEconomicCosts(costs, snapshot, displayed, economicDates, zone).isEmpty())
        // A filtered-out or undated payment cannot take away the economic event's only visible row.
        assertEquals(costs, unrepresentedEconomicCosts(costs, snapshot, emptyList(), economicDates, zone))
        assertEquals(costs, unrepresentedEconomicCosts(costs, snapshot, displayed, emptyMap(), zone))
    }

    @Test fun onePaymentAcrossDifferentEconomicDatesKeepsTheUnrepresentedEvent() {
        val payment = cash("payment", september, FinanceDirection.OUTFLOW)
        val links = listOf(
            FinanceEventEntryLinkEntity("first", payment.id, FinanceFlowRole.PAYMENT, 2_000,
                id = "first-link", createdAt = september),
            FinanceEventEntryLinkEntity("second", payment.id, FinanceFlowRole.PAYMENT, 2_000,
                id = "second-link", createdAt = september)
        )
        val snapshot = FinanceSnapshot(accounts = listOf(account), entries = listOf(payment),
            v4 = FinanceV4Snapshot(links = links))
        val first = cost("first", september)
        val second = cost("second", october, null)
        assertEquals(listOf(second), unrepresentedEconomicCosts(listOf(first, second), snapshot,
            FinanceProjection.ledger(snapshot), emptyMap(), zone))
    }

    @Test fun retiredPaymentAndSettlementLinksCannotHideOriginalEconomicRows() {
        val payment = cash("payment", september, FinanceDirection.OUTFLOW)
        val settlement = cash("settlement", september, FinanceDirection.OUTFLOW)
        val links = listOf(
            FinanceEventEntryLinkEntity("event", payment.id, FinanceFlowRole.PAYMENT,
                payment.amountMinor, id = "old-link", createdAt = september, voidedAt = october),
            FinanceEventEntryLinkEntity("event", settlement.id, FinanceFlowRole.SETTLEMENT,
                settlement.amountMinor, id = "settlement-link", createdAt = september)
        )
        val snapshot = FinanceSnapshot(accounts = listOf(account), entries = listOf(payment, settlement),
            v4 = FinanceV4Snapshot(links = links))
        val costs = listOf(cost("event", september, null))
        assertEquals(costs, unrepresentedEconomicCosts(costs, snapshot,
            FinanceProjection.ledger(snapshot), emptyMap(), zone))
    }
}
