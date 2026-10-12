package com.qq.closie.life.finance

import org.junit.Assert.*
import org.junit.Test

class FinanceCategoryTreeTest {
    @Test fun rulePathsNormalizeAndResolveEveryLevelWithinItsParent() {
        val rows = listOf(FinanceCategoryEntity("r", "餐饮"), FinanceCategoryEntity("m", "三餐", "r"),
            FinanceCategoryEntity("l", "早餐", "m"), FinanceCategoryEntity("n", "热食", "l"))
        val tree = FinanceCategoryTree(rows)
        assertEquals("r", tree.resolvePath("餐饮")?.id)
        assertEquals("m", tree.resolvePath("餐饮/三餐")?.id)
        assertEquals("l", tree.resolvePath("餐饮/三餐/早餐")?.id)
        val proposal = FinanceProposalEntity("p", FinanceSource.WECHAT, "hash", "", 1, 1,
            FinanceProposalKind.EXPENSE, 100, "微信支出")
        val rule = FinanceRuleSet(FinanceRuleEntity("rule", "合成规则", 0, true, 1),
            listOf(FinanceRuleConditionEntity("rule", 0, FinanceConditionKind.SOURCE, FinanceSource.WECHAT.name)),
            listOf(FinanceRuleActionEntity("rule", 0, FinanceActionKind.SUGGEST_CATEGORY, " 餐饮 / 三餐 › 早餐 / 热食 ")))
        val suggested = FinanceRuleEngine.apply(proposal, listOf(rule)).proposal
        assertEquals("餐饮", suggested.category)
        assertEquals("三餐/早餐/热食", suggested.subcategory)
        assertEquals("n", tree.find(suggested.category, suggested.subcategory)?.id)
        assertEquals("餐饮 › 三餐 › 早餐 › 热食", FinanceCategoryTree.displayProposalPath(suggested.category, suggested.subcategory))
    }

    @Test fun missingAmbiguousBrokenOrCyclicPathsNeverGuessANode() {
        val rows = listOf(FinanceCategoryEntity("r", "餐饮"), FinanceCategoryEntity("m", "三餐", "r"),
            FinanceCategoryEntity("l", "早餐", "m"))
        assertNull(FinanceCategoryTree(rows).resolvePath("餐饮/不存在"))
        assertNull(FinanceCategoryTree(rows + FinanceCategoryEntity("r2", "餐饮")).resolvePath("餐饮/三餐/早餐"))
        assertNull(FinanceCategoryTree(rows + FinanceCategoryEntity("m2", "三餐", "r")).resolvePath("餐饮/三餐/早餐"))
        val damaged = FinanceCategoryTree(rows + listOf(FinanceCategoryEntity("a", "甲", "b"),
            FinanceCategoryEntity("b", "乙", "a"), FinanceCategoryEntity("z", "孤叶", "missing")))
        assertNull(damaged.resolvePath("甲/乙"))
        assertNull(damaged.resolvePath("孤叶"))
        assertFalse(damaged.hasValidAncestry("z"))
        assertFalse(damaged.hasValidAncestry("a"))
        assertNull(FinanceCategoryTree.splitPath("餐饮//早餐"))
        assertNull(FinanceCategoryTree.singleName("三餐/早餐"))
    }

    @Test fun deepPathsAndDescendantsRemainSafeForCyclesAndMissingParents() {
        val rows = listOf(FinanceCategoryEntity("r", "餐饮"), FinanceCategoryEntity("m", "三餐", "r"),
            FinanceCategoryEntity("l", "早餐", "m"))
        val tree = FinanceCategoryTree(rows)
        tree.validateParents()
        assertEquals("餐饮 › 三餐 › 早餐", tree.fullPath("l"))
        assertEquals(setOf("r", "m", "l"), tree.descendants("r"))
        assertEquals("r", tree.root("l")?.id)
        val damaged = FinanceCategoryTree(rows + listOf(FinanceCategoryEntity("a", "甲", "b"),
            FinanceCategoryEntity("b", "乙", "a"), FinanceCategoryEntity("z", "孤叶", "missing")))
        assertEquals(setOf("a", "b"), damaged.descendants("a"))
        assertNotNull(damaged.fullPath("a"))
        assertEquals("孤叶", damaged.fullPath("z"))
        assertTrue(runCatching { damaged.validateParents() }.isFailure)
    }
}
