package com.qq.closie.life.ui.theme

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JournalCoverageTest {
    private fun codepoint(value: Int) = String(Character.toChars(value))

    @Test fun supportsAsciiAndPreservesNewlinePolicy() {
        assertTrue(JournalCoverage.supports("Life OS 2026 !~"))
        assertTrue(JournalCoverage.supports(""))
        assertTrue(JournalCoverage.supports("\n\r"))
        assertFalse(JournalCoverage.supports("\t"))
    }

    @Test fun supportsRepresentativeCjkAndSupplementaryCodepoints() {
        assertTrue(JournalCoverage.supports("生活日记中文"))
        assertTrue(JournalCoverage.supports(codepoint(195028)))
    }

    @Test fun preservesRangeEndpointsAndGaps() {
        listOf(32, 126, 160, 383, 407, 195028).forEach { cp ->
            assertTrue("Expected covered U+${cp.toString(16)}", JournalCoverage.supports(codepoint(cp)))
        }
        listOf(31, 127, 159, 384, 406, 195027, 195029).forEach { cp ->
            assertFalse("Expected uncovered U+${cp.toString(16)}", JournalCoverage.supports(codepoint(cp)))
        }
    }

    @Test fun oneUnsupportedCodepointRejectsTheWholeRun() {
        assertFalse(JournalCoverage.supports("生活" + codepoint(0x10FFFF)))
        assertFalse(JournalCoverage.supports("Life OS " + codepoint(0x1F642)))
    }
}
