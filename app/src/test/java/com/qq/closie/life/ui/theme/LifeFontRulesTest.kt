package com.qq.closie.life.ui.theme

import androidx.compose.ui.text.font.FontWeight
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The bundled-font containment rules, pinned as tests.
 *
 * Why this exists: the whole point of shipping four faces inside the APK is that NOTHING in the
 * Life OS UI is ever resolved by the platform font — because on a vivo device the platform font
 * is the user's theme font. Two ways that guarantee can rot without anyone noticing in code
 * review:
 *
 *  1. A Latin-only role (Special Elite / Caveat) gets handed Chinese text. The face has no CJK
 *     glyphs, so the system quietly supplies them.
 *  2. A Material component is left unstyled and falls back to `MaterialTheme.typography`, which
 *     resolves to FontFamily.Default unless the theme defines one.
 *
 * These tests fail loudly on both.
 */
class LifeFontRulesTest {

    /**
     * Every role that draws text which a user can read must be bound to a bundled family —
     * never null (which means "platform default" in Compose).
     */
    @Test
    fun everyTypographicRole_usesABundledFontFamily() {
        val roles = mapOf(
            "Brand" to LifeType.Brand,
            "Display" to LifeType.Display,
            "PageTitle" to LifeType.PageTitle,
            "EditorialTitle" to LifeType.EditorialTitle,
            "ModuleTitle" to LifeType.ModuleTitle,
            "SectionTitle" to LifeType.SectionTitle,
            "Body" to LifeType.Body,
            "BodySecondary" to LifeType.BodySecondary,
            "Caption" to LifeType.Caption,
            "Navigation" to LifeType.Navigation,
            "Action" to LifeType.Action,
            "MonoNumber" to LifeType.MonoNumber,
            "Timestamp" to LifeType.Timestamp,
            "HandNote" to LifeType.HandNote,
            "EmptyTitle" to LifeType.EmptyTitle
        )

        val bundled = setOf(LifeFonts.Serif, LifeFonts.Sans, LifeFonts.Hand, LifeFonts.Typewriter)

        roles.forEach { (name, style) ->
            assertThat(style.fontFamily).isNotNull()
            assertThat(bundled).contains(style.fontFamily)
        }
    }

    /** The Latin-only faces are exactly what the design says, with the weights we pinned. */
    @Test
    fun latinOnlyRoles_mapToTheLatinOnlyFaces() {
        assertThat(LifeType.MonoNumber.fontFamily).isEqualTo(LifeFonts.Typewriter)
        assertThat(LifeType.Timestamp.fontFamily).isEqualTo(LifeFonts.Typewriter)
        assertThat(LifeType.HandNote.fontFamily).isEqualTo(LifeFonts.Hand)
    }

    /** Chinese functional text is Sans; fixed Chinese headings are Serif. Nothing else. */
    @Test
    fun chineseRoles_mapToSansOrSerif() {
        listOf(
            LifeType.Body,
            LifeType.BodySecondary,
            LifeType.Caption,
            LifeType.Navigation,
            LifeType.Action,
            LifeType.EmptyTitle
        ).forEach { assertThat(it.fontFamily).isEqualTo(LifeFonts.Sans) }

        listOf(
            LifeType.Brand,
            LifeType.Display,
            LifeType.PageTitle,
            LifeType.EditorialTitle,
            LifeType.ModuleTitle,
            LifeType.SectionTitle
        ).forEach { assertThat(it.fontFamily).isEqualTo(LifeFonts.Serif) }
    }

    /**
     * Module rows are a peer list, so they must be lighter than a page title. This is the
     * v0.2.0-fix regression guard: they used to BE PageTitle (26sp) and seven 26sp rows read as
     * seven competing headlines.
     */
    @Test
    fun moduleTitle_isLighterThanPageTitle() {
        assertThat(LifeType.ModuleTitle.fontSize.value)
            .isLessThan(LifeType.PageTitle.fontSize.value)
        assertThat(LifeType.ModuleTitle.fontWeight).isEqualTo(FontWeight.Medium)
        assertThat(LifeType.ModuleTitle.fontSize.value).isWithin(1f).of(21f)
    }

    /**
     * The Material3 fallback is the second half of the containment: every role Material could
     * reach for must resolve to Serif or Sans, never to the platform default.
     */
    @Test
    fun materialTypographyFallback_neverFallsBackToThePlatformFont() {
        val t = LifeMaterialTypography
        val allowed = setOf(LifeFonts.Serif, LifeFonts.Sans)

        listOf(
            t.displayLarge, t.displayMedium, t.displaySmall,
            t.headlineLarge, t.headlineMedium, t.headlineSmall,
            t.titleLarge, t.titleMedium, t.titleSmall,
            t.bodyLarge, t.bodyMedium, t.bodySmall,
            t.labelLarge, t.labelMedium, t.labelSmall
        ).forEach { style ->
            assertThat(style.fontFamily).isNotNull()
            assertThat(allowed).contains(style.fontFamily)
        }
    }

    /** Titles take the serif voice, body/label take the sans voice — the design's split. */
    @Test
    fun materialTypographyFallback_splitsTitlesFromBody() {
        val t = LifeMaterialTypography
        assertThat(t.titleLarge.fontFamily).isEqualTo(LifeFonts.Serif)
        assertThat(t.headlineLarge.fontFamily).isEqualTo(LifeFonts.Serif)
        assertThat(t.bodyLarge.fontFamily).isEqualTo(LifeFonts.Sans)
        assertThat(t.labelLarge.fontFamily).isEqualTo(LifeFonts.Sans)
    }
}
