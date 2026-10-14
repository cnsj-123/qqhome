package com.qq.closie.life.finance

import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Currency

/** Exact minor units at input/storage boundaries; no floating point money, including formatting. */
object FinanceMoney {
    fun fractionDigits(currencyCode: String): Int = Currency.getInstance(currencyCode).defaultFractionDigits.also {
        require(it in 0..3) { "暂不支持此币种的小数位" }
    }
    fun parse(text: String, currencyCode: String = "CNY", allowNegative: Boolean = false): Long {
        val raw = text.trim()
        require(Regex(if (allowNegative) "-?[0-9]+(\\.[0-9]+)?" else "[0-9]+(\\.[0-9]+)?").matches(raw)) { "请输入有效金额" }
        val value = BigDecimal(raw)
        val digits = fractionDigits(currencyCode)
        require(value.scale() <= digits) { "${currencyCode} 金额最多 $digits 位小数" }
        val minor = try { value.movePointRight(digits).longValueExact() }
            catch (_: ArithmeticException) { throw IllegalArgumentException("金额超出可支持范围") }
        require(allowNegative || minor > 0) { "金额必须大于 0" }
        return minor
    }
    fun input(minor: Long, currencyCode: String = "CNY"): String =
        BigDecimal.valueOf(minor, fractionDigits(currencyCode)).setScale(fractionDigits(currencyCode), RoundingMode.UNNECESSARY).toPlainString()
    fun display(minor: Long, currencyCode: String = "CNY"): String =
        "${if (currencyCode == "CNY") "¥" else currencyCode + " "}${input(minor, currencyCode)}"
}
