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
import java.time.YearMonth
import java.time.ZoneId
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
            compose.onAllNodesWithText("账簿").fetchSemanticsNodes().isNotEmpty() }
    }
    private fun input(label: String, text: String) {
        compose.onNode(hasSetTextAction() and hasText(label)).performScrollTo().performTextReplacement(text)
    }
    private fun click(text: String) {
        val node = compose.onNodeWithText(text)
        if (runCatching { node.assertIsDisplayed() }.isFailure) node.performScrollTo()
        node.performClick()
    }
    private fun saved() {
        compose.waitUntil(10_000) { compose.onAllNodesWithText("保存记录").fetchSemanticsNodes().isEmpty() &&
            compose.onAllNodesWithText("保存账户").fetchSemanticsNodes().isEmpty() }
    }
    private fun snapshot() = runBlocking { repository.snapshot() }
    private fun openFilters() {
        compose.waitForIdle()
        compose.onNode(hasContentDescription("筛选与日期", substring = true)).performClick()
    }
    private fun selectPeriod(label: String) {
        openFilters()
        click(label)
        compose.waitForIdle()
    }
    private fun assertViewedMonth(month: YearMonth) {
        val dateControl = hasTestTag("finance-date-control") and
            hasText("${month.year}年", substring = true) and hasText("${month.monthValue}月", substring = true)
        compose.waitUntil(10_000) { compose.onAllNodes(dateControl).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(dateControl).assertIsDisplayed()
    }

    @Test fun insightsStartsWithItsOwnRangeAfterLedgerSelectedAllDates() {
        launch()
        selectPeriod("全部")
        click("分析")
        compose.waitForIdle()
        openFilters()
        val month=YearMonth.now()
        compose.onNodeWithText("日期 · ${month.year} 年 ${month.monthValue} 月").assertIsDisplayed()
    }

    @Test fun establishAccountRecordCorrectSearchAndVoidThroughReceiptSheets() {
        launch()
        click("建立第一个账户")
        input("账户名称", "工资卡")
        input("期初余额", "1000.00")
        click("保存账户"); saved()
        assertEquals(100_000L, snapshot().accounts.single().openingBalanceMinor)
        assertEquals("工资卡", snapshot().accounts.single().name)
        compose.onNodeWithTag("finance-add").assertIsDisplayed().performClick()
        input("金额 · CNY", "12.34")
        input("备注（可留空）", "早餐豆浆")
        click("标签与新分类")
        input("新主分类（可留空）", "餐饮")
        input("标签 · 逗号分隔", "早餐，出门")
        click("保存记录"); saved()
        val original = snapshot().entries.single()
        assertEquals(1_234L, original.amountMinor)
        val originalRow = FinanceProjection.ledger(snapshot()).single()
        assertEquals("餐饮", originalRow.category)
        assertEquals(setOf("早餐", "出门"), originalRow.tags.toSet())
        assertTrue(original.occurredAt >= snapshot().accounts.single().createdAt)
        assertEquals(98_766L, FinanceProjection.balance(snapshot().accounts.single(), snapshot().entries))
        click("早餐豆浆")
        input("金额 · CNY", "10.00")
        click("保存记录"); saved()
        assertEquals(1_000L, snapshot().entries.single().amountMinor)
        assertEquals(original.recordedAt, snapshot().entries.single().recordedAt)
        assertEquals(original.occurredAt, snapshot().entries.single().occurredAt)
        val correctedRow = FinanceProjection.ledger(snapshot()).single()
        assertEquals(originalRow.category, correctedRow.category)
        assertEquals(originalRow.tags, correctedRow.tags)
        assertEquals(99_000L, FinanceProjection.balance(snapshot().accounts.single(), snapshot().entries))
        compose.onNodeWithText("账簿").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("finance-search").performTextReplacement("出门")
        compose.waitForIdle()
        compose.onNodeWithTag("finance-ledger").performScrollToNode(hasText("早餐豆浆"))
        compose.onNodeWithText("早餐豆浆").assertIsDisplayed()
        click("早餐豆浆")
        click("作废这笔记录")
        compose.onNodeWithText("确认作废").performClick(); saved()
        assertNotNull(snapshot().entries.single().voidedAt)
        assertTrue(FinanceProjection.ledger(snapshot()).isEmpty())
        compose.waitUntil(10_000) { compose.onAllNodesWithText("早餐豆浆").fetchSemanticsNodes().isEmpty() }
    }

    @Test fun transferIsOneVisualRecordAndEditsBothFlowsThenConfirmsWholeVoid() {
        runBlocking {
            repository.saveAccount(name = "银行卡", kind = FinanceAccountKind.BANK_CARD)
            repository.saveAccount(name = "微信", kind = FinanceAccountKind.WECHAT)
        }
        launch()
        compose.onNodeWithTag("finance-add").assertIsDisplayed().performClick()
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

    @Test fun creditRepaymentEntryUsesCorrectAccountLegsAndDoesNotCreateAnotherConsumption() {
        lateinit var bank:FinanceAccountEntity
        lateinit var credit:FinanceAccountEntity
        runBlocking {
            bank=repository.saveAccount(name="合成还款银行",kind=FinanceAccountKind.BANK_CARD,openingBalanceMinor=100_000)
            credit=repository.saveAccount(name="合成旧欠款",kind=FinanceAccountKind.CREDIT,openingBalanceMinor=10_000)
            repository.workspace.setAccountPolicy(repository.economics.token(),credit.id,true,
                FinanceLiabilityConvention.POSITIVE_DEBT,FinanceBalanceBasis.LEGACY_CASH,null)
        }
        val before=FinanceAccountProjection.netByCurrency(snapshot())
        launch();click("账户");click("合成旧欠款")
        click("记录新的实际还款转账")
        input("金额 · CNY","50.00")
        click("保存记录");saved()
        val after=snapshot();val transfer=after.transfers.single()
        val out=after.entries.single {it.id==transfer.outflowEntryId}
        val incoming=after.entries.single {it.id==transfer.inflowEntryId}
        assertEquals(bank.id,out.accountId);assertEquals(credit.id,incoming.accountId)
        assertEquals(FinanceDirection.OUTFLOW,out.direction);assertEquals(FinanceDirection.INFLOW,incoming.direction)
        assertEquals(5_000L,out.amountMinor);assertEquals(out.amountMinor,incoming.amountMinor)
        assertEquals(before,FinanceAccountProjection.netByCurrency(after))
        assertEquals(-5_000L,FinanceAccountProjection.netContribution(after,credit))
        assertTrue(FinanceCostProjection.rows(after).isEmpty())
    }

    @Test fun historicalMonthAndSingleDayCountSurviveRetapAndInsightsRangeChange() {
        val current = YearMonth.now()
        val month = current.minusMonths(1)
        val day = month.atDay(1)
        val heading = "$day 真实资金流水 · 1 笔"
        runBlocking {
            val account = repository.saveAccount(name = "测试现金", kind = FinanceAccountKind.CASH)
            (1..2).forEach { number ->
                repository.saveEntry(accountId = account.id, direction = FinanceDirection.OUTFLOW,
                    amountMinor = 100, description = "上月第 $number 日",
                    occurredAt = month.atDay(number).atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli())
            }
        }
        launch()
        click("账簿")
        compose.waitForIdle()
        compose.onNodeWithTag("finance-date-control").performClick()
        if (month.year != current.year) compose.onNodeWithText("上一年").performClick()
        compose.onNodeWithText("${month.monthValue} 月").performClick()
        assertViewedMonth(month)
        fun awaitHistoricalRows() {
            compose.waitUntil(10_000) {
                compose.onAllNodes(hasContentDescription("$day，1 笔", substring = true)).fetchSemanticsNodes().isNotEmpty()
            }
        }
        awaitHistoricalRows()
        compose.onNode(hasContentDescription("$day，", substring = true)).performClick()
        fun assertSelectedRows() {
            compose.onNodeWithTag("finance-ledger").performScrollToNode(hasText(heading))
            compose.onNodeWithText(heading).assertExists()
            compose.onNodeWithTag("finance-ledger").performScrollToNode(hasText("上月第 1 日"))
            compose.onNodeWithText("上月第 1 日").assertExists()
            compose.onNodeWithText("上月第 2 日").assertDoesNotExist()
        }
        assertSelectedRows()
        click("账簿") // Tapping the already-selected tab must retain the month and day.
        assertSelectedRows()
        click("分析")
        selectPeriod("本月") // Insights has a different range; Transactions owns its viewed month/day.
        click("账簿")
        assertViewedMonth(month)
        awaitHistoricalRows()
        assertSelectedRows()
    }
}
