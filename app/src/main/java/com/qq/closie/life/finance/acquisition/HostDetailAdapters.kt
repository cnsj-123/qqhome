package com.qq.closie.life.finance.acquisition

import com.qq.closie.life.finance.*

private fun VisibleDetailFields.value(vararg labels: String) = labels.firstNotNullOfOrNull(fields::get)
private fun VisibleDetailFields.hasStatus(vararg statuses: String) = statuses.any {
    it in markers || fields["当前状态"] == it || fields["交易状态"] == it || fields["退款状态"] == it
}
private fun VisibleDetailFields.refundedSummary() = "REFUND_SUMMARY" in markers || "REFUND_SUMMARY" in fields.values

/** Current Owner UI families; actual node relationships must pass the bounded reader as well. */
object WeChatDetailAdapter : SemanticDetailAdapter() {
    override val fingerprints = listOf(
        setOf("当前状态", "转账说明", "转账时间", "转账单号"),
        setOf("当前状态", "收款方备注", "转账时间", "转账单号"),
        setOf("当前状态", "支付时间", "支付方式", "交易单号"),
        setOf("付款商家", "收款方式", "转账时间", "付款单号"))
    override val optionalLabels = setOf("收款时间", "商品", "商户单号")
    override val signedHeadline = true
    override fun parse(fields: VisibleDetailFields, observedAt: Long): DetailResult {
        if (!detail(fields)) return DetailResult(DetailRecognition.NOT_A_DETAIL_PAGE)
        val paid = amount(fields) ?: return low()
        if (fields.refundedSummary()) return low()
        val received = fields.fields["当前状态"] == "已存入零钱"
        if (fields.fields["转账说明"] == "微信转账") {
            if (received) {
                if (!paid.startsWith("+")) return low()
                val sent = fields.fields["转账时间"]?.let(DetailSemantics::time) ?: return low()
                val receipt = fields.fields["收款时间"]?.let(DetailSemantics::time) ?: return low()
                if (sent > receipt) return low()
            } else if (fields.fields["当前状态"] != "对方已收钱" || !paid.startsWith("-")) return low()
            return evidence(fields, observedAt, VisibleProviderKeys.WECHAT, paid,
                fields.value(if (received) "收款时间" else "转账时间"), fields.value("转账单号"),
                "WECHAT", null, null, if (received) FinanceProposalKind.INCOME else FinanceProposalKind.EXPENSE)
        }
        if (fields.fields.containsKey("付款商家")) {
            if (!paid.startsWith("+")) return low()
            val source = fields.fields["付款商家"]?.let { key -> TransactionSourceHint.entries.find { it.name == key } }
            return evidence(fields, observedAt, VisibleProviderKeys.WECHAT, paid, fields.value("转账时间"),
                fields.value("付款单号"), "WECHAT:platform", source, "收款方式", FinanceProposalKind.INCOME)
        }
        if (!fields.hasStatus("支付成功") || !paid.startsWith("-")) return low()
        return if (fields.fields["收款方备注"] == "二维码收款")
            evidence(fields, observedAt, VisibleProviderKeys.WECHAT, paid, fields.value("转账时间"),
                fields.value("转账单号"), "WECHAT", null, "支付方式")
        else evidence(fields, observedAt, VisibleProviderKeys.WECHAT, paid, fields.value("支付时间"),
            fields.value("交易单号"), "WECHAT:transaction", null, "支付方式", orderReference = fields.value("商户单号"))
    }
}

object AlipayDetailAdapter : SemanticDetailAdapter() {
    override val fingerprints = listOf(setOf("支付时间", "付款方式", "订单号"),
        setOf("创建时间", "订单号"), setOf("退款金额", "退款时间", "订单号"))
    override val optionalLabels = setOf("交易状态", "退款状态", "商家订单号", "退款方式", "商品说明", "交易详情")
    override val headlineStatuses = setOf("交易成功", "自动扣款成功", "代付成功", "退款成功")
    override fun parse(fields: VisibleDetailFields, observedAt: Long): DetailResult {
        if (!detail(fields)) return DetailResult(DetailRecognition.NOT_A_DETAIL_PAGE)
        if ("账单详情" !in fields.markers || fields.refundedSummary()) return low()
        val refund = fields.hasStatus("退款成功") && fields.fields.containsKey("退款时间")
        val headline = amount(fields, *if (refund) arrayOf("退款金额") else emptyArray()) ?: return low()
        val income = !refund && fields.fields.containsKey("创建时间") && !headline.startsWith("-") &&
            (headline.startsWith("+") || fields.fields["商品说明"] == "INVESTMENT_YIELD")
        if (!refund && !fields.hasStatus("交易成功", "自动扣款成功", "代付成功")) return low()
        if (!refund && !income && headline.startsWith("+")) return low()
        val source = fields.value("商品说明", "交易详情")?.let { key -> TransactionSourceHint.entries.find { it.name == key } }
            ?: fields.transactionSource
        return evidence(fields, observedAt, VisibleProviderKeys.ALIPAY, headline,
            fields.value(if (refund) "退款时间" else if (income) "创建时间" else "支付时间"), fields.value("订单号"),
            if (refund) "ALIPAY:refund" else "ALIPAY:transaction", source,
            if (refund) "退款方式" else "付款方式", if (refund || income) FinanceProposalKind.INCOME else FinanceProposalKind.EXPENSE,
            refund, fields.value("商家订单号"))
    }
}

object TaobaoDetailAdapter : SemanticDetailAdapter() {
    override val fingerprints = listOf(setOf("实付款", "订单号", "支付时间"), setOf("实付款", "订单号", "下单时间"),
        setOf("实付", "订单号", "支付时间"), setOf("实付", "订单号", "下单时间"),
        setOf("退款金额", "退款时间", "订单号"))
    override val optionalLabels = setOf("商家订单号", "支付方式", "退款方式")
    override val pageMarkers = super.pageMarkers + setOf("闪购", "淘宝闪购", "饿了么", "天猫超市", "天猫国际")
    override fun parse(fields: VisibleDetailFields, observedAt: Long): DetailResult {
        if (!detail(fields)) return DetailResult(DetailRecognition.NOT_A_DETAIL_PAGE)
        val refund = fields.hasStatus("退款成功") && fields.fields.containsKey("退款金额") && fields.fields.containsKey("退款时间")
        if (!refund && (fields.refundedSummary() || fields.hasStatus("退款成功") || !fields.hasStatus("交易成功", "订单已送达"))) return low()
        // Per-item paid prices and the order total must not be mixed. Duplicate rows are rejected by the reader.
        val paid = amount(fields, *if (refund) arrayOf("退款金额") else arrayOf("实付款", "实付")) ?: return low()
        if (paid.startsWith("-")) return low()
        val source = when {
            fields.hasStatus("订单已送达") && fields.markers.any { it in setOf("闪购", "淘宝闪购", "饿了么") } -> TransactionSourceHint.ELEME
            fields.markers.any { it in setOf("天猫超市", "天猫国际") } -> TransactionSourceHint.TMALL
            else -> fields.transactionSource ?: TransactionSourceHint.TAOBAO
        }
        return evidence(fields, observedAt, VisibleProviderKeys.TAOBAO, paid,
            if (refund) fields.value("退款时间") else fields.value("支付时间", "下单时间"), fields.value("订单号"),
            if (refund) "TAOBAO:refund" else "TAOBAO:order", source,
            if (refund) "退款方式" else "支付方式", if (refund) FinanceProposalKind.INCOME else FinanceProposalKind.EXPENSE,
            refund, fields.value("商家订单号"))
    }
}

object JdDetailAdapter : SemanticDetailAdapter() {
    override val fingerprints = listOf(setOf("实付款", "订单编号", "支付方式", "支付时间"),
        setOf("实付款", "订单编号", "支付方式", "下单时间"),
        setOf("创建时间", "支付方式", "总订单编号", "商户单号"), setOf("创建时间", "支付方式", "订单号"),
        setOf("退款金额", "退款时间", "商户单号"))
    override val optionalLabels = setOf("合计", "交易状态", "退款状态", "退款方式")
    override val headlineStatuses = setOf("交易成功", "还款成功", "退款成功")
    override fun parse(fields: VisibleDetailFields, observedAt: Long): DetailResult {
        if (!detail(fields)) return DetailResult(DetailRecognition.NOT_A_DETAIL_PAGE)
        val refund = fields.hasStatus("退款成功") && fields.fields.containsKey("退款时间")
        val repayment = fields.hasStatus("还款成功")
        if (!refund && fields.refundedSummary()) return low()
        val billing = "账单详情" in fields.markers
        if (!refund && !repayment && !fields.hasStatus("完成", "订单已完成", "交易成功")) return low()
        val paid = amount(fields, *if (refund) arrayOf("退款金额") else if (billing) emptyArray() else arrayOf("实付款")) ?: return low()
        if (repayment && paid.startsWith("-")) return low()
        if (!refund && !repayment && billing && !paid.startsWith("-")) return low()
        return evidence(fields, observedAt, VisibleProviderKeys.JD, paid,
            if (refund) fields.value("退款时间") else fields.value("支付时间", "创建时间", "下单时间"),
            if (billing) fields.value("商户单号", "订单号") else fields.value("订单编号"),
            if (refund) "JD:refund" else if (repayment) "JD:repayment" else if (billing) "JD:bill" else "JD:order",
            if (repayment) TransactionSourceHint.JD_BAITIAO else TransactionSourceHint.JD,
            if (refund) "退款方式" else "支付方式",
            if (refund) FinanceProposalKind.INCOME else if (repayment) FinanceProposalKind.TRANSFER else FinanceProposalKind.EXPENSE,
            refund, fields.value("总订单编号"))
    }
}

object MeituanDetailAdapter : SemanticDetailAdapter() {
    override val fingerprints = listOf(setOf("支付方式", "下单时间", "交易单号", "商家单号"),
        setOf("实际付款", "订单编号", "付款时间"), setOf("实付金额", "订单编号", "消费时间"))
    override val optionalLabels = setOf("消费场景", "订单金额")
    override val headlineStatuses = setOf("支付成功", "极速支付成功", "自动扣款成功")
    override val pageMarkers = super.pageMarkers + setOf("订单详情")
    override fun parse(fields: VisibleDetailFields, observedAt: Long): DetailResult {
        if (!detail(fields)) return DetailResult(DetailRecognition.NOT_A_DETAIL_PAGE)
        val bill = "账单详情" in fields.markers
        val repayment = fields.fields["消费场景"] == "还款" && fields.hasStatus("自动扣款成功")
        if (fields.refundedSummary()) return low()
        if (bill && !repayment && !fields.hasStatus("支付成功", "极速支付成功")) return low()
        if (!bill && !fields.hasStatus("买单成功！") && "订单详情" !in fields.markers) return low()
        val paid = amount(fields, *if (bill) emptyArray() else arrayOf("实际付款", "实付金额")) ?: return low()
        if (bill && !repayment && !paid.startsWith("-")) return low()
        return evidence(fields, observedAt, VisibleProviderKeys.MEITUAN, paid,
            fields.value("付款时间", "消费时间", "下单时间"), fields.value("交易单号", "订单编号"),
            if (repayment) "MEITUAN:repayment" else if (bill) "MEITUAN:transaction" else "MEITUAN:order",
            TransactionSourceHint.MEITUAN, "支付方式", if (repayment) FinanceProposalKind.TRANSFER else FinanceProposalKind.EXPENSE)
    }
}

object PinduoduoDetailAdapter : SemanticDetailAdapter() {
    override val fingerprints = listOf(setOf("实付", "订单编号", "支付方式", "下单时间", "拼单时间"))
    override fun parse(fields: VisibleDetailFields, observedAt: Long): DetailResult {
        if (!detail(fields)) return DetailResult(DetailRecognition.NOT_A_DETAIL_PAGE)
        if (!fields.hasStatus("交易成功") || fields.refundedSummary()) return low()
        val ordered = fields.value("下单时间")?.let(DetailSemantics::time) ?: return low()
        val grouped = fields.value("拼单时间")?.let(DetailSemantics::time) ?: return low()
        if (ordered > grouped || grouped > observedAt + 300000L) return low()
        return evidence(fields, observedAt, VisibleProviderKeys.PINDUODUO, amount(fields, "实付"),
            fields.value("下单时间"), fields.value("订单编号"), "PINDUODUO:order", TransactionSourceHint.PINDUODUO, "支付方式")
    }
}

object UnionPayDetailAdapter : SemanticDetailAdapter() {
    override val fingerprints = listOf(setOf("当前状态", "订单金额", "付款方式", "订单时间", "订单编号", "商户订单号"),
        setOf("当前状态", "退款金额", "退款方式", "退款时间", "订单编号", "商户订单号"))
    override val signedHeadline = true
    override fun parse(fields: VisibleDetailFields, observedAt: Long): DetailResult {
        if (!detail(fields)) return DetailResult(DetailRecognition.NOT_A_DETAIL_PAGE)
        if ("云闪付交易详情" !in fields.markers || fields.refundedSummary()) return low()
        val refund = fields.fields["当前状态"] == "退款成功"
        if (!refund && fields.fields["当前状态"] != "交易成功") return low()
        val paid = amount(fields) ?: return low()
        if (refund) {
            if (!paid.startsWith("+") || DetailSemantics.minor(paid) != fields.value("退款金额")?.let(DetailSemantics::minor)) return low()
        } else if (!paid.startsWith("-")) return low()
        return evidence(fields, observedAt, VisibleProviderKeys.UNIONPAY, paid,
            fields.value(if (refund) "退款时间" else "订单时间"), fields.value("订单编号"),
            if (refund) "UNIONPAY:refund" else "UNIONPAY:transaction", null,
            if (refund) "退款方式" else "付款方式", if (refund) FinanceProposalKind.INCOME else FinanceProposalKind.EXPENSE,
            refund, fields.value("商户订单号"))
    }
}
