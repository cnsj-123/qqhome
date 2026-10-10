package com.qq.closie.ui.lifeos.finance

import android.content.Context
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.qq.closie.data.backup.RestoreStartupGate
import com.qq.closie.life.data.database.LifeDatabase
import com.qq.closie.life.finance.*
import com.qq.closie.ui.lifeos.theme.LifeOsTheme
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Exercises real sheets through ViewModel → Repository → Room, including corrections. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w393dp-h852dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FinanceSmokeTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var db: LifeDatabase
    private lateinit var repository: FinanceRepository

    @Before fun setup() {
        RestoreStartupGate.resetForTesting(); RestoreStartupGate.markReady()
        val context: Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, LifeDatabase::class.java).allowMainThreadQueries().build()
        repository = FinanceRepository(db)
    }
    @After fun cleanup() { db.close(); RestoreStartupGate.resetForTesting() }
    private fun launch() {
        compose.setContent { LifeOsTheme { FinanceRoute(repository, {}) } }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("正在打开账本……").fetchSemanticsNodes().isEmpty() &&
            compose.onAllNodesWithText("账户摘要").fetchSemanticsNodes().isNotEmpty() }
    }
    private fun input(label: String, text: String) {
        compose.onNode(hasSetTextAction() and hasText(label)).performScrollTo().performTextReplacement(text)
    }
    private fun click(text: String) { compose.onNodeWithText(text).performScrollTo().performClick() }
    private fun saved() {
        compose.waitUntil(10_000) { compose.onAllNodesWithText("保存记录").fetchSemanticsNodes().isEmpty() &&
            compose.onAllNodesWithText("保存账户").fetchSemanticsNodes().isEmpty() }
    }
    private fun snapshot() = runBlocking { repository.snapshot() }

    @Test fun establishAccountRecordCorrectSearchAndVoidThroughReceiptSheets() {
        launch()
        click("建立第一个账户")
        input("账户名称", "工资卡")
        input("期初余额", "1000.00")
        click("保存账户"); saved()
        assertEquals(100_000L, snapshot().accounts.single().openingBalanceMinor)
        compose.onNodeWithTag("finance-add").performScrollTo().performClick()
        input("金额 · CNY", "12.34")
        input("具体事由 · 买了什么 / 为了什么", "早餐豆浆")
        input("分类（可自定义）", "餐饮")
        input("标签 · 用逗号分隔，可自定义", "早餐，出门")
        click("保存记录"); saved()
        val original = snapshot().entries.single()
        assertEquals(1_234L, original.amountMinor)
        assertTrue(original.occurredAt >= snapshot().accounts.single().createdAt)
        assertEquals(98_766L, FinanceProjection.balance(snapshot().accounts.single(), snapshot().entries))
        click("早餐豆浆")
        input("金额 · CNY", "10.00")
        click("保存记录"); saved()
        assertEquals(1_000L, snapshot().entries.single().amountMinor)
        assertEquals(original.recordedAt, snapshot().entries.single().recordedAt)
        assertEquals(original.occurredAt, snapshot().entries.single().occurredAt)
        assertEquals(99_000L, FinanceProjection.balance(snapshot().accounts.single(), snapshot().entries))
        compose.onNodeWithText("流水").performClick()
        compose.onNode(hasSetTextAction() and hasText("事由、分类、标签或账户")).performScrollTo().performTextReplacement("出门")
        compose.onNodeWithText("早餐豆浆").assertExists()
        click("早餐豆浆")
        click("作废这笔记录")
        compose.onNodeWithText("确认作废").performClick(); saved()
        assertNotNull(snapshot().entries.single().voidedAt)
        assertTrue(FinanceProjection.ledger(snapshot()).isEmpty())
    }

    @Test fun transferIsOneVisualRecordAndEditsBothFlowsThenConfirmsWholeVoid() {
        runBlocking {
            repository.saveAccount(name = "银行卡", kind = FinanceAccountKind.BANK_CARD)
            repository.saveAccount(name = "微信", kind = FinanceAccountKind.WECHAT)
        }
        launch()
        compose.onNodeWithTag("finance-add").performScrollTo().performClick()
        click("转账")
        input("金额 · CNY", "200.00")
        click("保存记录"); saved()
        assertEquals(2, snapshot().entries.size)
        assertEquals(1, snapshot().transfers.size)
        click("银行卡 → 微信")
        input("金额 · CNY", "250.00")
        click("保存记录"); saved()
        assertTrue(snapshot().entries.all { it.amountMinor == 25_000L })
        assertEquals(1, FinanceProjection.ledger(snapshot()).size)
        click("银行卡 → 微信")
        click("作废这笔记录")
        compose.onNodeWithText("作废整笔转账？").assertIsDisplayed()
        compose.onNodeWithText("确认作废").performClick(); saved()
        assertTrue(snapshot().entries.all { it.voidedAt != null })
        assertNotNull(snapshot().transfers.single().voidedAt)
    }
}
