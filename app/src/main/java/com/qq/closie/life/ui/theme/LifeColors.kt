package com.qq.closie.life.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Life OS semantic colour tokens.
 *
 * The palette is deliberately narrow and low-saturation. Life OS should read as a quiet paper
 * archive — surfaces do the grouping, ink does the hierarchy, and colour is almost never the
 * signal. Every screen references these tokens; no screen hardcodes a hex value.
 *
 * Base surfaces:   Paper (app background) → ColdWhite (raised) → Fog (inset / grouped)
 * Base ink:        Ink (primary) → Mist (secondary) → MistLight (tertiary / placeholder)
 * Structure:       Line (hairlines only)
 * Accents:         Sage / Clay / Gold — sparingly, never as a module's "brand colour"
 */
object LifeColors {

    // ------------------------------------------------------------------
    //  Base
    // ------------------------------------------------------------------

    /** Primary text and important actions. Warm slate, never pure black. */
    val Ink: Color = Color(0xFF3D4A52)

    /** Secondary text: metadata, timestamps, captions, unselected icons. */
    val Mist: Color = Color(0xFF8296A0)

    /** Tertiary text: placeholders, empty-state body, disabled labels. */
    val MistLight: Color = Color(0xFFB9C4CA)

    /** Hairline separators. Faint on purpose — whitespace groups, lines only confirm. */
    val Line: Color = Color(0xFFDCE3E7)

    /** Grouped / inset surface, one step below the page. */
    val Fog: Color = Color(0xFFEDF1F4)

    /** App background. Warm off-white; pure white would read as "document", not "paper". */
    val Paper: Color = Color(0xFFF7F6F2)

    /** Raised surface: cards, sheets, bottom bar. */
    val ColdWhite: Color = Color(0xFFFCFCFA)

    // ------------------------------------------------------------------
    //  Accents — small doses only
    // ------------------------------------------------------------------

    /** Primary accent: selected state, primary action, live module marker. */
    val Sage: Color = Color(0xFF7D8C80)

    /** Secondary accent: attention / failure without shouting. */
    val Clay: Color = Color(0xFFA98B76)

    /** Tertiary accent: dated or archived emphasis, editorial detail. */
    val Gold: Color = Color(0xFFB39B72)

    // ------------------------------------------------------------------
    //  Semantic aliases
    //
    //  Screens read these, not the pigments above. If the palette is ever retuned only this
    //  block changes.
    // ------------------------------------------------------------------

    val TextPrimary: Color = Ink
    val TextSecondary: Color = Mist
    val TextTertiary: Color = MistLight
    val TextDisabled: Color = MistLight

    val Surface: Color = Paper
    val SurfaceRaised: Color = ColdWhite
    val SurfaceInset: Color = Fog
    val Hairline: Color = Line

    val Accent: Color = Sage
    val AccentMuted: Color = Fog
    val OnAccent: Color = ColdWhite

    /** Failure / needs-attention. Warm clay, kept low-saturation so it stays calm. */
    val Alert: Color = Clay
}
