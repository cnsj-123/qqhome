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
    @Test fun populatedV4UpgradeStoresCivilDatesWithoutRewritingOldTimesAnchorMoneyOrIds()=runTest {
        val name="v16-date-populated-v4.db"
        helper.createDatabase(name,4).apply {
            execSQL("INSERT INTO finance_accounts(id,name,kind,currencyCode,openingBalanceMinor,createdAt,updatedAt,balanceAnchorAt) VALUES('account','合成旧账户','CASH','CNY',1000,100,100,100)")
            execSQL("INSERT INTO finance_entries(id,accountId,direction,amountMinor,description,occurredAt,recordedAt,updatedAt) VALUES('entry','account','OUTFLOW',100,'合成旧现金',100,200,201)")
            close()
        }
        helper.runMigrationsAndValidate(name,5,true,*LifeMigrations.ALL).close()
        val db=Room.databaseBuilder(ApplicationProvider.getApplicationContext<Context>(),LifeDatabase::class.java,name)
            .addMigrations(*LifeMigrations.ALL).allowMainThreadQueries().build()
        try {
            val finance=FinanceRepository(db){2_000_000_000_000L}
            val before=finance.snapshot();val account=before.accounts.single();val entry=before.entries.single()
            assertEquals("account",account.id);assertEquals("entry",entry.id)
            assertEquals(100L,account.balanceAnchorAt);assertEquals(900L,FinanceProjection.balance(account,before.entries))
            assertTrue(before.v16!!.entryTimingRows.isEmpty());assertTrue(before.v16!!.observationRows.isEmpty())
            fun token()=FinanceCommandToken(java.util.UUID.randomUUID().toString(),0)
            val civil=FinanceCivilDate.capture(java.time.LocalDate.of(2026,10,10),java.time.ZoneId.of("Asia/Tokyo"))
            finance.workspace.setPostingDate(token(),entry.id,entry.revision,civil.startInclusive,FinanceTimePrecision.DATE,"新确认的日期证据",civil)
            val next=FinanceCivilDate.capture(java.time.LocalDate.of(2026,10,11),java.time.ZoneId.of("Asia/Tokyo"))
            finance.workspace.setAccountPolicy(token(),account.id,true,FinanceLiabilityConvention.SIGNED,FinanceBalanceBasis.KNOWN_POSTED,null)
            finance.workspace.saveObservation(token(),account.id,900,next.startInclusive,FinanceBalanceBasis.KNOWN_POSTED,FinanceTimePrecision.DATE,"",next)
            val after=finance.snapshot();val changed=after.entries.single()
            assertEquals(before.accounts,after.accounts)
            assertEquals(entry.id,changed.id);assertEquals(entry.occurredAt,changed.occurredAt)
            assertEquals(entry.recordedAt,changed.recordedAt);assertEquals(entry.amountMinor,changed.amountMinor)
            assertEquals(900L,FinanceProjection.balance(after.accounts.single(),after.entries))
            assertEquals(civil,after.v16!!.entryTimingRows.single().postedCivilDate())
            assertEquals(next,after.v16!!.observationRows.single().observedCivilDate())
            assertEquals(900L,FinanceAccountProjection.reconcile(after,after.v16!!.observationRows.single()).bookMinor)
            FinanceIntegrity.validate(after)
        } finally {db.close()}
    }
}
