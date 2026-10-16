package com.qq.closie.life.finance.acquisition

import com.qq.closie.life.finance.*
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle

/** Small field grammars shared by host adapters. No page concatenation or free-text summaries. */
internal object DetailSemantics {
    val timeLabels = setOf("支付时间", "付款时间", "转账时间", "收款时间", "创建时间", "下单时间", "订单时间", "退款时间", "消费时间")
    val referenceLabels = setOf("转账单号", "交易单号", "订单号", "订单编号", "总订单编号", "商户单号", "商家单号", "商家订单号", "商户订单号", "付款单号")
    val moneyLabels = setOf("实付款", "实付", "实际付款", "实付金额", "合计", "订单金额", "退款金额", "金额")
    val channelLabels = setOf("支付方式", "付款方式", "退款方式", "收款方式")
    val statuses = setOf("交易成功", "支付成功", "极速支付成功", "自动扣款成功", "代付成功", "退款成功", "还款成功", "完成", "订单已完成", "订单已送达", "已使用", "已消费", "买单成功！", "已存入零钱", "对方已收钱")
    val sources = mapOf("滴滴出行" to TransactionSourceHint.DIDI, "高德打车" to TransactionSourceHint.GAODE,
        "中国铁路12306技术中心" to TransactionSourceHint.RAILWAY_12306, "中国铁路网络有限公司" to TransactionSourceHint.RAILWAY_12306,
        "淘宝闪购" to TransactionSourceHint.ELEME, "饿了么" to TransactionSourceHint.ELEME,
        "小红书（电商平台）" to TransactionSourceHint.XIAOHONGSHU, "京东平台商户" to TransactionSourceHint.JD,
        "京东外卖" to TransactionSourceHint.JD, "京东白条" to TransactionSourceHint.JD_BAITIAO,
        "拼多多" to TransactionSourceHint.PINDUODUO)
    private val number = Regex("[+−-]?[¥￥]?(?:[0-9]+|[0-9]{1,3}(?:,[0-9]{3})+)(?:\\.[0-9]{1,2})?")
    private val dates = listOf("uuuu-M-d HH:mm:ss", "uuuu-M-d HH:mm", "uuuu/M/d HH:mm", "uuuu年M月d日 HH:mm:ss")
        .map { DateTimeFormatter.ofPattern(it).withResolverStyle(ResolverStyle.STRICT) }
    private val baitiaoChannels = setOf("白条", "京东白条", "已出账白条", "未出账白条")
    fun compact(value: String) = value.trim().replace(Regex("\\s+"), "")
    fun source(value: String): TransactionSourceHint? = sources[value.removePrefix("退款-")]
    fun money(value: String): String? {
        val v = compact(value).removePrefix("合计").removeSuffix("(免运费)").removeSuffix("（免运费）").removeSuffix("元")
        return v.takeIf { number.matches(it) }?.replace(",", "")?.replace('−', '-')
    }
    fun minor(value: String): Long? = money(value)?.let {
        runCatching { FinanceMoney.parse(it.removePrefix("+").removePrefix("-").removePrefix("¥").removePrefix("￥")) }.getOrNull()
    }?.takeIf { it > 0 }
    fun time(value: String): Long? = dates.firstNotNullOfOrNull { format ->
        runCatching { LocalDateTime.parse(value.trim().replace(Regex("日\\s*"), "日 "), format)
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() }.getOrNull()
    }
    fun reference(value: String): String? = compact(value).takeIf {
        it.matches(Regex("[A-Za-z0-9][A-Za-z0-9_-]{7,63}")) && it.count(Char::isDigit) >= 4
    }
    fun digest(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
    fun channel(value: String): PaymentChannelHint? = when {
        value.startsWith("亲情卡") -> PaymentChannelHint.FAMILY_CARD
        value.startsWith("花呗") -> PaymentChannelHint.HUABEI
        compact(value) in baitiaoChannels -> PaymentChannelHint.JD_BAITIAO
        value.startsWith("美团月付") -> PaymentChannelHint.MEITUAN_MONTHLY
        value == "微信支付" || value == "微信" -> PaymentChannelHint.WECHAT_PAY
        value == "支付宝" -> PaymentChannelHint.ALIPAY
        value == "零钱" || value == "零钱通" -> PaymentChannelHint.WECHAT_BALANCE
        value == "余额宝" -> PaymentChannelHint.YUEBAO
        value.startsWith("账户余额") -> PaymentChannelHint.ACCOUNT_BALANCE
        value.contains("储蓄卡") -> PaymentChannelHint.DEBIT_CARD
        value.contains("信用卡") -> PaymentChannelHint.CREDIT_CARD
        value == "银行卡" -> PaymentChannelHint.BANK_CARD
        else -> null
    }
    fun refundSummary(value: String) = value.startsWith("已退款") || value.startsWith("已全额退款") ||
        value.startsWith("已退款(") || value == "部分退款"
    fun value(label: String, value: String): String? = when (label) {
        in timeLabels -> value.trim().takeIf { time(it) != null }
        in referenceLabels -> reference(value)
        in moneyLabels -> money(value)
        in channelLabels -> channel(value)?.name
        "当前状态", "交易状态", "退款状态" -> value.trim().takeIf { it in statuses || refundSummary(it) }
            ?.let { if (refundSummary(it)) "REFUND_SUMMARY" else it }
        "转账说明" -> value.takeIf { it == "微信转账" }
        "收款方备注" -> value.takeIf { it == "二维码收款" }
        "消费场景" -> value.takeIf { it in setOf("外卖", "酒店民宿", "骑车", "还款") }
        "付款商家" -> if (value.isNotBlank() && value.length <= 64) source(value)?.name ?: "MERCHANT" else null
        "商品说明" -> if (value.matches(Regex("余额宝-[0-9]{4}\\.[0-9]{2}\\.[0-9]{2}-收益发放"))) "INVESTMENT_YIELD" else source(value)?.name
        "交易详情", "商品" -> source(value)?.name
        else -> null
    }
}

/** A fingerprint can use alternative label families, never a package-only or amount-only match. */
abstract class SemanticDetailAdapter : TransactionDetailAdapter {
    abstract val fingerprints: List<Set<String>>
    override val requiredLabels: Set<String> get() = fingerprints.flatten().toSet()
    override val fieldLabels get() = requiredLabels + optionalLabels
    open val optionalLabels: Set<String> = emptySet()
    override val pageMarkers = DetailSemantics.statuses + setOf("账单详情", "云闪付交易详情", "退款汇总", "还款详情", "红包详情", "收款详情")
    override fun hasFingerprint(labels: Set<String>, markers: Set<String>) = fingerprints.any(labels::containsAll)
    override fun acceptsValue(label: String, value: String) = DetailSemantics.value(label, value) != null
    override fun fieldValue(label: String, value: String) = DetailSemantics.value(label, value)
    protected fun low() = DetailResult(DetailRecognition.LOW_CONFIDENCE)
    protected fun detail(fields: VisibleDetailFields) = hasFingerprint(fields.fields.keys, fields.markers)
    protected fun amount(fields: VisibleDetailFields, vararg labels: String): String? {
        // Label priority is a semantic rule, never numerical magnitude or screen position.
        for (label in labels) fields.fields[label]?.let { return DetailSemantics.money(it) }
        return fields.amounts.singleOrNull()?.let(DetailSemantics::money)
    }
    protected fun evidence(fields: VisibleDetailFields, observed: Long, host: String, amount: String?, time: String?,
        reference: String?, namespace: String, source: TransactionSourceHint?, channelLabel: String?,
        kind: FinanceProposalKind = FinanceProposalKind.EXPENSE, refund: Boolean = false,
        orderReference: String? = null): DetailResult = runCatching {
        val minor = requireNotNull(amount?.let(DetailSemantics::minor))
        val occurred = requireNotNull(time?.let(DetailSemantics::time))
        val ref = requireNotNull(reference?.let(DetailSemantics::reference))
        val channel = channelLabel?.let { fields.fields[it] }?.let { v -> PaymentChannelHint.entries.find { it.name == v } }
        val result = NormalizedTransactionEvidence(host, minor, "CNY", kind, occurred, observed,
            DetailSemantics.digest("$namespace:$ref"), "$host-detail-v2", source ?: fields.transactionSource, channel,
            if (refund) FinanceFlowRole.REFUND else if (kind == FinanceProposalKind.INCOME) FinanceFlowRole.OTHER else FinanceFlowRole.PAYMENT,
            orderReference?.let { DetailSemantics.digest("$host:order:$it") })
        result.toProposal()
        DetailResult(DetailRecognition.RECOGNIZED, result)
    }.getOrElse { low() }
}
