package com.qq.closie.life.finance

import com.qq.closie.life.finance.acquisition.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/** Synthetic node structures only. Owner images and actual transaction values are never fixtures. */
class HostDetailAdaptersTest {
    private val now = LocalDateTime.of(2026, 1, 3, 12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    private val time = "2026-01-02 10:20:30"
    private class N(override val text: String? = null, override val visible: Boolean = true,
        override val sensitive: Boolean = false, val nodes: List<N> = emptyList()) : DetailNode {
        var owner: N? = null
        init { nodes.forEach { it.owner = this } }
        override val childCount get() = nodes.size
        override fun child(index: Int) = nodes[index]
        override fun parent() = owner
        override fun close() = Unit
    }
    private fun node(vararg text: String) = N(nodes = text.map { N(it) })
    private fun row(label: String, value: String) = node(label, value)
    private fun page(vararg children: N) = N(nodes = children.toList())
    private fun read(adapter: TransactionDetailAdapter, root: N) = SemanticDetailReader.read(root, adapter)
    private fun parse(adapter: TransactionDetailAdapter, root: N) = read(adapter, root)?.let { adapter.parse(it, now) }
    private fun result(adapter: TransactionDetailAdapter, root: N) = requireNotNull(parse(adapter, root)?.evidence)
    private fun alipay(vararg extra: N) = page(N("账单详情"), node("滴滴出行", "-¥12.34", "交易成功"),
        row("支付时间", time), row("付款方式", "花呗"), row("订单号", "ORDER-123456"), *extra)
    private fun union(refund: Boolean = false, status: String = if (refund) "退款成功" else "交易成功") = page(
        N("云闪付交易详情"), node(if (refund) "退款-中国铁路12306技术中心" else "中国铁路12306技术中心", if (refund) "+¥2.00" else "-¥12.34"),
        row("当前状态", status), row(if (refund) "退款金额" else "订单金额", if (refund) "¥2.00" else "¥15.00"),
        row(if (refund) "退款方式" else "付款方式", "Example Bank储蓄卡[1234]"),
        row(if (refund) "退款时间" else "订单时间", "2026年1月2日 10:20:30"),
        row("订单编号", "ORDER-123456"), row("商户订单号", "MERCHANT-123456"))

    @Test fun sevenHostAdaptersRouteOnlyAfterExplicitOptIn() {
        assertEquals(7, ProviderRegistry.providers.size)
        assertEquals(7, ProviderRegistry.providers.map { it.adapter }.distinct().size)
        ProviderRegistry.providers.forEach { provider ->
            assertFalse(provider.defaultEnabled)
            provider.packageNames.forEach { pkg ->
                assertNull(VisibleTransactionEvidenceRouter.provider(pkg, emptySet()))
                assertEquals(provider, VisibleTransactionEvidenceRouter.provider(pkg, setOf(provider.key)))
            }
        }
        assertNull(VisibleTransactionEvidenceRouter.provider("unknown.host", ProviderRegistry.providers.map { it.key }.toSet()))
        assertNull(VisibleTransactionEvidenceRouter.provider("com.tmall.wireless", setOf(VisibleProviderKeys.TAOBAO)))
    }

    @Test fun hostBusinessChannelAndCanonicalAccountAreFourDifferentThings() {
        val evidence = result(AlipayDetailAdapter, alipay())
        assertEquals(VisibleProviderKeys.ALIPAY, evidence.hostProvider)
        assertEquals(TransactionSourceHint.DIDI, evidence.transactionSource)
        assertEquals(PaymentChannelHint.HUABEI, evidence.paymentChannelHint)
        assertNull(evidence.toProposal().accountId)
        assertNull(evidence.toProposal().targetAccountId)
        assertEquals("", evidence.toProposal().evidenceText)
        assertEquals(1234L, evidence.amountMinor)
        // Changing the extractor does not change real-world identity.
        assertEquals(evidence.toProposal().sourceKey, evidence.copy(extractionVersion = "alipay-detail-v99").toProposal().sourceKey)
    }

    @Test fun finalHeaderIgnoresDiscountsAndOriginalOrderAmount() {
        val evidence = result(AlipayDetailAdapter, alipay(row("订单金额", "¥99.00"), row("支付宝随机立减", "-¥86.66")))
        assertEquals(1234L, evidence.amountMinor)
        assertEquals(1234L, result(UnionPayDetailAdapter, union()).amountMinor)
    }

    @Test fun unsignedFamilyCardPaymentIsExpenseNotIncome() {
        val p = page(N("账单详情"), node("Example Merchant", "12.34", "交易成功"), row("支付时间", time),
            row("付款方式", "亲情卡(Example User)"), row("订单号", "ORDER-123456"))
        val e = result(AlipayDetailAdapter, p)
        assertEquals(FinanceProposalKind.EXPENSE, e.kind)
        assertEquals(PaymentChannelHint.FAMILY_CARD, e.paymentChannelHint)
        assertFalse(e.toProposal().toString().contains("Example User"))
    }

    @Test fun taobaoFinalOrderTotalWinsOverItemPriceAndDiscount() {
        val p = page(N("交易成功"), row("实付款", "¥12.34"), row("商品总价", "¥99.00"),
            row("店铺优惠", "-¥86.66"), row("订单号", "ORDER-123456"), row("支付时间", time))
        assertEquals(1234L, result(TaobaoDetailAdapter, p).amountMinor)
        val missingTime = page(N("交易成功"), row("实付款", "¥12.34"), row("订单号", "ORDER-123456"))
        assertNull(parse(TaobaoDetailAdapter, missingTime)?.evidence)
    }

    @Test fun jdBillingBaitiaoIsExplicitChannelAndRepaymentIsNotIncome() {
        val p = page(N("账单详情"), node("京东平台商户", "-12.34", "交易成功"),
            row("创建时间", time), row("支付方式", "白条"), row("总订单编号", "ORDER-123456"), row("商户单号", "MERCHANT-123456"))
        val e = result(JdDetailAdapter, p)
        assertEquals(TransactionSourceHint.JD, e.transactionSource)
        assertEquals(PaymentChannelHint.JD_BAITIAO, e.paymentChannelHint)
        val repayment = page(N("账单详情"), node("京东白条", "12.34", "还款成功"), row("创建时间", time),
            row("支付方式", "Example Bank储蓄卡(1234)"), row("订单号", "ORDER-123456"))
        assertEquals(FinanceProposalKind.TRANSFER, result(JdDetailAdapter, repayment).kind)
    }

    @Test fun embeddedServicesDoNotBecomeNewHosts() {
        val flash = page(N("订单已送达"), N("闪购"), row("实付", "¥12.34"),
            row("订单号", "ORDER-123456"), row("下单时间", time))
        val e = result(TaobaoDetailAdapter, flash)
        assertEquals(VisibleProviderKeys.TAOBAO, e.hostProvider)
        assertEquals(TransactionSourceHint.ELEME, e.transactionSource)
        assertFalse(ProviderRegistry.providers.any { it.key in setOf("eleme", "didi", "12306", "jd-baitiao") })
    }

    @Test fun unsignedYieldNeedsExplicitYieldSemanticsAndStrongReference() {
        val p = page(N("账单详情"), node("Example Fund", "12.34", "交易成功"), row("创建时间", time),
            row("商品说明", "余额宝-2026.01.01-收益发放"), row("订单号", "ORDER-123456"))
        assertEquals(FinanceProposalKind.INCOME, result(AlipayDetailAdapter, p).kind)
    }

    @Test fun meituanWrappedReferenceAndMonthlyChannelRemainMinimized() {
        val p = page(N("账单详情"), node("Example Merchant", "-12.34", "极速支付成功"),
            row("下单时间", time), row("支付方式", "美团月付(分3期)"),
            node("交易单号", "ORDER-", "123456", "复制"), row("商家单号", "MERCHANT-123456"))
        val e = result(MeituanDetailAdapter, p)
        assertEquals(PaymentChannelHint.MEITUAN_MONTHLY, e.paymentChannelHint)
        assertEquals(1234L, e.amountMinor)
        assertFalse(e.toProposal().sourceKey.contains("ORDER-123456"))
    }

    @Test fun pinduoduoOnlyUsesOrderTimeAndExcludesLogistics() {
        val p = page(N("交易成功"), N("实付：¥12.34（免运费）"), row("订单编号", "ORDER-123456"),
            N("支付方式：微信支付"), N("下单时间：$time"), N("拼单时间：2026-01-02 10:21:00"),
            row("快递单号", "TRACKING-123456"), row("物流公司", "Example Logistics"),
            N("发货时间：2026-01-02 11:00:00"), N("成交时间：2026-01-03 09:00:00"))
        val fields = requireNotNull(read(PinduoduoDetailAdapter, p))
        assertFalse(fields.fields.containsKey("快递单号"))
        assertFalse(fields.fields.containsKey("发货时间"))
        val e = requireNotNull(PinduoduoDetailAdapter.parse(fields, now).evidence)
        assertEquals(requireNotNull(DetailSemantics.time(time)), e.occurredAt)
        assertEquals(PaymentChannelHint.WECHAT_PAY, e.paymentChannelHint)
    }

    @Test fun refundGetsRefundRoleAndSeparateIdentityFromOriginalPayment() {
        val paid = result(UnionPayDetailAdapter, union())
        val refund = result(UnionPayDetailAdapter, union(refund = true))
        assertEquals(FinanceFlowRole.REFUND, refund.role)
        assertEquals(FinanceFlowRole.REFUND, refund.toProposal().role)
        assertEquals(TransactionSourceHint.RAILWAY_12306, refund.transactionSource)
        assertNotEquals(paid.referenceDigest, refund.referenceDigest)
        assertEquals(paid.relatedOrderDigest, refund.relatedOrderDigest)
        assertNull(parse(UnionPayDetailAdapter, union(status = "已全额退款"))?.evidence)
    }

    @Test fun missingReferenceMissingTimeAndSummaryRefundFailClosed() {
        val p = page(N("账单详情"), node("Example Merchant", "12.34", "交易成功"), row("创建时间", time))
        assertNull(parse(AlipayDetailAdapter, p)?.evidence)
        assertNull(parse(AlipayDetailAdapter, alipay(N("已退款")))?.evidence)
        assertNull(parse(JdDetailAdapter, page(N("账单详情"), node("京东白条", "12.34", "还款成功"), row("创建时间", time)))?.evidence)
    }

    @Test fun ambiguousRowsHeadersAndFlatWholePagesFailClosed() {
        assertNull(parse(AlipayDetailAdapter, alipay(row("订单号", "ORDER-999999")))?.evidence)
        assertNull(parse(AlipayDetailAdapter, alipay(node("12.35", "交易成功")))?.evidence)
        val flat = page(N("账单详情"), N("交易成功"), N("-12.34"), N("支付时间"), N(time), N("付款方式"), N("花呗"), N("订单号"), N("ORDER-123456"))
        assertNull(parse(AlipayDetailAdapter, flat)?.evidence)
    }

    @Test fun invisibleEditableAndOverflowTreesAreNeverEvidence() {
        val sensitiveTree = N(sensitive = true, nodes = listOf(row("订单号", "ORDER-123456")))
        val p = page(N("账单详情"), node("Example Merchant", "-12.34", "交易成功"), row("支付时间", time), row("付款方式", "支付宝"), sensitiveTree)
        assertNull(read(AlipayDetailAdapter, p))
        val hidden = N(visible = false, nodes = listOf(row("订单号", "ORDER-123456")))
        assertNull(read(AlipayDetailAdapter, page(N("账单详情"), hidden)))
        assertNull(read(AlipayDetailAdapter, N(nodes = List(401) { N("irrelevant") })))
    }

    @Test fun wechatFamiliesUseStatusTimeReferenceAndSignedFinalAmount() {
        fun payment(vararg extra: N) = page(node("Example Merchant", "-12.34"), row("当前状态", "支付成功"),
            row("支付时间", "2026年1月2日 10:20:30"), row("支付方式", "零钱"), row("交易单号", "ORDER-123456"), *extra)
        // Merchant/mini-program, red packet, group collection share the proven payment fields.
        listOf(payment(), payment(N("红包详情")), payment(N("收款详情")), payment(row("商品", "Example Item"))).forEach {
            assertEquals(FinanceProposalKind.EXPENSE, result(WeChatDetailAdapter, it).kind)
        }
        val qr = page(node("Example Merchant", "-12.34"), row("当前状态", "支付成功"), row("收款方备注", "二维码收款"),
            row("转账时间", "2026年1月2日 10:20:30"), row("转账单号", "ORDER-123456"))
        assertEquals(1234L, result(WeChatDetailAdapter, qr).amountMinor)
        val sent = page(node("Example User", "-12.34"), row("当前状态", "对方已收钱"), row("转账说明", "微信转账"),
            row("转账时间", "2026年1月2日 10:20:30"), row("转账单号", "ORDER-123456"))
        assertEquals(FinanceProposalKind.EXPENSE, result(WeChatDetailAdapter, sent).kind)
        val platform = page(node("Example Platform", "+12.34"), row("付款商家", "Example Merchant"), row("收款方式", "零钱"),
            row("转账时间", "2026年1月2日 10:20:30"), row("付款单号", "ORDER-123456"))
        assertEquals(FinanceProposalKind.INCOME, result(WeChatDetailAdapter, platform).kind)
    }
}
