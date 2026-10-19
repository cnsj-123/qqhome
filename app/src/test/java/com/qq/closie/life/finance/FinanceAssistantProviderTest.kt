package com.qq.closie.life.finance

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class FinanceAssistantProviderTest {
    private class Fake(val action: () -> Unit = {}, val output: FinanceAssistanceResult = FinanceAssistanceResult.Explanation("合成解释")) : FinanceAssistantProvider {
        override val id = "offline-fake"
        override val transport = FinanceProviderTransport.LOCAL_FAKE
        override val capabilities = FinanceAssistantCapability.entries.toSet()
        var received = 0
        override suspend fun assist(request: FinanceAssistanceRequest): FinanceAssistanceResult { received++; action(); return output }
    }
    private fun grants() = listOf(FinanceReadPurpose.AI_READ, FinanceReadPurpose.CLOUD_SEND).map {
        FinancePurposeGrantEntity(it.name, it, "offline-fake", true, false, false, 10_000, 0, 0)
    }
    private val scope get() = grants().map { FinanceGrantEventEntity(it.id,"event") }
    private fun request() = FinanceAssistanceRequest("request", FinanceAssistantCapability.EXPLANATION, "合成问题",
        FinanceDisclosurePlan("offline-fake", setOf("event"), setOf("amount"), "AI_READ", "CLOUD_SEND"),
        listOf(DisclosedFinanceRow("event","CNY",200,null,null)), "revision",1,0)

    @Test fun T09_defaultOffAndSeparatePurposeScopeStopProviderBeforeData() = runTest {
        val fake = Fake()
        var policy = FinanceAssistancePolicy(false,0,"revision",grants(),scope)
        val coordinator = FinanceAssistantCoordinator({ policy }) { 1_000 }
        assertTrue(runCatching { coordinator.request(fake,request()) }.isFailure)
        policy = policy.copy(enabled = true, grants = grants().filter { it.purpose == FinanceReadPurpose.AI_READ })
        assertTrue(runCatching { coordinator.request(fake,request()) }.isFailure)
        policy = policy.copy(grants = grants(), scope = emptyList())
        assertTrue(runCatching { coordinator.request(fake,request()) }.isFailure)
        assertEquals(0,fake.received)
        policy = policy.copy(scope = scope)
        assertEquals(FinanceAssistanceResult.Explanation("合成解释"),coordinator.request(fake,request()))
        assertEquals(1,fake.received)
    }
    @Test fun T10_inFlightRevocationAndHostileResultCannotWriteOrExpandScope() = runTest {
        var policy = FinanceAssistancePolicy(true,0,"revision",grants(),scope)
        val coordinator = FinanceAssistantCoordinator({ policy }) { 1_000 }
        val revoke = Fake({ policy = policy.copy(enabled = false) })
        assertTrue(runCatching { coordinator.request(revoke,request()) }.isFailure)
        policy = policy.copy(enabled = true)
        val hostile = Fake(output = FinanceAssistanceResult.QuerySuggestion(FinanceQuerySpec(eventIds = setOf("ungranted"))))
        assertTrue(runCatching { coordinator.request(hostile,request()) }.isFailure)
        val text = Fake(output = FinanceAssistanceResult.ChangeSuggestion("DELETE FROM finance_entries"))
        // An untrusted suggestion remains inert text: there is no canonical owner in the interface.
        assertEquals(text.output,coordinator.request(text,request()))
    }
}
