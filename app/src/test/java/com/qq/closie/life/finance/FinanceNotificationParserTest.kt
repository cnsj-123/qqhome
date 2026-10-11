package com.qq.closie.life.finance

import org.junit.Assert.*
import org.junit.Test

/** Synthetic fixtures, never real notification content. Protect only payment correctness. */
class FinanceNotificationParserTest {
    private fun inspect(title: String, body: String, app: String = "com.tencent.mm") =
        FinanceNotificationParser.inspect(app, "synthetic", title, body, 1_000L)

    @Test fun genericWechatTitleRequiresStructuredPaymentEvidence() {
        val result = inspect("微信", "微信支付\n付款金额：￥26.80\n支付成功")
        assertEquals(2_680L, result.proposal?.amountMinor)
        assertEquals(FinanceProposalKind.EXPENSE, result.proposal?.kind)
        assertNull(inspect("朋友", "今晚支付成功 ￥26.80").proposal)
        assertNull(inspect("微信", "朋友：支付成功 ￥26.80").proposal)
    }

    @Test fun thousandsAndCurrencyMarkersAreParsedExactly() {
        assertEquals(123_456L, inspect("支付宝", "付款成功 RMB 1,234.56", "com.eg.android.AlipayGphone").proposal?.amountMinor)
        assertEquals(2680L, inspect("支付凭证", "支付成功 26.80元").proposal?.amountMinor)
        listOf("￥1,23", "￥12.345", "-12.34元", "0元", "￥999999999999999999999999").forEach {
            assertNull(inspect("微信支付", "支付成功 $it").proposal)
        }
    }

    @Test fun multipleAmountsStayUnfilledInsteadOfChoosingBalanceOrDiscount() {
        val result = inspect("微信支付", "支付成功 ￥26.80；余额 ￥300.00")
        assertEquals(DiscoveryResult.AMBIGUOUS_AMOUNT, result.result)
        assertNotNull(result.proposal)
        assertNull(result.proposal?.amountMinor)
        assertNull(result.proposal?.canonicalId)
    }

    @Test fun refundsHaveIncomeDirectionButAreNotOrdinaryIncome() {
        val result = inspect("退款到账", "已退款 10.00元")
        assertEquals(FinanceProposalKind.INCOME, result.proposal?.kind)
        assertEquals(FinanceFlowRole.REFUND, result.proposal?.role)
        assertEquals(FinanceNature.OTHER, result.proposal?.nature)
    }

    @Test fun rejectionReasonsDistinguishUnsafeEvidence() {
        assertEquals(DiscoveryResult.UNSUCCESSFUL, inspect("微信支付", "待支付 ￥26.80").result)
        assertEquals(DiscoveryResult.UNKNOWN_DIRECTION, inspect("微信支付", "交易金额 26.80元").result)
        assertEquals(DiscoveryResult.UNKNOWN_DIRECTION, inspect("微信支付", "支付成功、收款成功 ￥26.80").result)
        assertEquals(DiscoveryResult.NO_AMOUNT, inspect("微信支付", "支付成功").result)
        assertNull(inspect("支付成功", "￥26.80", "unrelated.app").proposal)
    }
}
