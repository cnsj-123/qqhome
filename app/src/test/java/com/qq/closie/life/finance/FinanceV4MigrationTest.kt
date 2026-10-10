package com.qq.closie.life.finance

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.qq.closie.data.backup.RestoreStartupGate
import com.qq.closie.life.data.database.LifeDatabase
import com.qq.closie.life.data.database.LifeMigrations
import com.qq.closie.life.data.database.MIGRATION_3_4
import kotlinx.coroutines.test.runTest
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FinanceV4MigrationTest {
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), LifeDatabase::class.java,
        emptyList(), FrameworkSQLiteOpenHelperFactory())
    @Before fun ready() { RestoreStartupGate.resetForTesting(); RestoreStartupGate.markReady() }
    @After fun reset() { RestoreStartupGate.resetForTesting() }
    private fun room(name: String): LifeDatabase {
        val context: Context = ApplicationProvider.getApplicationContext()
        return Room.databaseBuilder(context, LifeDatabase::class.java, name)
            .addMigrations(*LifeMigrations.ALL).allowMainThreadQueries().build()
    }

    @Test fun populatedV3PreservesDomainFinanceAnchorsAndBalanceAndAcceptsV4Semantics() = runTest {
        val name = "synthetic-v3-v4.db"
        helper.createDatabase(name, 3).apply {
            execSQL("INSERT INTO capture_items(id,source,status,rawText,createdAt,updatedAt) VALUES('capture','MANUAL','NEW','合成旧记录',10,20)")
            execSQL("INSERT INTO finance_accounts(id,name,kind,currencyCode,openingBalanceMinor,createdAt,updatedAt) VALUES('a','合成卡','BANK_CARD','CNY',100000,100,100),('b','合成钱包','WECHAT','CNY',0,100,100)")
            execSQL("INSERT INTO finance_categories(id,name) VALUES('c','合成分类')")
            execSQL("INSERT INTO finance_tags(id,name) VALUES('t','合成标签')")
            execSQL("INSERT INTO finance_entries(id,accountId,direction,amountMinor,description,occurredAt,recordedAt,updatedAt,categoryId) VALUES('e','a','OUTFLOW',1000,'合成支出',100,100,100,'c'),('out','a','OUTFLOW',2000,'合成转账',200,200,200,NULL),('in','b','INFLOW',2000,'合成转账',200,200,200,NULL)")
            execSQL("INSERT INTO finance_entry_tags(entryId,tagId) VALUES('e','t')")
            execSQL("INSERT INTO finance_transfers(id,outflowEntryId,inflowEntryId,recordedAt,updatedAt) VALUES('transfer','out','in',200,200)")
            close()
        }
        helper.runMigrationsAndValidate(name, 4, true, MIGRATION_3_4).close()
        val db = room(name)
        try {
            val finance = FinanceRepository(db) { 1_000L }
            val state = finance.snapshot()
            assertEquals(4, db.openHelper.writableDatabase.version)
            assertEquals("合成旧记录", db.captureDao().getAllOnce().single().rawText)
            assertTrue(state.accounts.all { it.balanceAnchorAt == it.createdAt && it.createdAt == 100L })
            assertEquals(97_000L, FinanceProjection.balance(state.accounts.single { it.id == "a" }, state.entries))
            assertEquals(2_000L, FinanceProjection.balance(state.accounts.single { it.id == "b" }, state.entries))
            assertEquals("transfer", state.transfers.single().id)
            assertEquals("合成分类", FinanceProjection.ledger(state).single { it.entry.id == "e" }.category)
            assertEquals(listOf("合成标签"), FinanceProjection.ledger(state).single { it.entry.id == "e" }.tags)
            val old = state.entries.single { it.id == "e" }
            finance.saveEntry(old.id, old.accountId, old.direction, old.amountMinor, old.description, old.occurredAt,
                "合成分类", listOf("合成标签"), event = FinanceEventInput(expectedFlows = listOf(
                    FinanceExpectedFlowInput(role = FinanceFlowRole.REFUND),
                    FinanceExpectedFlowInput(role = FinanceFlowRole.SETTLEMENT, amountMinor = 100))), expectedRevision = old.revision)
            val after = finance.snapshot()
            assertEquals(1, after.v4!!.activeLinks.size)
            assertEquals(2, after.v4!!.expected.size)
            assertEquals(97_000L, FinanceProjection.balance(after.accounts.single { it.id == "a" }, after.entries))
            FinanceIntegrity.validate(after)
        } finally { db.close() }
    }

    @Test fun v2ThroughV3ToV4KeepsOldPlanIdentityCaptureAndRevision() = runTest {
        val name = "synthetic-v2-v4.db"
        helper.createDatabase(name, 2).apply {
            execSQL("INSERT INTO life_entities(id,entityType,createdAt,updatedAt,revision) VALUES('identity','plan',10,20,7)")
            execSQL("INSERT INTO plan_items(id,lifeEntityId,title,note,createdAt,updatedAt,sortOrder) VALUES('plan','identity','合成计划','保留旧领域数据',10,20,0)")
            execSQL("INSERT INTO capture_items(id,source,status,rawText,createdAt,updatedAt) VALUES('capture','MANUAL','NEW','合成捕获',10,20)")
            close()
        }
        helper.runMigrationsAndValidate(name, 4, true, LifeMigrations.MIGRATION_2_3, MIGRATION_3_4).apply {
            query("SELECT revision FROM life_entities WHERE id='identity'").use {
                assertTrue(it.moveToFirst()); assertEquals(7, it.getInt(0))
            }
            close()
        }
        val db = room(name)
        try {
            assertEquals("保留旧领域数据", db.planDao().getAllOnce().single().note)
            assertEquals("合成捕获", db.captureDao().getAllOnce().single().rawText)
            assertTrue(FinanceRepository(db).snapshot().entries.isEmpty())
        } finally { db.close() }
    }
}
