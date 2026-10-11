package com.qq.closie.ui.lifeos.finance

import com.qq.closie.life.finance.FinanceMoney
import java.util.Currency
import java.util.Locale

internal object FinanceCurrencies {
    val common = listOf("CNY", "HKD", "USD", "EUR", "JPY", "GBP", "SGD", "AUD", "CAD", "KRW")
    val choices: List<Pair<String, String>> by lazy {
        Currency.getAvailableCurrencies().filter { runCatching { FinanceMoney.fractionDigits(it.currencyCode) }.isSuccess }
            .map { it.currencyCode to "${it.currencyCode} · ${it.getDisplayName(Locale.SIMPLIFIED_CHINESE)}" }.sortedBy { it.first }
    }
}
