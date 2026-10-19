package com.qq.closie.life.finance

import org.junit.Test
import org.junit.Assert.*

class FinanceLocalOcrParserTest {
    @Test fun balanceAndIdentifierAreNotTransactionAmounts() {
        val result=FinanceLocalOcrParser.parse("合成订单编号 1234567890123\n余额 ￥900.00\n支付成功\n实付：12.34\n2026年10月09日 17:20")
        assertEquals(1_234L,result.amountMinor);assertEquals(FinanceProposalKind.EXPENSE,result.suggestedKind)
        assertEquals(FinanceTime.parse("2026-10-09 17:20"),result.occurredAt)
    }
    @Test fun ambiguousFactsAndDateWithoutTimeRemainUnknown() {
        val result=FinanceLocalOcrParser.parse("￥10.00\n￥12.00\n2026-10-09\n支付成功\n收款成功")
        assertNull(result.amountMinor);assertNull(result.occurredAt);assertNull(result.suggestedKind)
        val invalid=FinanceLocalOcrParser.parse("支付成功\n￥10.00\n2026-02-30 12:00")
        assertNull(invalid.occurredAt)
    }
}
