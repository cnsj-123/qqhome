package com.qq.closie.life.finance

import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class FinanceOcrCurrencyContextTest:FinanceRoomTest() {
    @Test fun merchantAcronymsDoNotRejectExplicitCnyOrRmbAndBothOcrPathsCreateOnlyCandidates()=runTest {
        for(currency in listOf("人民币","CNY","RMB"))for(merchant in listOf("KFC","VIP会员","POS")) {
            val fields=FinanceLocalOcrParser.parse("支付成功\n币种："+currency+"\n实付：12.34\n商户："+merchant)
            assertEquals("CNY",fields.currencyCode);assertEquals(1234L,fields.amountMinor)
            finance.media.createScreenshotCandidate(finance.economics.token(),fields){true}
            finance.media.createOcrCandidate(finance.economics.token(),fields,FinanceProposalKind.EXPENSE,
                FinanceSelectedDocument("content://synthetic/"+currency+merchant,"image/png","a".repeat(64),123),false)
        }
        assertTrue(finance.snapshot().entries.isEmpty())
    }
    @Test fun foreignAndUnknownCurrencyCandidatesPreserveDecimalWithoutGuessingCny()=runTest {
        val known = listOf("USD" to 1234L, "JPY" to null, "CHF" to 1234L)
        for ((code, minor) in known) {
            val fields = FinanceLocalOcrParser.parse("币种：$code\n实付：12.34")
            assertEquals(code, fields.currencyCode)
            assertEquals(minor, fields.amountMinor)
            assertEquals("12.34", fields.amountText)
        }
        for(raw in listOf(
            "币种：XYZ\n人民币12.34","币种：未知\n人民币12.34","币种：CNY/USD\n实付：12.34","人民币12.34 CHF",
            "人民币\nUSD 支付金额:12.34","支付成功\n实付：12.34\nKFC VIP POS","支付成功\n¥12.34")) {
            val fields=FinanceLocalOcrParser.parse(raw);assertNull(raw,fields.currencyCode)
            val id = finance.media.createScreenshotCandidate(finance.economics.token(), fields) { true }
            val snapshot = finance.snapshot()
            assertNull(snapshot.v4!!.proposals.single { it.id == id }.amountMinor)
            assertEquals("12.34", snapshot.v16!!.reviewSessionRows.single { it.proposalId == id }.amountText)
        }
        assertTrue(finance.snapshot().entries.isEmpty())
    }
}
