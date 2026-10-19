package com.qq.closie.life.finance

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.qq.closie.data.backup.RestoreStartupGate
import com.qq.closie.life.data.database.*
import kotlinx.coroutines.test.runTest
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class FinanceV5MigrationTest {
    @get:Rule val helper=MigrationTestHelper(InstrumentationRegistry.getInstrumentation(),LifeDatabase::class.java,
        emptyList(),FrameworkSQLiteOpenHelperFactory())
    @Before fun gate() { RestoreStartupGate.resetForTesting(); RestoreStartupGate.markReady() }
    @After fun closeGate() { RestoreStartupGate.resetForTesting() }
    @Test fun allPublishedVersionsUpgradePopulatedFactsWithoutChangingAnchorOrIdentity() = runTest {
        for(version in 1..4) {
            val name="v16-populated-v$version.db"
            helper.createDatabase(name,version).apply {
                execSQL("INSERT INTO capture_items(id,source,status,rawText,createdAt,updatedAt) VALUES('capture','MANUAL','NEW','合成旧记录',10,20)")
                if(version>=3) {
                    execSQL("INSERT INTO finance_accounts(id,name,kind,currencyCode,openingBalanceMinor,createdAt,updatedAt) VALUES('account','合成账户','CREDIT','CNY',-10000,100,100)")
                    execSQL("INSERT INTO finance_entries(id,accountId,direction,amountMinor,description,occurredAt,recordedAt,updatedAt) VALUES('before','account','OUTFLOW',1234,'合成旧历史',99,200,200),('edge','account','INFLOW',1000,'合成边界',100,201,201)")
                    // v4 carries an explicit anchor; v3 obtains it only from the existing 3→4 migration.
                    if(version==4) execSQL("UPDATE finance_accounts SET balanceAnchorAt=100")
                }
                close()
            }
            helper.runMigrationsAndValidate(name,5,true,*LifeMigrations.ALL).close()
            val db=Room.databaseBuilder(ApplicationProvider.getApplicationContext<Context>(),LifeDatabase::class.java,name)
                .addMigrations(*LifeMigrations.ALL).allowMainThreadQueries().build()
            try {
                assertEquals(5,db.openHelper.writableDatabase.version)
                assertEquals("合成旧记录",db.captureDao().getAllOnce().single().rawText)
                val state=FinanceRepository(db).snapshot()
                if(version>=3) {
                    val account=state.accounts.single()
                    assertEquals("account",account.id); assertEquals(100L,account.balanceAnchorAt)
                    assertEquals(-9_000L,FinanceProjection.balance(account,state.entries))
                    assertEquals(200L,state.entries.single { it.id=="before" }.recordedAt)
                }
                assertTrue(state.v16!!.economicRows.isEmpty())
                assertTrue(state.v16!!.entryTimingRows.isEmpty())
                assertEquals(0L,state.v16!!.controlRows.single().restoreEpoch)
                FinanceIntegrity.validate(state)
            } finally { db.close() }
        }
    }
}
