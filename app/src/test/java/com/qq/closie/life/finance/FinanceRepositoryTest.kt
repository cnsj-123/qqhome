package com.qq.closie.life.finance

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.qq.closie.data.backup.RestoreStartupGate
import com.qq.closie.life.data.database.LifeDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
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
class FinanceRepositoryTest {
    private lateinit var db: LifeDatabase
    private lateinit var repo: FinanceRepository
    private var now = 1_000L
    private val zone = ZoneId.of("Asia/Shanghai")
    private val day = LocalDate.of(2026, 10, 9)
    private val happened = day.atTime(12, 30).atZone(zone).toInstant().toEpochMilli()

    @Before fun setup() {
        RestoreStartupGate.resetForTesting(); RestoreStartupGate.markReady()
        val context: Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, LifeDatabase::class.java).allowMainThreadQueries().build()
        repo = FinanceRepository(db) { now }
    }
    @After fun cleanup() { db.close(); RestoreStartupGate.resetForTesting() }
    private suspend fun bank(opening: Long = 100_000) = repo.saveAccount(name = "银行卡", kind = FinanceAccountKind.BANK_CARD, openingBalanceMinor = opening)
    private suspend fun wechat() = repo.saveAccount(name = "微信", kind = FinanceAccountKind.WECHAT)
    private suspend fun expense(account: String, amount: Long = 10_000) = repo.saveEntry(accountId = account,
        direction = FinanceDirection.OUTFLOW, amountMinor = amount, description = "豆浆与油条", occurredAt = happened, category = "餐饮", tags = listOf("早餐", "回家"))
    private suspend fun transfer(a: String, b: String) = repo.saveTransfer(sourceAccountId = a, targetAccountId = b,
        amountMinor = 20_000, description = "零钱", occurredAt = happened)

    @Test fun realFlowsBalancesAndF1TotalsHaveSeparateMeanings() = runTest {
        val a = bank(); val b = wechat()
        expense(a.id)
        repo.saveEntry(accountId = b.id, direction = FinanceDirection.INFLOW, amountMinor = 30_000, description = "收入", occurredAt = happened)
        transfer(a.id, b.id)
        val data = repo.snapshot()
        assertEquals(4, data.entries.size); assertEquals(1, data.transfers.size)
        assertEquals(70_000L, FinanceProjection.balance(a, data.entries))
        assertEquals(50_000L, FinanceProjection.balance(b, data.entries))
        val rows = FinanceProjection.ledger(data, FinanceDateRange.month(day, zone))
        assertEquals(3, rows.size); assertEquals(1, rows.count { it.transfer != null })
        assertEquals(FinanceTotals(10_000, 30_000), FinanceProjection.totals(rows)["CNY"])
    }
    @Test fun openingBalanceIsDerivedAndMayBeNegative() = runTest {
        val a = bank(-500)
        assertEquals(-500L, FinanceProjection.balance(a, emptyList()))
        val changed = repo.saveAccount(a.id, "工资卡", FinanceAccountKind.BANK_CARD, openingBalanceMinor = 2_500)
        assertEquals(a.id, changed.id); assertEquals(a.createdAt, changed.createdAt)
        assertEquals(2_500L, FinanceProjection.balance(changed, repo.snapshot().entries))
    }
    @Test fun ordinaryCorrectionKeepsRecordedAtIdentityAndTagsAreNormalized() = runTest {
        val a = bank(); val b = wechat(); val row = expense(a.id)
        now = 3_000
        val changed = repo.saveEntry(row.id, b.id, FinanceDirection.INFLOW, 1_234, "改为收入", happened + 60_000, "礼物", listOf(" 家人 ", "家人"))
        assertEquals(row.id, changed.id); assertEquals(row.recordedAt, changed.recordedAt); assertEquals(now, changed.updatedAt)
        assertEquals(1_234L, changed.amountMinor); assertEquals(happened + 60_000, changed.occurredAt)
        assertEquals("改为收入", changed.description)
        assertEquals(listOf("家人"), FinanceProjection.ledger(repo.snapshot()).single().tags)
        assertEquals(100_000L, FinanceProjection.balance(a, repo.snapshot().entries))
    }
    @Test fun transferCorrectionUpdatesBothStableLegsAndRelation() = runTest {
        val a = bank(); val b = wechat(); val relation = transfer(a.id, b.id)
        val original = repo.snapshot(); now = 4_000
        val changed = repo.saveTransfer(relation.id, b.id, a.id, 12_345, "反向转回", happened + 60_000)
        val data = repo.snapshot()
        assertEquals(relation.outflowEntryId, changed.outflowEntryId)
        assertEquals(relation.inflowEntryId, changed.inflowEntryId)
        assertEquals(relation.recordedAt, changed.recordedAt)
        data.entries.forEach { entry ->
            assertEquals(12_345L, entry.amountMinor); assertEquals(now, entry.updatedAt)
            assertEquals(happened + 60_000, entry.occurredAt)
            assertEquals(original.entries.first { it.id == entry.id }.recordedAt, entry.recordedAt)
        }
        assertEquals(112_345L, FinanceProjection.balance(a, data.entries))
        assertTrue(FinanceProjection.totals(FinanceProjection.ledger(data)).isEmpty())
    }
    @Test fun voidKeepsCanonicalRowsAndRemovesDerivedEffects() = runTest {
        val a = bank(); val b = wechat(); val entry = expense(a.id); val relation = transfer(a.id, b.id)
        now = 5_000; repo.voidEntry(entry.id); repo.voidTransfer(relation.id)
        val data = repo.snapshot()
        assertEquals(3, data.entries.size); assertEquals(1, data.transfers.size)
        assertTrue(data.entries.all { it.voidedAt == now }); assertEquals(now, data.transfers.single().voidedAt)
        assertTrue(FinanceProjection.ledger(data).isEmpty()); assertEquals(100_000L, FinanceProjection.balance(a, data.entries))
        repo.voidEntry(entry.id); repo.voidTransfer(relation.id) // Idempotent, still no deletion.
        assertEquals(3, repo.snapshot().entries.size)
    }
    @Test fun oneTransferLegCannotBeEditedOrVoidedIndependently() = runTest {
        val a = bank(); val relation = transfer(a.id, wechat().id)
        assertTrue(runCatching { repo.voidEntry(relation.outflowEntryId) }.isFailure)
        assertTrue(runCatching { repo.saveEntry(relation.outflowEntryId, a.id, FinanceDirection.OUTFLOW, 1, "错误", happened) }.isFailure)
        FinanceIntegrity.validate(repo.snapshot())
    }
    @Test fun descriptionCategoryTagAndAccountSearchAreReal() = runTest {
        expense(bank().id)
        val data = repo.snapshot()
        for (query in listOf("豆浆", "餐饮", "早餐", "银行卡", "回家")) assertEquals(query, 1, FinanceProjection.ledger(data, search = query).size)
        assertTrue(FinanceProjection.ledger(data, search = "不存在").isEmpty())
        assertEquals(10_000L, FinanceProjection.categoryTotals(FinanceProjection.ledger(data)).single().totals.expenseMinor)
        assertEquals(2, FinanceProjection.tagTotals(FinanceProjection.ledger(data)).size)
    }
    @Test fun dateWindowIncludesLastDayAndUsesLocalCalendarBoundaries() = runTest {
        expense(bank().id)
        val data = repo.snapshot()
        assertEquals(1, FinanceProjection.ledger(data, FinanceDateRange.days(day, day, zone)).size)
        assertEquals(0, FinanceProjection.ledger(data, FinanceDateRange.days(day.plusDays(1), day.plusDays(1), zone)).size)
        assertEquals(1, FinanceProjection.ledger(data, FinanceDateRange.year(day, zone)).size)
        assertFalse(FinanceDateRange.days(day, day, zone).contains(day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()))
    }
    @Test fun archivingKeepsHistorySearchAndBalanceButBlocksNewFlows() = runTest {
        val a = bank(); val entry = expense(a.id); repo.archiveAccount(a.id)
        val data = repo.snapshot(); assertNotNull(data.accounts.single().archivedAt)
        assertEquals(90_000L, FinanceProjection.balance(data.accounts.single(), data.entries))
        assertEquals(1, FinanceProjection.ledger(data, search = "银行卡").size)
        assertTrue(runCatching { expense(a.id) }.isFailure)
        repo.saveEntry(entry.id, a.id, FinanceDirection.OUTFLOW, 2_000, "历史纠错", happened)
        assertEquals(1, repo.snapshot().entries.size)
    }
    @Test fun invalidTransferValidationWritesNothing() = runTest {
        val a = bank(); val dollar = repo.saveAccount(name = "美元", kind = FinanceAccountKind.OTHER, currencyCode = "USD")
        assertTrue(runCatching { transfer(a.id, a.id) }.isFailure)
        assertTrue(runCatching { transfer(a.id, dollar.id) }.isFailure)
        assertTrue(runCatching { repo.saveTransfer(sourceAccountId = a.id, targetAccountId = dollar.id, amountMinor = 0, description = "", occurredAt = happened) }.isFailure)
        assertTrue(repo.snapshot().entries.isEmpty()); assertTrue(repo.snapshot().transfers.isEmpty())
    }
    @Test fun createTransferRollsBackIfSecondInsertFails() = runTest {
        val a = bank(); val b = wechat()
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_inflow BEFORE INSERT ON finance_entries WHEN NEW.direction='INFLOW' BEGIN SELECT RAISE(ABORT, 'injected failure'); END")
        assertTrue(runCatching { transfer(a.id, b.id) }.isFailure)
        assertTrue(repo.snapshot().entries.isEmpty()); assertTrue(repo.snapshot().transfers.isEmpty())
        assertEquals(100_000L, FinanceProjection.balance(a, repo.snapshot().entries))
    }
    @Test fun editAndVoidTransferRollBackIfSecondUpdateFails() = runTest {
        val a = bank(); val b = wechat(); val relation = transfer(a.id, b.id)
        val before = repo.snapshot(); now = 8_000
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_update BEFORE UPDATE ON finance_entries WHEN OLD.direction='INFLOW' BEGIN SELECT RAISE(ABORT, 'injected failure'); END")
        assertTrue(runCatching { repo.saveTransfer(relation.id, a.id, b.id, 33_333, "更改", happened) }.isFailure)
        assertEquals(before, repo.snapshot())
        assertTrue(runCatching { repo.voidTransfer(relation.id) }.isFailure)
        assertEquals(before, repo.snapshot())
    }
    @Test fun failedDescriptiveAssociationRollsBackAllOrdinaryChanges() = runTest {
        val a = bank()
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_tag BEFORE INSERT ON finance_entry_tags BEGIN SELECT RAISE(ABORT, 'injected failure'); END")
        assertTrue(runCatching { expense(a.id) }.isFailure)
        val data = repo.snapshot(); assertTrue(data.entries.isEmpty()); assertTrue(data.categories.isEmpty()); assertTrue(data.tags.isEmpty())
    }
    @Test fun overflowIsRejectedBeforeCommit() = runTest {
        val a = bank(Long.MAX_VALUE)
        assertTrue(runCatching { repo.saveEntry(accountId = a.id, direction = FinanceDirection.INFLOW, amountMinor = 1, description = "溢出", occurredAt = happened) }.isFailure)
        assertTrue(repo.snapshot().entries.isEmpty())
    }
    @Test fun persistedAccountsEntriesAndTransferSurviveClosingAndReopeningRoom() = runTest {
        val context: Context = ApplicationProvider.getApplicationContext()
        val filename = "finance-reopen-test.db"; context.deleteDatabase(filename)
        fun open() = Room.databaseBuilder(context, LifeDatabase::class.java, filename).allowMainThreadQueries().build()
        val first = open(); val writer = FinanceRepository(first) { now }
        val a = writer.saveAccount(name = "银行卡", kind = FinanceAccountKind.BANK_CARD, openingBalanceMinor = 100_000)
        val b = writer.saveAccount(name = "微信", kind = FinanceAccountKind.WECHAT)
        writer.saveTransfer(sourceAccountId = a.id, targetAccountId = b.id, amountMinor = 20_000, description = "持久转账", occurredAt = happened)
        writer.saveEntry(accountId = a.id, direction = FinanceDirection.OUTFLOW, amountMinor = 1_234, description = "持久支出", occurredAt = happened, tags = listOf("标签"))
        val saved = writer.snapshot(); first.close()
        val second = open()
        try { assertEquals(saved, FinanceRepository(second).snapshot()) } finally { second.close(); context.deleteDatabase(filename) }
    }
    @Test fun restoreBarrierAlsoProtectsFinanceAfterRepositoryConstruction() = runTest {
        bank(); RestoreStartupGate.resetForTesting()
        assertTrue(runCatching { repo.snapshot() }.isFailure)
        assertTrue(runCatching { expense("missing") }.isFailure)
    }
    @Test fun observableSnapshotReturnsTheCoherentTypedState() = runTest {
        val a = bank(); expense(a.id); transfer(a.id, wechat().id)
        assertEquals(repo.snapshot(), repo.observeSnapshot().first())
    }
    @Test fun observationInvalidatesOnAnEditWithoutAnyRowCountChange() = kotlinx.coroutines.runBlocking {
        val account = bank(); val entry = expense(account.id)
        val received = kotlinx.coroutines.channels.Channel<FinanceSnapshot>(kotlinx.coroutines.channels.Channel.UNLIMITED)
        val watcher = launch(kotlinx.coroutines.Dispatchers.Default) {
            repo.observeSnapshot().collect { received.send(it) }
        }
        try {
            kotlinx.coroutines.withTimeout(10_000) { received.receive() }
            now = 9_000
            repo.saveEntry(entry.id, account.id, FinanceDirection.OUTFLOW, 500, "同一行纠错", happened, "餐饮", listOf("早餐", "回家"))
            val changed = kotlinx.coroutines.withTimeout(10_000) {
                var value = received.receive()
                while (value.entries.single().amountMinor != 500L) value = received.receive()
                value
            }
            assertEquals(1, changed.entries.size)
            assertEquals("同一行纠错", changed.entries.single().description)
        } finally { watcher.cancel(); received.close() }
    }
}
