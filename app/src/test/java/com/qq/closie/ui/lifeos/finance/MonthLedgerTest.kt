package com.qq.closie.ui.lifeos.finance

import com.qq.closie.life.finance.*
import java.math.BigInteger
import org.junit.Assert.*
import org.junit.Test

class MonthLedgerTest {
    private fun row(id: String, currency: String, amount: Long, direction: FinanceDirection = FinanceDirection.OUTFLOW): FinanceLedgerRow {
        val account = FinanceAccountEntity(currency, currency, FinanceAccountKind.CASH, currency, 0, 0, 0)
        return FinanceLedgerRow(FinanceEntryEntity(id, account.id, direction, amount, id, 0, 0, 0), account, null, emptyList())
    }
    @Test fun `days keep currencies separate and transfers only contribute a presence marker`() {
        val ordinary = row("a", "CNY", 100)
        val transfer = row("t", "CNY", 900).copy(transfer = FinanceTransferEntity("t", "out", "in", 0, 0))
        val rows = listOf(ordinary, row("b", "JPY", 800), transfer, row("c", "CNY", 50, FinanceDirection.INFLOW))
        val cny = ledgerDays(rows, "CNY").values.single()
        assertEquals(BigInteger.valueOf(100), cny.spending)
        assertEquals(BigInteger.valueOf(50), cny.income)
        assertEquals(3, cny.count)
        assertEquals(BigInteger.valueOf(800), ledgerDays(rows, "JPY").values.single().spending)
    }
    @Test fun `day summary does not overflow when several valid entries are combined`() {
        val rows = listOf(row("a", "USD", Long.MAX_VALUE), row("b", "USD", Long.MAX_VALUE))
        assertEquals(BigInteger.valueOf(Long.MAX_VALUE).multiply(BigInteger.TWO), ledgerDays(rows, "USD").values.single().spending)
    }
    @Test fun `currency choices only offer precision supported by finance core`() {
        assertTrue(FinanceCurrencies.choices.any { it.first == "JPY" })
        assertTrue(FinanceCurrencies.choices.any { it.first == "KWD" })
        FinanceCurrencies.choices.forEach { assertTrue(FinanceMoney.fractionDigits(it.first) in 0..3) }
    }
}
