package com.qq.closie.data.backup

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.qq.closie.life.data.database.LifeDatabase
import com.qq.closie.life.finance.*
import kotlinx.coroutines.test.runTest
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FinanceBackupRecoveryTest {
    private lateinit var db: LifeDatabase
    private lateinit var repo: FinanceRepository
    private lateinit var context: Context
    @Before fun setup() {
        RestoreStartupGate.resetForTesting(); RestoreStartupGate.markReady()
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, LifeDatabase::class.java).allowMainThreadQueries().build()
        repo = FinanceRepository(db)
    }
    @After fun cleanup() { db.close(); RestoreStartupGate.resetForTesting() }
    private suspend fun seed(): Pair<FinanceAccountEntity, FinanceEntryEntity> {
        val bank = repo.saveAccount(name = "银行卡", kind = FinanceAccountKind.BANK_CARD, openingBalanceMinor = 100_000)
        val wallet = repo.saveAccount(name = "微信", kind = FinanceAccountKind.WECHAT)
        val entry = repo.saveEntry(accountId = bank.id, direction = FinanceDirection.OUTFLOW,
            amountMinor = 1_234, description = "早餐", occurredAt = 100, category = "餐饮", tags = listOf("早晨"))
        repo.saveTransfer(sourceAccountId = bank.id, targetAccountId = wallet.id, amountMinor = 20_000, description = "零钱", occurredAt = 200)
        return bank to entry
    }
    @Test fun snapshotAndJsonArchiveCarryEveryFinanceTableExactly() = runTest {
        seed()
        val saved = LifeBackupApplier.snapshot(db)
        assertEquals(readFinanceSnapshot(db, includeIntake = true), saved.finance)
        assertEquals(27, saved.toInsertOrder().size)
        val decoded = BackupValidator.gson.fromJson(BackupValidator.gson.toJson(saved), LifeBackupPayload::class.java)
        assertEquals(saved.finance, decoded.finance)
        FinanceIntegrity.validate(requireNotNull(decoded.finance))
        val expectedTables = db.openHelper.writableDatabase.query("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name NOT IN ('android_metadata','room_master_table')").use {
            val names = mutableSetOf<String>(); while (it.moveToNext()) names += it.getString(0); names
        }
        assertEquals(expectedTables, saved.toInsertOrder().map { it.first }.toSet())
    }
    @Test fun recoveryReplaysFinanceWhileKeepingRetiredCanonicalRows() = runTest {
        val (bank, entry) = seed(); val before = LifeBackupApplier.snapshot(db)
        repo.saveEntry(entry.id, bank.id, FinanceDirection.OUTFLOW, 9_999, "后来纠错", 300)
        val extra = repo.saveEntry(accountId = bank.id, direction = FinanceDirection.INFLOW, amountMinor = 100, description = "恢复后新增", occurredAt = 400)
        LifeBackupApplier.restoreSnapshot(db, before)
        val restored = repo.snapshot()
        assertEquals(FinanceProjection.ledger(requireNotNull(before.finance)), FinanceProjection.ledger(restored))
        assertNotNull(restored.entries.find { it.id == extra.id }?.voidedAt)
        assertEquals(4, restored.entries.size)
        LifeBackupApplier.restoreSnapshot(db, before) // Recovery can retry safely.
        assertEquals(FinanceProjection.ledger(requireNotNull(before.finance)), FinanceProjection.ledger(repo.snapshot()))
    }
    @Test fun legacyPayloadWithoutFinanceCannotEraseExistingMoneyRecords() = runTest {
        seed(); val before = repo.snapshot()
        val legacy = BackupValidator.gson.fromJson("{\"lifeEntities\":[],\"planItems\":[]}", LifeBackupPayload::class.java)
        assertNull(legacy.finance)
        LifeBackupApplier.restoreSnapshot(db, legacy)
        assertEquals(before, repo.snapshot())
    }
    @Test fun restoringAnIndependentArchiveKeepsBothIdentitiesForSameDescriptiveNames() = runTest {
        seed()
        val second = Room.inMemoryDatabaseBuilder(context, LifeDatabase::class.java).allowMainThreadQueries().build()
        try {
            val writer = FinanceRepository(second)
            val a = writer.saveAccount(name = "另一张卡", kind = FinanceAccountKind.BANK_CARD)
            writer.saveEntry(accountId = a.id, direction = FinanceDirection.OUTFLOW, amountMinor = 100, description = "另一份早餐", occurredAt = 1,
                category = "餐饮", tags = listOf("早晨"))
            val saved = LifeBackupApplier.snapshot(second)
            LifeBackupApplier.restoreSnapshot(db, saved)
            assertEquals(2, repo.snapshot().categories.size)
            assertEquals(FinanceProjection.ledger(requireNotNull(saved.finance)), FinanceProjection.ledger(repo.snapshot()))
        } finally { second.close() }
    }
    @Test fun corruptTransferArchiveFailsWithoutChangingExistingFinance() = runTest {
        seed(); val before = repo.snapshot(); val saved = LifeBackupApplier.snapshot(db)
        val corrupt = saved.copy(finance = before.copy(entries = before.entries.dropLast(1)))
        assertTrue(runCatching { LifeBackupApplier.restoreSnapshot(db, corrupt) }.isFailure)
        assertEquals(before, repo.snapshot())
    }
}
