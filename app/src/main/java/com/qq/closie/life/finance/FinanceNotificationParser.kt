package com.qq.closie.life.finance

import java.math.BigDecimal
import java.util.UUID

data class FinanceParseResult(val result: DiscoveryResult, val proposal: FinanceProposalEntity? = null)

/** Notification evidence is only a suggestion. Ambiguous amounts are never guessed. */
object FinanceNotificationParser {
    val packages = mapOf("com.tencent.mm" to FinanceSource.WECHAT, "com.eg.android.AlipayGphone" to FinanceSource.ALIPAY)
    private const val number = "(?:[0-9]{1,3}(?:,[0-9]{3})+|[0-9]+)(?:\\.[0-9]{1,2})?"
    private val money = Regex("(?<![0-9.,+\\-])(?:[¥￥]\\s*($number)(?![0-9.,])|($number)\\s*元|(?:CNY|RMB)\\s*($number)(?![0-9.,]))", RegexOption.IGNORE_CASE)
    private val expense = Regex("支付成功|付款成功|成功付款|成功支付|扣款成功|已支付|已付款|已扣款|支付金额|付款金额|支出金额")
    private val income = Regex("收款到账|收款成功|成功收款|到账通知|已到账|已收款|退款成功|退款到账|已退款|收款金额")
    private val paymentTitle = Regex("支付|收款|到账|退款|扣款|交易提醒")
    private val alipayExpense = Regex("^\\s*你有一笔\\s*$number\\s*元\\s*的支出(?:[，,。.!！\\s]|$)")
    private val alipayReceipt = Regex("^\\s*(?:支付成功|付款成功|成功付款|成功支付|扣款成功|收款成功|收款到账|退款成功|退款到账|已退款)(?=[\\s：:，,¥￥]|$)")
    private val transferCountTitle = Regex("^你收到\\s*[0-9]+\\s*笔转账$")

    fun parse(packageName: String, sourceKey: String, title: String, body: String, occurredAt: Long): FinanceProposalEntity? =
        inspect(packageName, sourceKey, title, body, occurredAt).proposal

    fun inspect(packageName: String, sourceKey: String, title: String, body: String, occurredAt: Long): FinanceParseResult {
        val source = packages[packageName] ?: return FinanceParseResult(DiscoveryResult.NOT_PAYMENT)
        val text = (title.take(240) + "\n" + body.take(1800)).replace('\u00a0', ' ')
        if (listOf("失败", "未支付", "未付款", "待付款", "待支付", "验证码", "优惠券", "营销", "请付款", "待领取", "待收款").any(text::contains))
            return FinanceParseResult(DiscoveryResult.UNSUCCESSFUL)
        val genericTitle = title.trim().matches(Regex("(?:微信|支付宝)(?:[（(].*[）)])?"))
        val structuredBody = Regex("微信支付|支付凭证|交易提醒|付款金额|支付金额|收款到账|退款到账").containsMatchIn(body)
        val explicitAlipayExpense = source == FinanceSource.ALIPAY && alipayExpense.containsMatchIn(body)
        // An app-name title needs a structured receipt, not arbitrary order/chat text mentioning payment.
        val alipayCompletedReceipt = source == FinanceSource.ALIPAY && alipayReceipt.containsMatchIn(body)
        val transferNotice = transferCountTitle.matches(title.trim())
        if (!paymentTitle.containsMatchIn(title) && !transferNotice &&
            !(genericTitle && (structuredBody || explicitAlipayExpense || alipayCompletedReceipt)))
            return FinanceParseResult(DiscoveryResult.NOT_PAYMENT)
        // A transfer count is evidence of a notification, never evidence of money or ownership.
        if (transferNotice) return FinanceParseResult(
            if (money.containsMatchIn(text)) DiscoveryResult.TRANSFER_REVIEW else DiscoveryResult.NO_AMOUNT)
        val outflow = expense.containsMatchIn(text) || explicitAlipayExpense
        val inflow = income.containsMatchIn(text) || (title.contains("收款") && !outflow)
        if (outflow == inflow) return FinanceParseResult(DiscoveryResult.UNKNOWN_DIRECTION)
        val amounts = money.findAll(text).mapNotNull { match ->
            runCatching { BigDecimal(match.groupValues.drop(1).first(String::isNotBlank).replace(",", ""))
                .movePointRight(2).longValueExact() }.getOrNull()?.takeIf { it > 0 }
        }.toSet()
        if (amounts.isEmpty()) return FinanceParseResult(DiscoveryResult.NO_AMOUNT)
        val refund = text.contains("退款")
        val proposal = FinanceProposalPrivacy.sanitize(FinanceProposalEntity(UUID.randomUUID().toString(), source, sourceKey, "",
            System.currentTimeMillis(), occurredAt, if (inflow) FinanceProposalKind.INCOME else FinanceProposalKind.EXPENSE,
            amounts.singleOrNull(), "", nature = if (refund) FinanceNature.OTHER else FinanceNature.PERSONAL,
            role = if (refund) FinanceFlowRole.REFUND else if (inflow) FinanceFlowRole.OTHER else FinanceFlowRole.PAYMENT))
        return FinanceParseResult(if (amounts.size == 1) DiscoveryResult.CREATED else DiscoveryResult.AMBIGUOUS_AMOUNT, proposal)
    }
}
