package com.qq.closie.ui.lifeos.finance

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w393dp-h852dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FinanceQueryDetailsTest : FinanceRoomTest() {
    @get:Rule val compose = createComposeRule()
    private val models = ViewModelStore()

    @After fun clearModels() { models.clear() }

    private fun viewModel(): FinanceViewModel = ViewModelProvider(models, object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = FinanceViewModel(finance) as T
    })[FinanceViewModel::class.java]

    private fun showQuery(snapshot: FinanceSnapshot) {
        val state = FinanceUiState(snapshot = snapshot, allRows = FinanceProjection.ledger(snapshot), loaded = true, loading = false)
        val vm = viewModel()
        compose.setContent {
            LifeOsTheme {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) { FinanceQueryWorkbench(state, vm) }
            }
        }
    }

    @Test fun normalCostAndCashResultsOpenExistingFactsWithoutChangingTheQueryOrLedger() {
        val data = runBlocking { val account = account(); payment(account.id, 12_345); finance.snapshot() }
        val cost = FinanceCostProjection.rows(data).single()
        val entry = data.entries.single()
        showQuery(data)

        compose.onNodeWithTag("finance-query-cost-" + cost.id).performScrollTo().performClick()
        compose.onNodeWithText("经济事项详情").assertExists()
        compose.onNodeWithText("编辑出资与承担").assertExists()
        compose.onNodeWithText("收起").performClick()
        compose.onNodeWithTag("finance-query-cost-" + cost.id).assertExists()

        compose.onNodeWithText("真实资金流水").performScrollTo().performClick()
        compose.onNodeWithTag("finance-query-cash-" + entry.id).performScrollTo().performClick()
        compose.onNodeWithText("账单详情").assertExists()
        compose.onNodeWithText("编辑账单").assertExists()
        assertEquals(data, runBlocking { finance.snapshot() })
    }

    @Test fun economicDetailsKeepUnresolvedShareUnknownAndOnlyRequestEditingOnTheEditButton() {
        val event = runBlocking {
            val friend = finance.economics.saveParty(finance.economics.token(), "合成朋友")
            finance.economics.confirmEconomic(finance.economics.token(), FinanceEconomicInput(
                description = "关系待确认的事项", currencyCode = "CNY", occurredAt = 2_000,
                valueMinor = 12_000, valueBasis = "确认购买价值", categoryId = null, allocationsComplete = true,
                funding = listOf(FinanceFundingInput("fund", friend, 12_000, basis = "确认外部付款")),
                shares = listOf(FinanceShareInput("share", 12_000, "self", null, "fund", FinanceShareRelation.UNRESOLVED))
            ))
        }
        val data = runBlocking { finance.snapshot() }
        var editRequests = 0
        compose.setContent {
            LifeOsTheme { FinanceEconomicDetails(data, event, onEdit = { editRequests++ }, onOpenCash = {}, onClose = {}) }
        }
        compose.onNodeWithText("事项价值").assertExists()
        compose.onNodeWithText("已确认 ¥0.00 · 另有待确认").assertExists()
        compose.onAllNodesWithText("暂无本人流水").assertCountEquals(2)
        assertEquals(0, editRequests)
        assertEquals(data, runBlocking { finance.snapshot() })

        compose.onNodeWithText("编辑出资与承担").performClick()
        assertEquals(1, editRequests)
        assertEquals(data, runBlocking { finance.snapshot() })
    }

    @Test fun purposeLimitedResultCannotOpenTheWholeSharedCashEntryOrAnotherEvent() {
        val expires = System.currentTimeMillis() + 60_000
        val prepared = runBlocking {
            val account = account()
            val first = payment(account.id, 10_000)
            val firstEvent = eventId(first.id)
            val second = finance.saveEntry(accountId = account.id, direction = FinanceDirection.OUTFLOW,
                amountMinor = 1, description = "范围外的私密事项", occurredAt = 4_000)
            val secondEvent = eventId(second.id)
            finance.allocateEntry(first.id, listOf(
                FinanceAllocation(firstEvent, FinanceFlowRole.PAYMENT, 5_000),
                FinanceAllocation(secondEvent, FinanceFlowRole.PAYMENT, 5_000)
            ), first.revision)
            finance.workspace.grant(finance.economics.token(), FinanceReadPurpose.LOCAL_QUERY, "local-conditions",
                setOf(firstEvent), emptySet(), true, true, true, expires)
            finance.snapshot() to firstEvent
        }
        val (data, eventId) = prepared
        val cost = FinanceCostProjection.rows(data).single { it.eventId == eventId }
        showQuery(data)
        compose.onNodeWithTag("finance-query-local-input").performScrollTo().performTextInput("全部 个人成本")
        compose.onNodeWithText("本次查询授权 · 请选择").performScrollTo().performClick()
        compose.onNodeWithText("授权至 " + FinanceTime.input(expires)).performScrollTo().performClick()
        compose.onNodeWithText("解释为可见查询条件").performScrollTo().performClick()

        compose.onNodeWithTag("finance-query-cost-" + cost.id).performScrollTo().assertHasNoClickAction()
        compose.onNodeWithText("经济事项详情").assertDoesNotExist()
        compose.onNodeWithText("账单详情").assertDoesNotExist()
        compose.onNodeWithText("范围外的私密事项", substring = true).assertDoesNotExist()
        assertEquals(data, runBlocking { finance.snapshot() })
    }
}
