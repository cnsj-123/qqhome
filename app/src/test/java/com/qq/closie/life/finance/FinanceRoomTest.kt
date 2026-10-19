package com.qq.closie.life.finance

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.qq.closie.data.backup.RestoreStartupGate
import com.qq.closie.life.data.database.LifeDatabase
import org.junit.After
import org.junit.Before
import org.junit.Assert.*

/** For fixtures whose ONLY permitted restore delta is the control singleton/epoch. */
internal fun assertFinanceFactsAfterRestore(expected:FinanceSnapshot,actual:FinanceSnapshot,previousEpoch:Long):Long {
    val expectedV16=requireNotNull(expected.v16)
    val actualV16=requireNotNull(actual.v16)
    assertEquals(1,actualV16.controlRows.size)
    val control=actualV16.controlRows.single()
    assertEquals(1,control.id)
    assertTrue(control.restoreEpoch>previousEpoch)
    assertTrue(control.restoreEpoch>(expectedV16.controlRows.singleOrNull()?.restoreEpoch ?: 0L))
    // Compare every business field, ID, revision, lifecycle and historical relation.
    // Do not drop v16 or normalize any other recovery policy/history fields.
    assertEquals(expected,actual.copy(v16=actualV16.copy(controlRows=expectedV16.controlRows)))
    return control.restoreEpoch
}

internal suspend fun assertStaleFinanceCommandRejected(repo:FinanceRepository,entryId:String,staleEpoch:Long) {
    val before=repo.portableSnapshot();val entry=before.entries.single {it.id==entryId}
    val result=runCatching {repo.workspace.setPostingDate(FinanceCommandToken(java.util.UUID.randomUUID().toString(),staleEpoch),
        entry.id,entry.revision,null,FinanceTimePrecision.UNKNOWN,"合成旧恢复会话")}
    val error=result.exceptionOrNull()
    assertTrue(error is IllegalArgumentException)
    assertTrue(error?.message.orEmpty().contains("恢复后"))
    assertEquals(before,repo.portableSnapshot())
}

/** One actual Room database per test, including generated DAO/transaction behavior. */
abstract class FinanceRoomTest {
    protected lateinit var db: LifeDatabase
    protected lateinit var finance: FinanceRepository
    protected var clock = 1_000L
    @Before fun openRoom() {
        RestoreStartupGate.resetForTesting()
        RestoreStartupGate.markReady()
        val context: Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, LifeDatabase::class.java).allowMainThreadQueries().build()
        finance = FinanceRepository(db) { clock }
    }
    @After fun closeRoom() {
        db.close()
        RestoreStartupGate.resetForTesting()
    }
    protected suspend fun account(name: String = "合成账户") =
        finance.saveAccount(name = name, kind = FinanceAccountKind.WECHAT)
    protected suspend fun payment(accountId: String, amount: Long = 30_000,
        nature: FinanceNature = FinanceNature.PERSONAL, share: Long? = null, category: String = "衣服") =
        finance.saveEntry(accountId = accountId, direction = FinanceDirection.OUTFLOW, amountMinor = amount,
            description = "合成付款", occurredAt = 2_000, category = category, tags = listOf("合成标签"),
            event = FinanceEventInput(nature = nature, personalShareMinor = share))
    protected suspend fun eventId(entryId: String) = db.financeIntakeDao().linksForEntry(entryId).single().eventId
}
