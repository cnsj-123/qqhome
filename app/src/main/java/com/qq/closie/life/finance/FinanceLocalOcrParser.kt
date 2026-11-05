package com.qq.closie.life.finance

data class FinanceOcrFields(
    val amountMinor: Long?, val occurredAt: Long?, val suggestedKind: FinanceProposalKind?,
    val role: FinanceFlowRole?, val issue: String, val currencyCode: String? = null,
    val amountCandidates: List<String> = emptyList()
) {
    // A decimal candidate can enter review even before its currency/precision is known.
    val amountText: String? get() = amountCandidates.singleOrNull()
        ?: amountMinor?.let { value -> currencyCode?.let { FinanceMoney.input(value, it) } }
    val hasTransactionFields: Boolean get() = amountCandidates.isNotEmpty() || amountText != null ||
        occurredAt != null || suggestedKind != null
}

/** Extract only bounded, structured local evidence. Merchant prose is never retained. */
object FinanceLocalOcrParser {
    private val supportedCodes = java.util.Currency.getAvailableCurrencies().map { it.currencyCode }
        .filter { runCatching { FinanceMoney.fractionDigits(it) }.isSuccess }.toSet()
    private val names = mapOf(
        "人民币" to "CNY", "人民幣" to "CNY", "RMB" to "CNY",
        "美元" to "USD", "美金" to "USD", "欧元" to "EUR", "歐元" to "EUR",
        "日元" to "JPY", "日圓" to "JPY", "港币" to "HKD", "港幣" to "HKD",
        "新台币" to "TWD", "新台幣" to "TWD", "台币" to "TWD", "英镑" to "GBP",
        "英鎊" to "GBP", "韩元" to "KRW", "韓元" to "KRW", "澳元" to "AUD",
        "加元" to "CAD", "新加坡元" to "SGD"
    )
    private val currency = "(?<![A-Z])(?:${(supportedCodes + names.keys).sortedByDescending(String::length).joinToString("|")})(?![A-Z])"
    private const val symbols = """[¥￥$€£₩₹฿₽₫₱₺₪]"""
    private const val number = """(?:[0-9]{1,12}|[0-9]{1,3}(?:,[0-9]{3}){1,3})(?:\.[0-9]{1,6})?"""
    private const val amountLabels = "实付|付款金额|交易金额|退款金额|收款金额|支付金额|返利金额|返现金额|到账金额|金额"
    private val labelAmount = Regex("""(?:$amountLabels)[:：\s]*(?:$currency)?\s*$symbols?\s*[-−+]?\s*($number)(?![0-9.,])""", RegexOption.IGNORE_CASE)
    private val prefixedAmount = Regex("""(?:$currency\s*$symbols?|$symbols)\s*[-−+]?\s*($number)(?![0-9.,])""", RegexOption.IGNORE_CASE)
    private val suffixedAmount = Regex("""(?<![0-9.,])($number)\s*(?:$currency|元(?!素|件))""", RegexOption.IGNORE_CASE)
    private val currencyToken = Regex(currency, RegexOption.IGNORE_CASE)
    private val fieldPattern = Regex("""^(?:币种|货币|currency)\s*[:：]\s*(.+)$""", RegexOption.IGNORE_CASE)
    private val monetaryContext = Regex("$amountLabels|支付成功|付款成功|退款成功|返利到账|返现到账")
    private val excludedLine = Regex("余额|订单号|交易单号|流水号|商户\\s*[:：]|店铺\\s*[:：]|收款方\\s*[:：]|付款方\\s*[:：]")
    private val isolatedMoney = Regex("""^(?:$currency\s*)?$symbols?\s*[-−+]?\s*$number\s*(?:$currency|元)?$""", RegexOption.IGNORE_CASE)

    private fun currencies(value: String): List<String> = currencyToken.findAll(value).map { match ->
        val token = match.value.uppercase(java.util.Locale.ROOT)
        names[token] ?: token
    }.toList()

    fun parse(raw: String): FinanceOcrFields {
        val text = raw.take(24_000)
        val lines = text.lineSequence().map(String::trim).take(200).toList()
        val fields = lines.mapNotNull { fieldPattern.matchEntire(it)?.groupValues?.get(1) }
        val amountLines = lines.filterNot(excludedLine::containsMatchIn)
            .filter { monetaryContext.containsMatchIn(it) || isolatedMoney.matches(it) }
        val amountSet = linkedSetOf<String>()
        val codeSet = linkedSetOf<String>()
        fields.flatMapTo(codeSet, ::currencies)
        lines.filter { currencyToken.matches(it) }.flatMapTo(codeSet, ::currencies)
        for (line in amountLines) {
            // Currency names on a labeled amount or an otherwise isolated money line are evidence.
            codeSet += currencies(line)
            val matches = sequenceOf(labelAmount, prefixedAmount, suffixedAmount)
                .flatMap { it.findAll(line) }.map { it.groupValues[1] }.distinct()
            for (value in matches) {
                val decimal = value.replace(",", "").toBigDecimalOrNull() ?: continue
                if (decimal.signum() > 0 && amountSet.size < 5) amountSet += decimal.stripTrailingZeros().toPlainString()
            }
            if (Regex("""$number\s*元(?!素|件)""").containsMatchIn(line) && currencies(line).isEmpty()) codeSet += "CNY"
        }
        val unknownField = fields.any { value ->
            val detected = currencies(value)
            detected.isEmpty() || value.split('/', '、', ',','+').any { it.isNotBlank() && currencies(it).isEmpty() }
        }
        val symbolConflict = codeSet.singleOrNull()?.let { code ->
            amountLines.any { line ->
                ('$' in line && code !in setOf("USD", "AUD", "CAD", "HKD", "NZD", "SGD", "TWD", "MXN")) ||
                ('€' in line && code != "EUR") || ('£' in line && code != "GBP") ||
                (('¥' in line || '￥' in line) && code !in setOf("CNY", "JPY"))
            }
        } ?: false
        val currencyCode = codeSet.singleOrNull()?.takeUnless { unknownField || symbolConflict }
        val amountText = amountSet.singleOrNull()
        val minor = currencyCode?.let { code -> amountText?.let { runCatching { FinanceMoney.parse(it, code) }.getOrNull() } }
        val dates = Regex("(?:20[0-9]{2})[-/年][0-9]{1,2}[-/月][0-9]{1,2}日?[ T\\s]+[0-9]{1,2}:[0-9]{2}")
            .findAll(text).mapNotNull { match ->
                val parts = Regex("[0-9]+").findAll(match.value).map { it.value.toInt() }.toList()
                val normalized = String.format(java.util.Locale.ROOT, "%04d-%02d-%02d %02d:%02d",
                    parts[0], parts[1], parts[2], parts[3], parts[4])
                runCatching { FinanceTime.parse(normalized) }.getOrNull()
            }.toSet()
        val adjustment = when {
            text.contains("退款成功") || text.contains("退款金额") -> FinanceFlowRole.REFUND
            text.contains("返利到账") || text.contains("返利金额") -> FinanceFlowRole.REBATE
            text.contains("返现到账") || text.contains("返现金额") -> FinanceFlowRole.CASHBACK
            else -> null
        }
        val outgoing = listOf("支付成功", "付款成功", "实付", "支出").any(text::contains)
        val incoming = listOf("收款成功", "收款金额", "收入").any(text::contains)
        val transfer = text.contains("还款成功") || text.contains("信用卡还款")
        val kind = when {
            adjustment != null -> FinanceProposalKind.INCOME.takeUnless { outgoing || transfer }
            transfer -> FinanceProposalKind.TRANSFER
            outgoing && !incoming -> FinanceProposalKind.EXPENSE
            incoming && !outgoing -> FinanceProposalKind.INCOME
            else -> null
        }
        val issue = listOfNotNull(
            if (currencyCode == null) "币种待核对，请选择实际币种的账户" else null,
            when {
                amountSet.size > 1 -> "发现多个金额（${amountSet.joinToString("、")}），请核对实际金额"
                amountSet.isEmpty() -> "金额待填写"
                currencyCode != null && minor == null -> "金额精度与币种不符，请核对"
                else -> null
            },
            if (dates.size != 1) "发生时间待核对" else null,
            if (kind == null) "收支方向待核对" else null
        ).joinToString("；").ifBlank { "本地识别结果待确认" }
        return FinanceOcrFields(minor, dates.singleOrNull(), kind, adjustment, issue, currencyCode, amountSet.toList())
    }
}
