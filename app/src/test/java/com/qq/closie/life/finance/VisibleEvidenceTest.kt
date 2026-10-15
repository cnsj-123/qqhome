package com.qq.closie.life.finance

import com.qq.closie.life.finance.acquisition.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class VisibleEvidenceTest {
    private val observed = LocalDateTime.of(2026, 1, 2, 13, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    private val fields = VisibleDetailFields(mapOf("当前状态" to "已存入零钱", "转账说明" to "微信转账",
        "转账时间" to "2026年1月2日 12:00:00", "收款时间" to "2026年1月2日 12:00:05",
        "转账单号" to "123456789012345678901234567890"), setOf("+12.34"))

    @Test fun repeatedReferenceHasStableKeyAndNoRawPageText() {
        val first = WeChatDetailAdapter.parse(fields, observed).evidence!!.toProposal()
        val later = WeChatDetailAdapter.parse(fields, observed + 1000).evidence!!.toProposal()
        assertEquals(first.sourceKey, later.sourceKey)
        assertEquals(1234L, first.amountMinor)
        assertEquals("", first.evidenceText)
        assertFalse(first.sourceKey.contains(fields.fields.getValue("转账单号")))
    }

    @Test fun incompleteOrAmbiguousPagesFailClosed() {
        assertNull(WeChatDetailAdapter.parse(fields.copy(fields = fields.fields - "当前状态"), observed).evidence)
        assertNull(WeChatDetailAdapter.parse(fields.copy(amounts = setOf("+12.34", "+56.78")), observed).evidence)
        assertNull(WeChatDetailAdapter.parse(fields.copy(fields = fields.fields + ("收款时间" to "2026年2月30日 12:00:05")), observed).evidence)
        assertNull(VisibleTransactionEvidenceRouter.provider("unrelated.app", setOf(VisibleProviderKeys.WECHAT)))
    }

    @Test fun extractorVersionIsNotIdentityAndDisabledProviderCannotDispatch() {
        val evidence = WeChatDetailAdapter.parse(fields, observed).evidence!!
        assertEquals(evidence.toProposal().sourceKey, evidence.copy(extractionVersion = "next-extractor-v2").toProposal().sourceKey)
        assertNull(VisibleTransactionEvidenceRouter.provider("com.tencent.mm", emptySet()))
        val proposal = evidence.toProposal()
        val old = proposal.copy(source = FinanceSource.WECHAT,
            sourceKey = "visible:v1:WECHAT:wechat-received-transfer-v1:${evidence.referenceDigest}")
        assertEquals(FinanceEvidenceOrigin.strongReference(proposal), FinanceEvidenceOrigin.strongReference(old))
    }

    @Test fun strongConflictsRemainBlockedEvenWhenAmountAndTimeAreFarApart() {
        val first = WeChatDetailAdapter.parse(fields, observed).evidence!!.toProposal()
        val conflicting = first.copy(id = "conflict", amountMinor = 9999, occurredAt = first.occurredAt + 86_400_000)
            .let { it.copy(sourceKey = FinanceEvidenceOrigin.conflictKey(it)) }
        assertEquals(listOf(first), FinanceEvidenceOrigin.blockingPeers(conflicting, listOf(first, conflicting)))
        assertTrue(FinanceEvidenceOrigin.blockingPeers(conflicting, listOf(first.copy(status = FinanceProposalStatus.REJECTED))).isEmpty())
        assertTrue(FinanceEvidenceOrigin.blockingPeers(conflicting, listOf(first.copy(
            sourceKey = first.sourceKey.replace(":wechat:", ":another-provider:")))).isEmpty())
    }

    @Test fun weakMatchesStaySeparateAndRevisionInvalidatesAcknowledgement() {
        val first = WeChatDetailAdapter.parse(fields, observed).evidence!!.toProposal()
        val second = first.copy(id = "second", sourceKey = "notification:synthetic")
        val snapshot = FinanceSnapshot(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
        val before = FinanceDuplicateReview.candidates(first.id, first.kind, 1234, first.occurredAt, listOf(second), snapshot)
        val after = FinanceDuplicateReview.candidates(first.id, first.kind, 1234, first.occurredAt, listOf(second.copy(revision = 1)), snapshot)
        assertEquals(setOf("proposal:second:0"), before)
        assertFalse(before.containsAll(after))
        assertEquals(FinanceProposalStatus.ACTIVE, first.status)
        assertNull(first.canonicalId)
    }
}
