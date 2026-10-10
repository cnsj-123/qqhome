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
import kotlinx.coroutines.test.runTest
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FinanceMigrationTest {
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), LifeDatabase::class.java,
        emptyList(), FrameworkSQLiteOpenHelperFactory())

    @Test fun populatedV2IsAdditivelyMigratedAndFinanceWorksThroughRoom() = runTest {
        RestoreStartupGate.resetForTesting(); RestoreStartupGate.markReady()
        val name = "finance-v2-to-v3.db"
        val v2 = helper.createDatabase(name, 2)
        v2.execSQL("INSERT INTO life_entities(id,entityType,createdAt,updatedAt,revision) VALUES('old-identity','plan',100,200,7)")
        v2.execSQL("INSERT INTO plan_items(id,lifeEntityId,title,note,createdAt,updatedAt,sortOrder) VALUES('old-plan','old-identity','旧计划','不能丢失',100,200,0)")
        v2.execSQL("INSERT INTO capture_items(id,source,status,rawText,createdAt,updatedAt) VALUES('old-capture','MANUAL','NEW','旧记录',100,200)")
        v2.close()
        val migrated = helper.runMigrationsAndValidate(name, 3, true, LifeMigrations.MIGRATION_2_3)
        migrated.query("SELECT revision FROM life_entities WHERE id='old-identity'").use { assertTrue(it.moveToFirst()); assertEquals(7, it.getInt(0)) }
        migrated.query("SELECT title,note FROM plan_items WHERE id='old-plan'").use { assertTrue(it.moveToFirst()); assertEquals("旧计划", it.getString(0)); assertEquals("不能丢失", it.getString(1)) }
        migrated.close()
        val context: Context = ApplicationProvider.getApplicationContext()
        val room = Room.databaseBuilder(context, LifeDatabase::class.java, name).addMigrations(*LifeMigrations.ALL).allowMainThreadQueries().build()
        try {
            val repo = FinanceRepository(room) { 1_000L }
            val a = repo.saveAccount(name = "银行卡", kind = FinanceAccountKind.BANK_CARD, openingBalanceMinor = 100_000)
            val b = repo.saveAccount(name = "微信", kind = FinanceAccountKind.WECHAT)
            repo.saveTransfer(sourceAccountId = a.id, targetAccountId = b.id, amountMinor = 20_000, description = "迁移后转账", occurredAt = 5_000)
            assertEquals(80_000L, FinanceProjection.balance(a, repo.snapshot().entries))
            assertEquals(2, repo.snapshot().entries.size)
            assertEquals("旧计划", room.planDao().getAllOnce().single().title)
            assertEquals("旧记录", room.captureDao().getAllOnce().single().rawText)
            assertEquals(4, room.openHelper.writableDatabase.version)
        } finally { room.close(); RestoreStartupGate.resetForTesting() }
    }
}
