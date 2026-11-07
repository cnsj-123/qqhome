package com.qq.closie.ui.lifeos.finance

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import com.qq.closie.life.finance.*
import com.qq.closie.ui.lifeos.theme.LifeOsTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate

/** Production query sheet/VM/workbench over real Room; no injected query rows or expected count patch. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],qualifiers="w405dp-h880dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FinanceQueryAcceptanceTest:FinanceRoomTest() {
    @get:Rule val compose=createComposeRule()
    private var models:ViewModelStore?=null
    private lateinit var vm:FinanceViewModel
    private val workbench=mutableStateOf(false)
    @After fun stopReader(){compose.runOnIdle {models?.clear()}}

    private fun launch() {
        compose.setContent {
            models=requireNotNull(LocalViewModelStoreOwner.current).viewModelStore
            LifeOsTheme {
                if(workbench.value) {
                    val state by vm.state.collectAsState()
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {FinanceQueryWorkbench(state,vm)}
                } else FinanceRoute(finance,{})
            }
        }
        compose.waitUntil(10_000) {compose.onAllNodesWithText("账簿").fetchSemanticsNodes().isNotEmpty()}
        compose.runOnIdle {vm=ViewModelProvider(requireNotNull(models),FinancePresentationFactory(finance))[FinanceViewModel::class.java]}
        awaitState {it.loaded && !it.busy}
    }
    private fun awaitState(predicate:(FinanceUiState)->Boolean) {
        compose.waitUntil(10_000) {compose.runOnIdle {};predicate(vm.state.value)}
    }
    private fun openAll() {
        compose.onNodeWithContentDescription("筛选与日期").performClick()
        compose.onNodeWithTag("query-period-ALL").performClick()
    }
    private fun previewCount(expected:Int) {
        compose.waitUntil(10_000) {
            compose.runOnIdle {}
            compose.onAllNodesWithTag("finance-query-preview-count").fetchSemanticsNodes().singleOrNull()
                ?.config?.getOrNull(SemanticsProperties.Text)?.joinToString("") {it.text}==expected.toString()
        }
        compose.onNodeWithTag("finance-query-preview-count").assertTextEquals(expected.toString())
    }
    private fun total(text:String) {
        compose.waitUntil(10_000) {compose.onAllNodes(hasText(text,substring=true)).fetchSemanticsNodes().isNotEmpty()}
    }
    private fun apply(expectedIds:Set<String>,expectedQuery:String?=null,expectedAccount:String?=null) {
        compose.onNodeWithText("查看结果").performClick()
        awaitState {it.ledger.map {row->row.id}.toSet()==expectedIds && it.filter.period==FinancePeriod.ALL &&
            (expectedQuery==null || it.filter.query==expectedQuery) &&
            (expectedAccount==null || it.filter.accountId==expectedAccount)}
        compose.onNodeWithTag("finance-query-sheet").assertDoesNotExist()
        assertEquals(expectedIds,vm.state.value.ledger.map {it.id}.toSet())
    }

    @Test fun accountCategoryTagAndDescriptionSearchPreviewTheSameRowsThatApplyShows() {
        val entry=runBlocking {
            val account=account("旅行卡")
            finance.saveEntry(accountId=account.id,direction=FinanceDirection.OUTFLOW,amountMinor=3_200,
                description="咖啡",category="交通",tags=listOf("旅途"),occurredAt=2_000)
        }
        val before=runBlocking {finance.portableSnapshot()}
        launch()
        for(query in listOf("旅行卡","交通","旅途","咖啡")) {
            openAll()
            compose.onNodeWithTag("finance-query-keyword").performTextReplacement(query)
            previewCount(1);total("资金流出 ¥32.00")
            apply(setOf(entry.id),expectedQuery=query)
            assertEquals(query,vm.state.value.filter.query)
        }
        assertEquals(before,runBlocking {finance.portableSnapshot()})
    }

    @Test fun oneTransferIsOnePreviewAndLedgerRowFromEitherAccountWithRealFundDirections() {
        val prepared=runBlocking {
            val a=account("A旅行卡");val b=account("B储蓄卡")
            Triple(a,b,finance.saveTransfer(sourceAccountId=a.id,targetAccountId=b.id,amountMinor=1_200,
                description="自己的账户调拨",occurredAt=2_000))
        }
        val (a,b,transfer)=prepared
        val before=runBlocking {finance.portableSnapshot()}
        launch();openAll();previewCount(1);total("净资金变化 ¥0.00");apply(setOf(transfer.id))
        for((account,label) in listOf(a to "资金流出 ¥12.00",b to "资金流入 ¥12.00")) {
            openAll()
            compose.onNode(hasText(account.name) and hasAnyAncestor(hasTestTag("finance-query-sheet"))).performScrollTo().performClick()
            previewCount(1);total(label);apply(setOf(transfer.id),expectedAccount=account.id)
            assertEquals(account.id,vm.state.value.filter.accountId)
        }
        assertEquals(before,runBlocking {finance.portableSnapshot()})
    }

    @Test fun incomeMixedCashAndMultipleCurrenciesKeepHonestDirectionLabels() {
        val account=runBlocking {
            val a=account("收入卡")
            finance.saveEntry(accountId=a.id,direction=FinanceDirection.INFLOW,amountMinor=8_000,description="收入",occurredAt=2_000)
            a
        }
        launch();openAll();previewCount(1);total("资金流入 ¥80.00")
        runBlocking {
            finance.saveEntry(accountId=account.id,direction=FinanceDirection.OUTFLOW,amountMinor=3_200,description="咖啡",occurredAt=2_000)
            val usd=finance.saveAccount(name="美元卡",kind=FinanceAccountKind.BANK_CARD,currencyCode="USD")
            finance.saveEntry(accountId=usd.id,direction=FinanceDirection.OUTFLOW,amountMinor=1_000,description="美元消费",occurredAt=2_000)
        }
        previewCount(3);total("净资金流入 ¥48.00");total("资金流出 USD 10.00")
        apply(runBlocking {finance.snapshot()}.entries.map {it.id}.toSet())
        val result=FinanceLedgerQuery.execute(vm.state.value.snapshot,vm.state.value.filter.querySpec())
        assertEquals(mapOf("CNY" to PersonalCostTotal(-4_800,0),"USD" to PersonalCostTotal(1_000,0)),result.totals)
    }

    @Test fun savedPersonalCostPreviewAndReopenedViewUseTheChosenCostBasis() {
        runBlocking {
            val shared=account("分摊卡")
            payment(shared.id,5_000,FinanceNature.SPLIT,1_000)
            payment(account("其他卡").id,9_000)
        }
        launch();openAll()
        compose.onNodeWithTag("finance-query-keyword").performTextReplacement("分摊卡")
        previewCount(1);total("资金流出 ¥50.00")
        compose.onNodeWithText("存为视图").performClick()
        compose.onNodeWithTag("finance-query-save-mode-PERSONAL_COST").performClick()
        compose.waitUntil(10_000) {compose.onAllNodes(hasText("个人成本 ¥10.00",substring=true) and
            hasAnyAncestor(hasTestTag("finance-query-save-dialog"))).fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithTag("finance-query-view-name").performTextReplacement("测试个人成本")
        compose.onNodeWithText("保存视图").performClick()
        awaitState {state->!state.busy && state.snapshot.v16?.savedQueryRows.orEmpty().any {it.name=="测试个人成本"}}
        val saved=vm.state.value.snapshot.v16!!.savedQueryRows.single {it.name=="测试个人成本"}
        val spec=finance.workspace.queryFrom(saved)
        assertEquals(FinanceQueryMode.PERSONAL_COST,spec.mode)
        assertEquals(PersonalCostTotal(1_000,0),FinanceQueryEngine.execute(vm.state.value.snapshot,spec).totals.getValue("CNY"))
        compose.onNodeWithContentDescription("取消查账条件").performClick()
        compose.runOnIdle {workbench.value=true}
        compose.onNodeWithText("真实资金流水").performScrollTo().performClick()
        total("资金流出 ¥140.00")
        compose.onNodeWithText("测试个人成本").performScrollTo().performClick()
        compose.onNode(hasText("个人成本 ¥10.00")).performScrollTo().assertExists()
    }

    @Test fun unknownDatesAndCrossMonthRefundsStayUnassignedOrInTheirActualAdjustmentMonth() {
        val january=FinanceDateRange.month(LocalDate.of(2025,1,1))
        val february=FinanceDateRange.month(LocalDate.of(2025,2,1))
        val purchaseTime=requireNotNull(january.startInclusive)+10_000L
        val refundTime=requireNotNull(february.startInclusive)+10_000L
        val data=runBlocking {
            val a=account()
            val payment=finance.saveEntry(accountId=a.id,direction=FinanceDirection.OUTFLOW,amountMinor=1_000,
                description="一月购买",occurredAt=purchaseTime,event=FinanceEventInput())
            val event=eventId(payment.id)
            val later=finance.saveEntry(accountId=a.id,direction=FinanceDirection.INFLOW,amountMinor=200,
                description="次月退款",occurredAt=refundTime,event=FinanceEventInput(role=FinanceFlowRole.REFUND,existingEventId=event))
            val undated=finance.saveEntry(accountId=a.id,direction=FinanceDirection.OUTFLOW,amountMinor=600,
                description="未归期普通支出",occurredAt=purchaseTime)
            db.financeV16Dao().putEntryTiming(listOf(FinanceEntryTimingEntity(undated.id,cashPrecision=FinanceTimePrecision.UNKNOWN,
                postedSource="未记录",createdAt=clock,updatedAt=clock)))
            finance.snapshot() to (payment.id to later.id)
        }
        launch()
        compose.runOnIdle {vm.applyFilter(FinanceFilter(period=FinancePeriod.CUSTOM,range=january))}
        awaitState {it.filter.range==january && it.ledger.map {r->r.id}==listOf(data.second.first)}
        assertEquals(1,vm.state.value.unknownCashDateCount)
        val first=financeQueryPreview(vm.state.value.snapshot,vm.state.value.filter.querySpec())
        assertEquals(1,first.count);assertEquals(1,first.undatedCount)
        assertEquals(PersonalCostTotal(1_000,0),vm.state.value.costSummary.getValue("CNY"))
        compose.runOnIdle {vm.applyFilter(FinanceFilter(period=FinancePeriod.CUSTOM,range=february))}
        awaitState {it.filter.range==february && it.ledger.map {r->r.id}==listOf(data.second.second)}
        assertEquals(PersonalCostTotal(-200,0),vm.state.value.costSummary.getValue("CNY"))
        assertEquals(data.first,runBlocking {finance.snapshot()})
    }

    @Test fun allUnknownPersonalCostShowsUnresolvedAmountWithoutAZeroTotal() {
        runBlocking {
            account()
            val friend=finance.economics.saveParty(finance.economics.token(),"合成朋友")
            finance.economics.confirmEconomic(finance.economics.token(),FinanceEconomicInput(
                description="承担关系待确认",currencyCode="CNY",occurredAt=2_000,
                valueMinor=12_000,valueBasis="确认购买价值",categoryId=null,allocationsComplete=true,
                funding=listOf(FinanceFundingInput("fund",friend,12_000,basis="确认外部付款")),
                shares=listOf(FinanceShareInput("share",12_000,"self",null,"fund",FinanceShareRelation.UNRESOLVED))))
        }
        launch();openAll();previewCount(0)
        compose.onNodeWithText("存为视图").performClick()
        compose.onNodeWithTag("finance-query-save-mode-PERSONAL_COST").performClick()
        previewCount(1)
        total("个人成本金额未知 · CNY · 1项待确认")
        compose.onAllNodes(hasText("个人成本 ¥0.00",substring=true) and
            hasAnyAncestor(hasTestTag("finance-query-save-dialog"))).assertCountEquals(0)
    }

    @Test fun ordinaryLedgerAndCashSavedViewUseTheirExplicitDatesAfterALaterPayment() {
        val january=FinanceDateRange.month(LocalDate.of(2025,1,1))
        val february=FinanceDateRange.month(LocalDate.of(2025,2,1))
        val data=runBlocking {
            val a=account("日期核对卡")
            val original=finance.saveEntry(accountId=a.id,direction=FinanceDirection.OUTFLOW,amountMinor=1_000,
                description="一月消费二月收付",occurredAt=requireNotNull(january.startInclusive)+10_000,event=FinanceEventInput())
            val payment=finance.saveEntry(id=original.id,accountId=a.id,direction=original.direction,amountMinor=original.amountMinor,
                description=original.description,occurredAt=requireNotNull(february.startInclusive)+10_000,expectedRevision=original.revision)
            finance.snapshot() to payment
        }
        launch()
        compose.runOnIdle {vm.applyFilter(FinanceFilter(period=FinancePeriod.CUSTOM,range=january))}
        awaitState {it.filter.range==january && it.ledgerQuery?.rows?.singleOrNull()?.id==data.second.id}
        assertTrue(vm.state.value.ledger.isEmpty())
        val filter=vm.state.value.filter
        assertEquals(1,financeQueryPreview(vm.state.value.snapshot,filter.querySpec()).count)
        assertEquals(0,financeQueryPreview(vm.state.value.snapshot,filter.querySpec(),FinanceLedgerDateBasis.CASH_MOVEMENT).count)
        compose.onNodeWithContentDescription("筛选与日期").performClick()
        previewCount(1)
        compose.onNodeWithText("存为视图").performClick()
        compose.onNodeWithTag("finance-query-save-mode-CASH_MOVEMENT").assertIsSelected()
        previewCount(0)
        compose.onNodeWithText("按真实收付日期查询").assertExists()
        compose.onNodeWithText("取消").performClick()
        previewCount(1)
        compose.onNodeWithContentDescription("取消查账条件").performClick()
        runBlocking {db.financeV16Dao().putEntryTiming(listOf(FinanceEntryTimingEntity(data.second.id,
            cashPrecision=FinanceTimePrecision.UNKNOWN,postedSource="未记录",createdAt=clock,updatedAt=clock)))}
        awaitState {data.second.id in it.unknownCashDateIds && it.ledgerQuery?.rows?.size==1}
        assertTrue(vm.state.value.ledger.isEmpty())
        assertTrue(vm.state.value.ledgerQuery!!.undatedRows.isEmpty())
        val cash=FinanceLedgerQuery.execute(vm.state.value.snapshot,filter.querySpec())
        assertTrue(cash.rows.isEmpty());assertEquals(1,cash.undatedRows.size)
        assertEquals(1,vm.state.value.unknownCashDateCount)
    }
}
