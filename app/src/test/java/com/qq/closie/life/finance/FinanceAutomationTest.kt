package com.qq.closie.life.finance

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FinanceAutomationTest : FinanceRoomTest() {
    private val automation get() = FinanceAutomationRepository(db, finance)
    private fun parse(key: String = "synthetic-key", body: String = "支付成功 ￥12.34") =
        FinanceNotificationParser.parse("com.tencent.mm", key, "微信支付", body, System.currentTimeMillis())!!

    @Test fun allowlistPaymentEvidenceAndAmountAmbiguityProduceOnlyTypedProposals() {
        assertNull(FinanceNotificationParser.parse("other.package", "key", "支付成功", "￥1.00", 0))
        assertNull(FinanceNotificationParser.parse("com.tencent.mm", "key", "聊天消息", "今晚￥12.34", 0))
        assertNull(FinanceNotificationParser.parse("com.tencent.mm", "key", "微信支付", "待支付 ￥1.00", 0))
        val one = parse()
        assertEquals(1_234L, one.amountMinor)
        assertEquals(FinanceProposalStatus.ACTIVE, one.status)
        assertNull(one.canonicalId)
        assertNull(parse(body = "支付成功 ￥12.34，账户余额￥56.78").amountMinor)
        val refund = FinanceNotificationParser.parse("com.eg.android.AlipayGphone", "refund-key",
            "退款到账", "退款成功 10.00元", System.currentTimeMillis())!!
        assertEquals(FinanceProposalKind.INCOME, refund.kind)
        assertEquals(FinanceFlowRole.REFUND, refund.role)
    }

    @Test fun sourceKeyDiscoveryIsIdempotentRejectAndSnoozeNeverCreateTruth() = runTest {
        account()
        val found = automation.discover(parse())!!
        assertNotNull(found.accountId)
        assertNull(automation.discover(parse()))
        assertEquals(1, db.financeIntakeDao().proposals().size)
        assertTrue(finance.snapshot().entries.isEmpty())
        automation.dismiss(found.id, found.revision, FinanceProposalStatus.SNOOZED)
        val snoozed = db.financeIntakeDao().proposal(found.id)!!
        assertEquals(FinanceProposalStatus.SNOOZED, snoozed.status)
        assertNull(snoozed.canonicalId)
        assertTrue(finance.snapshot().entries.isEmpty())
        automation.dismiss(snoozed.id, snoozed.revision, FinanceProposalStatus.REJECTED)
        assertEquals(FinanceProposalStatus.REJECTED, db.financeIntakeDao().proposal(found.id)!!.status)
        assertTrue(finance.snapshot().entries.isEmpty())
        assertTrue(finance.snapshot().v4!!.events.isEmpty())
    }

    @Test fun confirmationCreatesExactlyOneCanonicalResultAndOneRelationWithProposalAuthority() = runTest {
        val a = account()
        val found = automation.discover(parse())!!
        val input = FinanceProposalConfirmation(FinanceProposalKind.EXPENSE, a.id, null, 1_234, "合成确认",
            2_000, "餐饮", "", listOf("确认标签"), FinanceEventInput())
        val id = automation.confirm(found.id, found.revision, input)
        assertEquals(id, automation.confirm(found.id, found.revision, input.copy(amountMinor = 99_999)))
        val state = readFinanceSnapshot(db, true)
        assertEquals(1, state.entries.size)
        assertEquals(1_234L, state.entries.single().amountMinor)
        assertEquals(id, state.entries.single().id)
        assertEquals(1, state.v4!!.events.size)
        assertEquals(1, state.v4!!.activeLinks.size)
        assertEquals(FinanceProposalStatus.CONFIRMED, state.v4!!.proposals.single().status)
        assertEquals(id, state.v4!!.proposals.single().canonicalId)
        assertTrue(state.v4!!.changes.filter { it.operation in setOf("ENTRY_SAVE", "LINK_CREATE") }
            .all { it.authority == "USER_CONFIRMED_PROPOSAL" })
    }

    @Test fun snoozedProposalCanConfirmOnceAndFailedConfirmationKeepsProposalUnchanged() = runTest {
        val a = account()
        val proposal = automation.discover(parse())!!
        automation.dismiss(proposal.id, proposal.revision, FinanceProposalStatus.SNOOZED)
        val snoozed = db.financeIntakeDao().proposal(proposal.id)!!
        val input = FinanceProposalConfirmation(FinanceProposalKind.EXPENSE, a.id, null, 100, "合成确认",
            2_000, "", "", emptyList(), FinanceEventInput())
        assertTrue(runCatching { automation.confirm(snoozed.id, snoozed.revision, input.copy(accountId = "missing")) }.isFailure)
        assertEquals(snoozed, db.financeIntakeDao().proposal(snoozed.id))
        assertTrue(finance.snapshot().entries.isEmpty())
        val canonical = automation.confirm(snoozed.id, snoozed.revision, input)
        assertEquals(canonical, automation.confirm(snoozed.id, snoozed.revision, input))
        assertEquals(1, finance.snapshot().entries.size)
    }

    @Test fun rulesSortByPriorityThenIdFirstScalarWinsAndTagsMergeInStableOrder() {
        val proposal = parse().copy(accountId = "original")
        fun rule(id: String, priority: Int, category: String, tag: String) = FinanceRuleSet(
            FinanceRuleEntity(id, id, priority, true, 1_000),
            listOf(FinanceRuleConditionEntity(id, 0, FinanceConditionKind.TEXT_CONTAINS, "支付")),
            listOf(FinanceRuleActionEntity(id, 0, FinanceActionKind.SUGGEST_CATEGORY, category),
                FinanceRuleActionEntity(id, 1, FinanceActionKind.SUGGEST_ACCOUNT, id),
                FinanceRuleActionEntity(id, 2, FinanceActionKind.ADD_TAG, tag),
                FinanceRuleActionEntity(id, 3, FinanceActionKind.ADD_TAG, "共享标签"),
                FinanceRuleActionEntity(id, 4, FinanceActionKind.NORMALIZE_DESCRIPTION, "建议" + id)))
        val early = rule("a", 1, "服饰/衣服", "一")
        val tie = rule("b", 1, "餐饮", "二")
        val late = rule("z", 9, "旅行", "三")
        val result = FinanceRuleEngine.apply(proposal, listOf(late, tie, early))
        assertEquals(result, FinanceRuleEngine.apply(proposal, listOf(early, late, tie)))
        assertEquals("服饰", result.proposal.category)
        assertEquals("衣服", result.proposal.subcategory)
        assertEquals("a", result.proposal.accountId)
        assertEquals("建议a", result.proposal.description)
        assertEquals(listOf("一", "共享标签", "二", "三"), result.tags)
        assertEquals(listOf("a", "b", "z"), result.matched)
        assertEquals(proposal.evidenceText, result.proposal.evidenceText)
        assertEquals(proposal.role, result.proposal.role)
    }
}
