package com.qq.closie.life.ui.theme

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LifeFontRulesTest {
    @Test fun brandDateAndMoneyHaveDistinctRoles() {
        assertThat(LifeType.Wordmark.fontFamily).isNotEqualTo(LifeType.DateHero.fontFamily)
        assertThat(LifeType.Money.fontFamily).isEqualTo(LifeFonts.Sans)
        assertThat(LifeType.Money.fontFeatureSettings).isEqualTo("tnum")
        assertThat(LifeType.MonoNumber.fontFeatureSettings).isEqualTo("tnum")
    }
    @Test fun unsupportedHandwritingKeepsMetricsAndUsesBundledFallback() {
        val text = "生活𰻞"
        val safe = LifeType.Body.withBundledFallback(text)
        assertThat(safe.fontFamily).isEqualTo(LifeFonts.Sans)
        assertThat(safe.fontSize).isEqualTo(LifeType.Body.fontSize)
        assertThat(safe.lineHeight).isEqualTo(LifeType.Body.lineHeight)
    }
    @Test fun applicationHierarchySharesTheHomeFamilies() {
        assertThat(LifeType.Body.fontFamily).isEqualTo(LifeFonts.JournalHand)
        assertThat(LifeType.Caption.fontFamily).isEqualTo(LifeFonts.JournalMeta)
        assertThat(LifeType.PageTitle.fontFamily).isEqualTo(LifeFonts.Journal)
        assertThat(LifeType.ModuleTitle.fontSize.value).isLessThan(LifeType.PageTitle.fontSize.value)
    }
}
