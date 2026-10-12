package com.qq.closie.life.finance

import org.junit.Assert.*
import org.junit.Test

class FinanceProposalPrivacyTest {
    @Test fun notificationScrubbingIsIdempotentAndKeepsIdentityAndConfirmedReferences() {
        val old = FinanceProposalEntity("p", FinanceSource.ALIPAY, "opaque-hash", "合成私密原文", 1, 2,
            FinanceProposalKind.INCOME, 100, "合成通知标题", status = FinanceProposalStatus.CONFIRMED,
            canonicalId = "confirmed-entry", revision = 7, role = FinanceFlowRole.REFUND)
        val safe = FinanceProposalPrivacy.sanitize(old)
        assertEquals("", safe.evidenceText)
        assertEquals("支付宝退款", safe.description)
        assertEquals(old.sourceKey, safe.sourceKey)
        assertEquals(old.canonicalId, safe.canonicalId)
        assertEquals(old.revision, safe.revision)
        assertEquals(safe, FinanceProposalPrivacy.sanitize(safe))
        assertEquals(safe, FinanceProposalPrivacy.sanitize(FinanceV4Snapshot(proposals = listOf(old))).proposals.single())
        val legacy = old.copy(source = FinanceSource.LEGACY_IMPORT)
        assertEquals(legacy, FinanceProposalPrivacy.sanitize(legacy))
    }
}
