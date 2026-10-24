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
    @Test fun knownForeignMixedAndUnknownCurrencyFieldsCannotCreateCnyCandidates()=runTest {
        for(raw in listOf("币种：USD\n实付：12.34","币种：JPY\n实付：12.34","币种：CHF\n实付：12.34",
            "币种：XYZ\n人民币12.34","币种：未知\n人民币12.34","币种：CNY/USD\n实付：12.34","人民币12.34 CHF",
            "人民币\nUSD 支付金额:12.34","支付成功\n实付：12.34\nKFC VIP POS","支付成功\n¥12.34")) {
            val fields=FinanceLocalOcrParser.parse(raw);assertNull(raw,fields.currencyCode)
            assertTrue(runCatching {finance.media.createScreenshotCandidate(finance.economics.token(),fields){true}}.isFailure)
        }
        assertTrue(finance.snapshot().entries.isEmpty())
    }
}
