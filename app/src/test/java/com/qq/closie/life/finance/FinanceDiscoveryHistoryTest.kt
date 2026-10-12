package com.qq.closie.life.finance

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class FinanceDiscoveryHistoryTest {
    @Before fun before() = FinanceDiscoveryDiagnostics.clear()
    @After fun after() = FinanceDiscoveryDiagnostics.clear()

    @Test fun `later ordinary notifications do not erase payment results completing asynchronously`() {
        val payment = FinanceDiscoveryDiagnostics.received(FinanceSource.ALIPAY)
        val ordinary = FinanceDiscoveryDiagnostics.received(FinanceSource.ALIPAY)
        FinanceDiscoveryDiagnostics.result(FinanceSource.ALIPAY, ordinary, DiscoveryResult.NOT_PAYMENT)
        FinanceDiscoveryDiagnostics.result(FinanceSource.ALIPAY, payment, DiscoveryResult.CREATED, 3726, "synthetic-proposal")
        val history = FinanceDiscoveryDiagnostics.state.value.recent.getValue(FinanceSource.ALIPAY)
        assertEquals(listOf(ordinary, payment), history.map { it.sequence })
        assertEquals(3726L, history.last().amountMinor)
        assertEquals("synthetic-proposal", history.last().proposalId)
        assertEquals(DiscoveryResult.NOT_PAYMENT, FinanceDiscoveryDiagnostics.state.value.latest[FinanceSource.ALIPAY]?.result)
    }

    @Test fun `each source is bounded and clear rejects late callbacks`() {
        val old = FinanceDiscoveryDiagnostics.received(FinanceSource.WECHAT)
        repeat(20) { FinanceDiscoveryDiagnostics.received(FinanceSource.WECHAT) }
        val other = FinanceDiscoveryDiagnostics.received(FinanceSource.ALIPAY)
        FinanceDiscoveryDiagnostics.result(FinanceSource.WECHAT, old, DiscoveryResult.CREATED)
        assertEquals(FinanceDiscoveryDiagnostics.HISTORY_LIMIT, FinanceDiscoveryDiagnostics.state.value.recent[FinanceSource.WECHAT]?.size)
        assertEquals(1, FinanceDiscoveryDiagnostics.state.value.recent[FinanceSource.ALIPAY]?.size)
        FinanceDiscoveryDiagnostics.clear()
        FinanceDiscoveryDiagnostics.result(FinanceSource.ALIPAY, other, DiscoveryResult.CREATED)
        assertTrue(FinanceDiscoveryDiagnostics.state.value.recent.isEmpty())
        assertTrue(FinanceDiscoveryDiagnostics.state.value.latest.isEmpty())
    }
}
