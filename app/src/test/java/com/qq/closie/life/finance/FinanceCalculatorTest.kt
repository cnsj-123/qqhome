package com.qq.closie.life.finance

import org.junit.Assert.*
import org.junit.Test

class FinanceCalculatorTest {
    @Test fun exactArithmeticAndPrecedence() {
        assertEquals(3_050L,FinanceCalculator.evaluate("18＋12.5"))
        assertEquals(600L,FinanceCalculator.evaluate("(1 + 2) × 2"))
        assertEquals(100L,FinanceCalculator.evaluate("1÷3×3"))
        assertEquals(7L,FinanceCalculator.evaluate("7","JPY"))
        assertEquals(1250L,FinanceCalculator.evaluate("1.25","KWD"))
    }
    @Test fun rejectsRoundingOverflowBadInputAndExecutableText() {
        for(text in listOf("1/3","1/0","1+","-1","0","(2+3","2;DELETE","1e8","0.001",
            "99999999999999999999999999")) assertTrue(text,runCatching { FinanceCalculator.evaluate(text) }.isFailure)
    }
}
