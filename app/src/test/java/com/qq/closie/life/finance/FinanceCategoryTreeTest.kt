package com.qq.closie.life.finance

import org.junit.Assert.*
import org.junit.Test

class FinanceCategoryTreeTest {
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
