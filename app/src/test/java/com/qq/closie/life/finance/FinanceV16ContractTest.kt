package com.qq.closie.life.finance

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.qq.closie.data.backup.*
import com.qq.closie.life.data.database.LifeDatabase
import kotlinx.coroutines.test.runTest
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FinanceV16ContractTest {
    private lateinit var db: LifeDatabase
    private lateinit var cash: FinanceRepository
    private lateinit var economics: FinanceEconomicRepository
    private var now = 1_000L
    private val zone = ZoneId.of("Asia/Shanghai")
    private fun day(month: Int, day: Int = 10) = LocalDate.of(2026, month, day).atStartOfDay(zone).toInstant().toEpochMilli()

    @Before fun setup() {
        RestoreStartupGate.resetForTesting(); RestoreStartupGate.markReady()
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), LifeDatabase::class.java)
            .allowMainThreadQueries().build()
        cash = FinanceRepository(db) { now }
        economics = FinanceEconomicRepository(db, cash) { now }
    }
    @After fun close() { db.close(); RestoreStartupGate.resetForTesting() }
    private suspend fun bank(currency: String = "CNY", balance: Long = 100_000) =
        cash.saveAccount(name = "合成账户", kind = FinanceAccountKind.BANK_CARD, currencyCode = currency, openingBalanceMinor = balance)
    private suspend fun external(value: Long? = 12_000, gift: Long? = value, repayable: Long? = null,
        unresolved: Boolean = false): String {
        val friend = economics.saveParty(economics.token(), "合成朋友")
        val fundId = java.util.UUID.randomUUID().toString()
        val funding = FinanceFundingInput(fundId, friend, value ?: 12_000, basis = "用户确认外部付款")
        val shares = if (unresolved) listOf(FinanceShareInput("unknown:" + fundId, value, "self", null, fundId, FinanceShareRelation.UNRESOLVED))
        else listOfNotNull(
            gift?.let { FinanceShareInput("gift:" + fundId, it, "self", friend, fundId, FinanceShareRelation.GIFT_CONFIRMED) },
            repayable?.let { FinanceShareInput("repay:" + fundId, it, "self", "self", fundId, FinanceShareRelation.REPAYABLE_CONFIRMED) })
        return economics.confirmEconomic(economics.token(), FinanceEconomicInput(description = "", currencyCode = "CNY",
            occurredAt = day(9), valueMinor = value, valueBasis = "合成确认", categoryId = null,
            allocationsComplete = true, funding = listOf(funding), shares = shares))
    }

    @Test fun T01_giftHasValueButNoOwnCashCostOrDebt() = runTest {
        val eventId = external()
        val state = cash.snapshot()
        assertTrue(state.entries.isEmpty())
        assertTrue(state.v16!!.obligationRows.isEmpty())
        assertEquals(12_000L, state.v16!!.economicRows.single().valueMinor)
        assertEquals(PersonalCostTotal(0, 0), FinanceCostProjection.eventTotal(state, eventId))
        FinanceIntegrity.validate(state)
    }

    @Test fun T02_repayableCreatesDebtAndRepaymentDoesNotConsumeTwice() = runTest {
        val eventId = external(gift = null, repayable = 12_000)
        val account = bank()
        val debt = cash.snapshot().v16!!.obligationRows.single()
        assertEquals(12_000L, FinanceCostProjection.eventTotal(cash.snapshot(), eventId).confirmedMinor)
        economics.recordSettlement(economics.token(), debt.id, debt.revision, FinanceCashInput(account.id, 12_000, day(10)))
        val state = cash.snapshot()
        assertEquals(88_000L, FinanceProjection.balance(account, state.entries))
        assertEquals(12_000L, FinanceCostProjection.eventTotal(state, eventId).confirmedMinor)
        assertEquals(0L, FinanceObligationProjection.rows(state).single().openMinor)
    }

    @Test fun T03_mixedGiftDebtAndPartialSettlementConserveAmounts() = runTest {
        val eventId = external(30_000, 10_000, 20_000)
        val account = bank()
        val debt = cash.snapshot().v16!!.obligationRows.single()
        economics.recordSettlement(economics.token(), debt.id, debt.revision, FinanceCashInput(account.id, 8_000, day(10)))
        val state = cash.snapshot()
        assertEquals(20_000L, FinanceCostProjection.eventTotal(state, eventId).confirmedMinor)
        assertEquals(12_000L, FinanceObligationProjection.rows(state).single().openMinor)
        assertEquals(92_000L, FinanceProjection.balance(account, state.entries))
    }

    @Test fun T04_unknownRelationshipIsNotZeroAndDoesNotInventDebt() = runTest {
        val eventId = external(unresolved = true)
        val state = cash.snapshot()
        assertEquals(PersonalCostTotal(0, 1), FinanceCostProjection.eventTotal(state, eventId))
        assertTrue(FinanceCostProjection.rows(state).single().amountMinor == null)
        assertTrue(state.v16!!.obligationRows.isEmpty())
        assertTrue(state.entries.isEmpty())
    }

    @Test fun T05_incompleteUnequalAllocationRetainsUnassignedPortion() = runTest {
        val friend = economics.saveParty(economics.token(), "合成朋友")
        val other = economics.saveParty(economics.token(), "合成参与方")
        val input = FinanceEconomicInput(description = "三人分配", currencyCode = "CNY", occurredAt = day(9),
            valueMinor = 30_000, valueBasis = "用户确认", categoryId = null, allocationsComplete = false,
            funding = listOf(FinanceFundingInput("fund", friend, 30_000, basis = "外部付款")),
            shares = listOf(FinanceShareInput("one", 10_000, "self", friend, "fund", FinanceShareRelation.GIFT_CONFIRMED),
                FinanceShareInput("two", 8_000, other, friend, "fund", FinanceShareRelation.GIFT_CONFIRMED)))
        val eventId = economics.confirmEconomic(economics.token(), input)
        assertFalse(FinanceCostProjection.eventTotal(cash.snapshot(), eventId).complete)
        assertEquals(1, FinanceCostProjection.rows(cash.snapshot()).count { it.amountMinor == null })
        val before = cash.snapshot()
        try { economics.confirmEconomic(economics.token(), input.copy(eventId = eventId,
            expectedRevision = before.v16!!.economicRows.single().revision, allocationsComplete = true)); fail() }
        catch (_: IllegalArgumentException) { }
        assertEquals(before, cash.snapshot())
        val complete=input.copy(eventId=eventId,expectedRevision=before.v16!!.economicRows.single().revision,
            allocationsComplete=true,shares=input.shares+FinanceShareInput("three",12_000,"self","self","fund",FinanceShareRelation.REPAYABLE_CONFIRMED))
        economics.confirmEconomic(economics.token(),complete)
        val after=cash.snapshot()
        assertEquals(30_000L,exactSum(after.v16!!.shareRows.filter {it.voidedAt==null}.map {requireNotNull(it.amountMinor)}))
        assertEquals(PersonalCostTotal(12_000,0),FinanceCostProjection.eventTotal(after,eventId))
        assertEquals(12_000L,FinanceObligationProjection.rows(after).single().openMinor)
        assertTrue(after.entries.isEmpty())
        FinanceIntegrity.validate(after)
    }

    @Test fun T06_historicalResolutionClosesWorkWithoutManufacturingBankEvidence() = runTest {
        external(gift = null, repayable = 12_000)
        val debt = cash.snapshot().v16!!.obligationRows.single()
        economics.resolveWithoutCash(economics.token(), debt.id, debt.revision,
            FinanceResolutionKind.HISTORICAL_SETTLED, 12_000, "历史声明，账户及日期未知")
        val state = cash.snapshot()
        assertTrue(state.entries.isEmpty())
        val work = FinanceObligationProjection.rows(state).single()
        assertEquals(0L, work.openMinor)
        assertFalse(work.cashEvidenceComplete)
        assertEquals(12_000L, work.nonCashResolvedMinor)
    }

    @Test fun T07_refundAndRebateBelongToActualMonthAndAllowNegativeBudgetUse() = runTest {
        val account = bank()
        val paid = cash.saveEntry(accountId = account.id, direction = FinanceDirection.OUTFLOW,
            amountMinor = 20_000, description = "", occurredAt = day(9), event = FinanceEventInput())
        val state = cash.snapshot()
        val event = state.v4!!.events.single()
        val link = state.v4!!.activeLinks.single()
        economics.confirmEconomic(economics.token(), FinanceEconomicInput(event.id, description = "", currencyCode = "CNY",
            occurredAt = day(9), valueMinor = 20_000, valueBasis = "确认购买", categoryId = null, allocationsComplete = true,
            funding = listOf(FinanceFundingInput("self-fund", "self", 20_000, link.id, "真实本人付款")),
            shares = listOf(FinanceShareInput("self-share", 20_000, "self", "self", "self-fund", FinanceShareRelation.PERSONAL_CONFIRMED))))
        suspend fun revision() = cash.snapshot().v16!!.economicRows.single().revision
        economics.recordAdjustment(economics.token(), event.id, revision(), FinanceFlowRole.REFUND,
            FinanceCashInput(account.id, 3_000, day(10)), 3_000)
        economics.recordAdjustment(economics.token(), event.id, revision(), FinanceFlowRole.REBATE,
            FinanceCashInput(account.id, 1_000, day(10, 11)), 1_000)
        val rows = FinanceCostProjection.rows(cash.snapshot())
        assertEquals(20_000L, FinanceCostProjection.total(rows.filter { it.occurredAt < day(10, 1) }).confirmedMinor)
        assertEquals(-4_000L, FinanceCostProjection.total(rows.filter { it.occurredAt >= day(10, 1) }).confirmedMinor)
        assertEquals(16_000L, FinanceCostProjection.total(rows).confirmedMinor)
        assertEquals(84_000L, FinanceProjection.balance(account, cash.snapshot().entries))
        assertEquals(paid.recordedAt, cash.snapshot().entries.first { it.id == paid.id }.recordedAt)
    }

    @Test fun T08_duplicateConfirmationAndRestoreEpochCannotCreateSecondCash() = runTest {
        external(gift = null, repayable = 12_000)
        val account = bank()
        val debt = cash.snapshot().v16!!.obligationRows.single()
        val token = economics.token()
        val request = FinanceCashInput(account.id, 8_000, day(10))
        val first = economics.recordSettlement(token, debt.id, debt.revision, request)
        assertEquals(first, economics.recordSettlement(token, debt.id, debt.revision, request))
        assertEquals(1, cash.snapshot().entries.size)
        val before=cash.snapshot();val v=before.v16!!
        val economic=v.economicRows.single();val funding=v.fundingRows.single();val share=v.shareRows.single()
        val edit=FinanceEconomicInput(eventId=debt.eventId,expectedRevision=economic.revision,description="",currencyCode="CNY",
            occurredAt=day(9),valueMinor=12_000,valueBasis="合成明确更正",categoryId=null,allocationsComplete=true,
            funding=listOf(FinanceFundingInput(funding.id,funding.partyId,funding.amountMinor,funding.entryLinkId,funding.basis)),
            shares=listOf(FinanceShareInput(share.id,share.amountMinor,share.beneficiaryId,share.bearerId,share.fundingId,share.relation)))
        try {economics.confirmEconomic(economics.token(),edit.copy(expectedRevision=economic.revision-1));fail()}
        catch (_:IllegalArgumentException) { }
        assertEquals(before,cash.snapshot())
        val gift=edit.copy(shares=listOf(edit.shares.single().copy(bearerId=funding.partyId,relation=FinanceShareRelation.GIFT_CONFIRMED)))
        try {economics.confirmEconomic(economics.token(),gift);fail()}
        catch (_:IllegalArgumentException) { }
        assertEquals(before,cash.snapshot()) // Existing repayment and its obligation cannot be erased by an enum edit.
        assertEquals(4_000L,FinanceObligationProjection.rows(cash.snapshot()).single().openMinor)
        assertEquals(12_000L,FinanceCostProjection.eventTotal(cash.snapshot(),debt.eventId).confirmedMinor)
        val snapshot = LifeBackupApplier.snapshot(db)
        LifeBackupApplier.restoreSnapshot(db, snapshot)
        try { economics.recordSettlement(token, debt.id, debt.revision, request); fail() }
        catch (_: IllegalArgumentException) { }
        assertEquals(1, cash.snapshot().entries.size)
    }

    @Test fun T12_currencyNetAndLegacyAnchorDoNotChangeByAccountKind() = runTest {
        val cny = bank(balance = 100_000)
        val usd = bank("USD", 2_000)
        val credit = cash.saveAccount(name = "合成信用账户", kind = FinanceAccountKind.CREDIT, openingBalanceMinor = -10_000)
        cash.saveEntry(accountId = cny.id, direction = FinanceDirection.OUTFLOW, amountMinor = 500,
            description = "", occurredAt = cny.balanceAnchorAt - 1)
        val state = cash.snapshot()
        assertEquals(mapOf("CNY" to 90_000L, "USD" to 2_000L), FinanceAccountProjection.netByCurrency(state))
        assertEquals(cny.balanceAnchorAt, state.accounts.first { it.id == cny.id }.balanceAnchorAt)
        assertEquals(-10_000L, FinanceProjection.balance(credit, state.entries))
        assertEquals(2_000L, FinanceProjection.balance(usd, state.entries))
    }

    @Test fun positiveLegacyDebtNormalizesOpeningOnlyAndInternalRepaymentPreservesNet() = runTest {
        val bank=bank()
        val credit=cash.saveAccount(name="旧正数欠款",kind=FinanceAccountKind.CREDIT,openingBalanceMinor=10_000)
        // Kind alone never supplies a sign decision.
        assertEquals(110_000L,FinanceAccountProjection.netByCurrency(cash.snapshot())["CNY"])
        assertTrue(FinanceAccountProjection.liabilitySignPending(cash.snapshot(),credit))
        cash.workspace.setAccountPolicy(economics.token(),credit.id,true,FinanceLiabilityConvention.POSITIVE_DEBT,
            FinanceBalanceBasis.LEGACY_CASH,null)
        assertFalse(FinanceAccountProjection.liabilitySignPending(cash.snapshot(),credit))
        cash.saveEntry(accountId=credit.id,direction=FinanceDirection.OUTFLOW,amountMinor=99_000,
            description="起点前历史",occurredAt=credit.balanceAnchorAt-1)
        assertEquals(90_000L,FinanceAccountProjection.netByCurrency(cash.snapshot())["CNY"])
        cash.saveTransfer(sourceAccountId=bank.id,targetAccountId=credit.id,amountMinor=5_000,
            description="真实还款",occurredAt=now)
        var s=cash.snapshot()
        assertEquals(90_000L,FinanceAccountProjection.netByCurrency(s)["CNY"])
        assertEquals(-5_000L,FinanceAccountProjection.netContribution(s,credit))
        assertEquals(15_000L,FinanceProjection.balance(credit,s.entries))
        assertEquals(15_000L,FinanceAccountProjection.register(s,credit.id).first().balanceMinor)
        assertNull(FinanceAccountProjection.register(s,credit.id).last().balanceMinor)
        cash.saveEntry(accountId=credit.id,direction=FinanceDirection.OUTFLOW,amountMinor=2_000,
            description="又一笔真实支出",occurredAt=now+1)
        assertEquals(-7_000L,FinanceAccountProjection.netContribution(cash.snapshot(),credit))
        cash.saveTransfer(sourceAccountId=bank.id,targetAccountId=credit.id,amountMinor=10_000,
            description="明确超额还款",occurredAt=now+2)
        s=cash.snapshot()
        assertEquals(3_000L,FinanceAccountProjection.netContribution(s,credit))
        assertEquals(88_000L,FinanceAccountProjection.netByCurrency(s)["CNY"])
        assertEquals(23_000L,FinanceProjection.balance(credit,s.entries))
    }

    @Test fun negativeOpeningCannotBeConvertedTwiceAndNetExclusionIsExplicit() = runTest {
        val primary=bank()
        val credit=cash.saveAccount(name="已是负数",kind=FinanceAccountKind.CREDIT,openingBalanceMinor=-10_000)
        val usd=bank("USD",2_000)
        val before=cash.snapshot()
        assertTrue(runCatching {cash.workspace.setAccountPolicy(economics.token(),credit.id,true,
            FinanceLiabilityConvention.POSITIVE_DEBT,FinanceBalanceBasis.LEGACY_CASH,null)}.isFailure)
        assertEquals(before,cash.snapshot())
        cash.workspace.setAccountPolicy(economics.token(),credit.id,false,FinanceLiabilityConvention.SIGNED,
            FinanceBalanceBasis.LEGACY_CASH,null)
        assertEquals(mapOf("CNY" to 100_000L,"USD" to 2_000L),FinanceAccountProjection.netByCurrency(cash.snapshot()))
        cash.archiveAccount(usd.id)
        assertEquals(mapOf("CNY" to 100_000L),FinanceAccountProjection.netByCurrency(cash.snapshot()))
        assertEquals(100_000L,FinanceProjection.balance(primary,cash.snapshot().entries))
    }

    @Test fun accountCreationAnchorsAndStaleMetadataKeepCanonicalHistoryIntact() = runTest {
        val historical=cash.saveAccount(name="自选起点",kind=FinanceAccountKind.CASH,openingBalanceMinor=500,balanceAnchorAt=100)
        val zero=cash.saveAccount(name="从零",kind=FinanceAccountKind.CASH,balanceAnchorAt=0)
        val current=bank()
        assertEquals(100L,historical.balanceAnchorAt)
        assertEquals(0L,zero.balanceAnchorAt)
        assertEquals(now,current.balanceAnchorAt)
        cash.saveEntry(accountId=historical.id,direction=FinanceDirection.INFLOW,amountMinor=10,
            description="起点边界",occurredAt=100)
        cash.saveEntry(accountId=historical.id,direction=FinanceDirection.OUTFLOW,amountMinor=20,
            description="起点前",occurredAt=99)
        assertEquals(510L,FinanceProjection.balance(historical,cash.snapshot().entries))
        cash.saveAccount(id=historical.id,name="第一次改名",kind=historical.kind,openingBalanceMinor=500,
            expectedUpdatedAt=historical.updatedAt)
        val before=cash.snapshot()
        assertTrue(runCatching {cash.workspace.setAccountPolicy(economics.token(),historical.id,true,
            FinanceLiabilityConvention.POSITIVE_DEBT,FinanceBalanceBasis.LEGACY_CASH,null,historical.updatedAt)}.isFailure)
        assertTrue(runCatching {cash.saveAccount(id=historical.id,name="旧表单改名",kind=historical.kind,
            openingBalanceMinor=500,expectedUpdatedAt=historical.updatedAt)}.isFailure)
        assertTrue(runCatching {cash.saveAccount(id=historical.id,name="旁路改起点",kind=historical.kind,
            openingBalanceMinor=500,balanceAnchorAt=200)}.isFailure)
        assertTrue(runCatching {cash.saveAccount(name="未来起点",kind=historical.kind,balanceAnchorAt=now+1)}.isFailure)
        assertEquals(before,cash.snapshot())
    }

    @Test fun settlementCannotExceedItsEventAllocationAfterRelinking() = runTest {
        val firstEvent = external(gift = null, repayable = 12_000)
        val otherEvent = external()
        val account = bank()
        val debt = cash.snapshot().v16!!.obligationRows.single()
        val entryId = economics.recordSettlement(economics.token(), debt.id, debt.revision,
            FinanceCashInput(account.id, 12_000, day(10)))
        val before = cash.snapshot()
        val entry = before.entries.single()
        assertTrue(runCatching { cash.allocateEntry(entryId, listOf(FinanceAllocation(firstEvent, FinanceFlowRole.SETTLEMENT, 6_000),
            FinanceAllocation(otherEvent, FinanceFlowRole.SETTLEMENT, 6_000)), entry.revision) }.isFailure)
        assertEquals(before, cash.snapshot())
    }

    @Test fun restorationRetiresPolicyAndSelfCanBeUsedAgain() = runTest {
        val account = bank()
        val early = LifeBackupApplier.snapshot(db)
        external()
        db.financeV16Dao().putAccountPolicy(listOf(FinanceAccountPolicyEntity(account.id, false,
            FinanceLiabilityConvention.POSITIVE_DEBT, createdAt = now, updatedAt = now)))
        LifeBackupApplier.restoreSnapshot(db, early)
        assertEquals(mapOf("CNY" to 100_000L), FinanceAccountProjection.netByCurrency(cash.snapshot()))
        external(gift = null, repayable = 12_000)
        assertEquals(1, FinanceObligationProjection.rows(cash.snapshot()).size)
    }

    @Test fun T11_encryptedTypedPayloadRestoresOnAnotherDatabaseWithGrantsRevoked() = runTest {
        val gift = external()
        external(unresolved = true)
        external(gift = null, repayable = 12_000)
        db.financeV16Dao().putPurposeGrant(listOf(FinancePurposeGrantEntity("home", FinanceReadPurpose.HOME,
            "home", true, true, true, Long.MAX_VALUE, now, now)))
        db.financeV16Dao().putGrantEvent(listOf(FinanceGrantEventEntity("home", gift)))
        val snapshot = LifeBackupApplier.snapshot(db)
        val plain = com.google.gson.Gson().toJson(snapshot).toByteArray()
        val encrypted = java.io.ByteArrayOutputStream()
        EncryptedBackupEnvelope.encrypt(java.io.ByteArrayInputStream(plain), encrypted, "synthetic-only-password".toCharArray())
        val decoded = java.io.ByteArrayOutputStream()
        EncryptedBackupEnvelope.decrypt(java.io.ByteArrayInputStream(encrypted.toByteArray()), decoded, "synthetic-only-password".toCharArray())
        val payload = com.google.gson.Gson().fromJson(decoded.toString("UTF-8"), LifeBackupPayload::class.java)
        val target = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), LifeDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            LifeBackupApplier.restoreSnapshot(target, payload)
            val restored = FinanceRepository(target).snapshot()
            assertEquals(snapshot.finance!!.v4!!.events.map { it.id }.toSet(), restored.v4!!.events.map { it.id }.toSet())
            assertTrue(restored.entries.isEmpty())
            assertEquals(1, FinanceObligationProjection.rows(restored).size)
            assertFalse(FinanceCostProjection.total(FinanceCostProjection.rows(restored)).complete)
            assertTrue(restored.v16!!.purposeGrantRows.all { it.voidedAt != null })
            assertEquals(1L, restored.v16!!.controlRows.single().restoreEpoch)
        } finally { target.close() }
    }
}
