package com.qq.closie.life.finance

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.qq.closie.data.backup.LifeBackupApplier
import com.qq.closie.data.backup.RestoreStartupGate
import com.qq.closie.life.data.database.LifeDatabase
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId

/** Real canonical writes, with balances anchored independently from complete ledger history. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FinanceBalanceAnchorTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val day = LocalDate.of(2026, 10, 9)
    private fun time(hour: Int) = day.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()
    private val anchor = time(18)
    private val yesterday = day.minusDays(1).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
    private var now = anchor
    private lateinit var db: LifeDatabase
    private lateinit var repo: FinanceRepository

    @Before fun setup() {
        RestoreStartupGate.resetForTesting(); RestoreStartupGate.markReady()
        val context: Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, LifeDatabase::class.java).allowMainThreadQueries().build()
        repo = FinanceRepository(db) { now }
    }
    @After fun cleanup() { db.close(); RestoreStartupGate.resetForTesting() }
    private suspend fun account() = repo.saveAccount(name = "银行卡", kind = FinanceAccountKind.BANK_CARD,
        openingBalanceMinor = FinanceMoney.parse("10000"))
    private suspend fun flow(account: String, direction: FinanceDirection, amount: String, occurred: Long,
        reason: String) = repo.saveEntry(accountId = account, direction = direction,
        amountMinor = FinanceMoney.parse(amount), description = reason, occurredAt = occurred,
        category = "餐饮", tags = listOf("补记"))

    @Test fun backfilledExpenseRemainsCanonicalSearchableAndInHistoricalStatistics() = runTest {
        val bank = account()
        val old = flow(bank.id, FinanceDirection.OUTFLOW, "100", yesterday, "昨天的晚餐")
        val data = repo.snapshot()
        assertEquals(anchor, bank.createdAt)
        assertEquals(old, data.entries.single())
        assertNull(data.entries.single().voidedAt)
        assertEquals(anchor, old.recordedAt)
        assertEquals(FinanceMoney.parse("10000"), FinanceProjection.balance(bank, data.entries))

        val range = FinanceDateRange.days(day.minusDays(1), day.minusDays(1), zone)
        val history = FinanceProjection.ledger(data, range)
        assertEquals(old.id, history.single().id)
        for (query in listOf("昨天", "餐饮", "补记", "银行卡")) {
            assertEquals(old.id, FinanceProjection.ledger(data, range, query).single().id)
        }
        assertEquals(FinanceTotals(10_000, 0), FinanceProjection.totals(history)["CNY"])
        assertEquals(10_000L, FinanceProjection.categoryTotals(history).single().totals.expenseMinor)
        assertEquals(10_000L, FinanceProjection.tagTotals(history).single().totals.expenseMinor)
        assertEquals(10_000L, FinanceProjection.totals(FinanceProjection.ledger(data, FinanceDateRange.month(day, zone)))["CNY"]?.expenseMinor)
        assertEquals(10_000L, FinanceProjection.totals(FinanceProjection.ledger(data, FinanceDateRange.year(day, zone)))["CNY"]?.expenseMinor)

        flow(bank.id, FinanceDirection.OUTFLOW, "200", time(19), "开始跟踪后的支出")
        val after = repo.snapshot()
        assertEquals(FinanceMoney.parse("9800"), FinanceProjection.balance(bank, after.entries))
        assertEquals(2, FinanceProjection.ledger(after).size)
        assertEquals(30_000L, FinanceProjection.totals(FinanceProjection.ledger(after))["CNY"]?.expenseMinor)
        assertEquals(listOf(old.id), FinanceProjection.ledger(after, range).map { it.id })
    }

    @Test fun inflowBeforeAnchorIsHistoryWhileAtAndAfterAnchorAffectBalance() = runTest {
        val bank = account()
        val before = flow(bank.id, FinanceDirection.INFLOW, "100", anchor - 1, "起点前收入")
        val at = flow(bank.id, FinanceDirection.INFLOW, "200", anchor, "起点收入")
        flow(bank.id, FinanceDirection.INFLOW, "300", time(19), "起点后收入")
        val data = repo.snapshot()
        assertEquals(FinanceMoney.parse("10500"), FinanceProjection.balance(bank, data.entries))
        assertEquals(60_000L, FinanceProjection.totals(FinanceProjection.ledger(data))["CNY"]?.incomeMinor)
        repo.voidEntry(before.id)
        assertEquals(FinanceMoney.parse("10500"), FinanceProjection.balance(bank, repo.snapshot().entries))
        repo.voidEntry(at.id)
        assertEquals(FinanceMoney.parse("10300"), FinanceProjection.balance(bank, repo.snapshot().entries))
        assertEquals(3, repo.snapshot().entries.size)
    }

    @Test fun transferUsesEachAccountAnchorWithoutChangingItsAtomicFactOrF1Totals() = runTest {
        val bank = account()
        now = time(20)
        val wallet = repo.saveAccount(name = "微信", kind = FinanceAccountKind.WECHAT,
            openingBalanceMinor = FinanceMoney.parse("5000"))
        suspend fun transfer(amount: String, occurred: Long) = repo.saveTransfer(
            sourceAccountId = bank.id, targetAccountId = wallet.id, amountMinor = FinanceMoney.parse(amount),
            description = "跨起点转账", occurredAt = occurred)
        transfer("100", yesterday) // Before both anchors: already reflected in both opening balances.
        val atSource = transfer("200", anchor) // Source boundary is inclusive; target is still historical.
        val between = transfer("300", time(19))
        val atTarget = transfer("500", time(20)) // Included for both accounts.
        val data = repo.snapshot()
        assertEquals(FinanceMoney.parse("9000"), FinanceProjection.balance(bank, data.entries))
        assertEquals(FinanceMoney.parse("5500"), FinanceProjection.balance(wallet, data.entries))
        assertEquals(8, data.entries.size)
        assertEquals(4, data.transfers.size)
        assertEquals(4, FinanceProjection.ledger(data, search = "跨起点").size)
        assertTrue(FinanceProjection.totals(FinanceProjection.ledger(data)).isEmpty())
        assertEquals(2, data.entries.count { it.id == atSource.outflowEntryId || it.id == atSource.inflowEntryId })

        repo.saveTransfer(between.id, bank.id, wallet.id, FinanceMoney.parse("300"), "改为起点前", anchor - 1)
        assertEquals(FinanceMoney.parse("9300"), FinanceProjection.balance(bank, repo.snapshot().entries))
        repo.voidTransfer(atTarget.id)
        val voided = repo.snapshot()
        assertEquals(FinanceMoney.parse("9800"), FinanceProjection.balance(bank, voided.entries))
        assertEquals(FinanceMoney.parse("5000"), FinanceProjection.balance(wallet, voided.entries))
        assertEquals(8, voided.entries.size)
        assertEquals(4, voided.transfers.size)
        FinanceIntegrity.validate(voided)
    }

    @Test fun correctingOccurredAtAcrossAnchorChangesOnlyTheBalanceProjection() = runTest {
        val bank = account()
        val old = flow(bank.id, FinanceDirection.OUTFLOW, "100", yesterday, "需要纠正时间")
        now = time(20)
        val changed = repo.saveEntry(old.id, bank.id, FinanceDirection.OUTFLOW, old.amountMinor, old.description, anchor,
            category = "餐饮", tags = listOf("补记"))
        assertEquals(old.id, changed.id)
        assertEquals(old.recordedAt, changed.recordedAt)
        assertEquals(FinanceMoney.parse("9900"), FinanceProjection.balance(bank, repo.snapshot().entries))
        repo.saveEntry(old.id, bank.id, FinanceDirection.OUTFLOW, old.amountMinor, old.description, yesterday,
            category = "餐饮", tags = listOf("补记"))
        val data = repo.snapshot()
        assertEquals(FinanceMoney.parse("10000"), FinanceProjection.balance(bank, data.entries))
        assertEquals(1, data.entries.size)
        assertNull(data.entries.single().voidedAt)
        assertEquals(10_000L, FinanceProjection.totals(FinanceProjection.ledger(data))["CNY"]?.expenseMinor)
    }

    @Test fun editingOrArchivingAccountDoesNotMoveItsOpeningBalanceAnchor() = runTest {
        val bank = account()
        flow(bank.id, FinanceDirection.OUTFLOW, "100", yesterday, "旧支出")
        flow(bank.id, FinanceDirection.OUTFLOW, "200", time(19), "新支出")
        now = time(20)
        val edited = repo.saveAccount(bank.id, "工资卡", bank.kind, openingBalanceMinor = bank.openingBalanceMinor)
        repo.archiveAccount(bank.id)
        val data = repo.snapshot()
        assertEquals(anchor, edited.createdAt)
        assertEquals(anchor, data.accounts.single().createdAt)
        assertEquals(FinanceMoney.parse("9800"), FinanceProjection.balance(data.accounts.single(), data.entries))
        assertEquals(2, FinanceProjection.ledger(data).size)
    }

    @Test fun backupRecoveryPreservesAnchorAndBackfilledCanonicalRows() = runTest {
        val bank = account()
        flow(bank.id, FinanceDirection.OUTFLOW, "100", yesterday, "旧支出")
        flow(bank.id, FinanceDirection.OUTFLOW, "200", time(19), "新支出")
        val saved = LifeBackupApplier.snapshot(db)
        now = time(21)
        repo.saveAccount(bank.id, "改名", bank.kind, openingBalanceMinor = FinanceMoney.parse("20000"))
        LifeBackupApplier.restoreSnapshot(db, saved)
        val restored = repo.snapshot()
        assertEquals(anchor, restored.accounts.single().createdAt)
        assertEquals(FinanceMoney.parse("9800"), FinanceProjection.balance(restored.accounts.single(), restored.entries))
        assertEquals(saved.finance, restored)
        assertEquals(2, restored.entries.size)
        assertTrue(restored.entries.all { it.voidedAt == null })
    }
}
