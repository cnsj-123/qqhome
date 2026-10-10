package com.qq.closie.life.finance

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.qq.closie.data.backup.RestoreStartupGate
import com.qq.closie.life.data.database.LifeDatabase
import org.junit.After
import org.junit.Before

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
