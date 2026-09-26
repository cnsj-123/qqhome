package com.qq.closie.life.ui.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Life OS typography — the design-spec mapping over [LifeFonts].
 *
 * Roles and their exact faces (design: index.html):
 *
 *  Brand          Noto Serif SC 600   28sp   — the "Life OS" wordmark / date hero
 *  Display        Noto Serif SC 600   30sp   — editorial hero lines
 *  PageTitle      Noto Serif SC 600   26sp   — 记录 / 生活 / 我的 page heads
 *  EditorialTitle Noto Serif SC 500   22sp   — cover / quote / long-form titles
 *  SectionTitle   Noto Serif SC 600   14sp   — 最近 / 接下来 group headers (design .sec-t)
 *  ModuleTitle    Noto Serif SC 500   21sp   — 衣橱 / 财务 / 物品 … module directory rows
 *  Body           Noto Sans SC 400    16sp   — user-visible record copy
 *  BodySecondary  Noto Sans SC 300    14sp   — secondary copy, empty-state body, motto
 *  Caption        Noto Sans SC 400    12sp   — metadata, status, the home date line
 *  Navigation     Noto Sans SC 500    11sp   — bottom bar labels only
 *  Action         Noto Sans SC 500    15sp   — rows and buttons
 *  MonoNumber     Special Elite 400   15sp   — aligned amounts and counts
 *  Timestamp      Special Elite 400   11sp   — 09.24 · 08:31-style stamps
 *  HandNote       Caveat 400          18sp   — English-only decoration; never Chinese
 *  EmptyTitle     Noto Sans SC 500    20sp   — empty-state headline (quieter than a page title)
 *
 * Chinese copy is never decorated: everything functional is [LifeFonts.Sans], titles are
 * [LifeFonts.Serif], and the hand face exists solely for the English annotations the design
 * marks as handwritten.
 *
 * Glyph coverage replaces a style-level fallback: Compose 1.7 has no per-glyph fontFamilyFallback
 * API on TextStyle, and none is needed here. Every non-Sans role renders only fixed UI strings
 * that its bundled resource fully covers — Serif's subset spans ASCII + GB2312 (every fixed
 * headline this app can draw), Typewriter spans Latin numerals, Hand is restricted to English
 * by rule. All user-entered text flows through [LifeFonts.Sans], a full-charset bundled face.
 * A system fallback is therefore never reached, which is exactly why vivo theme fonts cannot
 * leak back into Life OS: every rendered glyph comes from a bundled res/font resource.
 */

object LifeType {

    /** The app wordmark and the home date hero. */
    val Brand: TextStyle = TextStyle(
        fontFamily = LifeFonts.Serif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp,
        lineHeight = 36.sp,
        letterSpacing = 0.6.sp
    )

    /** Editorial hero line — cover / feature titles. */
    val Display: TextStyle = TextStyle(
        fontFamily = LifeFonts.Serif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 30.sp,
        lineHeight = 38.sp,
        letterSpacing = 0.6.sp
    )

    /** Page head for 记录 / 生活 / 我的. */
    val PageTitle: TextStyle = TextStyle(
        fontFamily = LifeFonts.Serif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 26.sp,
        lineHeight = 34.sp,
        letterSpacing = 0.5.sp
    )

    /** Cover / quote / long-form editorial titles. */
    val EditorialTitle: TextStyle = TextStyle(
        fontFamily = LifeFonts.Serif,
        fontWeight = FontWeight.Medium,
        fontSize = 22.sp,
        lineHeight = 30.sp,
        letterSpacing = 0.4.sp
    )

    /**
     * One row in the 生活 module directory — 衣橱 / 财务 / 物品 / 旅行 / 园艺 / 阅读 / 计划.
     *
     * Deliberately *not* [PageTitle] (26sp). A page has one page title; a directory has seven
     * peers, and at 26sp seven consecutive rows read as seven competing headlines. 21sp keeps the
     * serif editorial voice while staying a list item. The page's own 生活 heading still uses
     * [PageTitle], so the two levels remain clearly distinct.
     */
    val ModuleTitle: TextStyle = TextStyle(
        fontFamily = LifeFonts.Serif,
        fontWeight = FontWeight.Medium,
        fontSize = 21.sp,
        lineHeight = 29.sp,
        letterSpacing = 0.3.sp
    )

    /** Group header above rows — 最近 / 接下来 / 调试. The design marks these as serif 600
     * 14px (.sec-t), NOT the hand face and not a generic sans — the quiet spine of a page.
     */
    val SectionTitle: TextStyle = TextStyle(
        fontFamily = LifeFonts.Serif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.4.sp
    )

    /** Default reading size — record copy and list content. */
    val Body: TextStyle = TextStyle(
        fontFamily = LifeFonts.Sans,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 26.sp,
        letterSpacing = 0.1.sp
    )

    /** Secondary copy: empty-state body, the home motto, subtitles. */
    val BodySecondary: TextStyle = TextStyle(
        fontFamily = LifeFonts.Sans,
        fontWeight = FontWeight.Light,
        fontSize = 14.sp,
        lineHeight = 23.sp,
        letterSpacing = 0.1.sp
    )

    /** Smallest permitted sans size. Metadata, status chips, the home date line. */
    val Caption: TextStyle = TextStyle(
        fontFamily = LifeFonts.Sans,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 17.sp,
        letterSpacing = 0.3.sp
    )

    /** Bottom navigation labels. Never another family, never larger than 12sp. */
    val Navigation: TextStyle = TextStyle(
        fontFamily = LifeFonts.Sans,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp
    )

    /** Row / button label. */
    val Action: TextStyle = TextStyle(
        fontFamily = LifeFonts.Sans,
        fontWeight = FontWeight.Medium,
        fontSize = 15.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.2.sp
    )

    /** Aligned amounts and counts — ¥ 3,705.50. */
    val MonoNumber: TextStyle = TextStyle(
        fontFamily = LifeFonts.Typewriter,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.sp
    )

    /** Small stamps — 09.24 · 08:31, build numbers. */
    val Timestamp: TextStyle = TextStyle(
        fontFamily = LifeFonts.Typewriter,
        fontWeight = FontWeight.Normal,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.3.sp
    )

    /** English-only handwritten annotation. Never Chinese, never navigation. */
    val HandNote: TextStyle = TextStyle(
        fontFamily = LifeFonts.Hand,
        fontWeight = FontWeight.Normal,
        fontSize = 18.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.sp
    )

    /**
     * A one-line note the user typed — the *safe* sibling of [HandNote].
     *
     * [HandNote] is Caveat, a Latin-only face, and that is correct for the decorative English the
     * design marks as handwritten (`handwritten` labels, the home motto). It is wrong for anything
     * the user can type: Caveat has no CJK glyphs at all, so a note written in Chinese — which is
     * the overwhelmingly common case in this app — fell through to the *system* font, and with it
     * picked up whatever the device's theme font happens to be. That is precisely the vivo-font
     * leak [LifeFonts] exists to prevent, and it also made a Chinese note render in a different
     * face from an English one on the same screen.
     *
     * This style keeps the same size and rhythm so the note still reads as an aside rather than
     * body copy, but uses [LifeFonts.Sans] — the full-charset bundled face — so every glyph the
     * user can produce is drawn from a bundled resource and no system fallback is ever reached.
     * Losing the handwriting *look* for user text is the right trade: the note is content, and
     * content must be legible in the user's own language.
     */
    val UserNote: TextStyle = TextStyle(
        fontFamily = LifeFonts.Sans,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 25.sp,
        letterSpacing = 0.1.sp
    )

    /** Empty-state headline: present, but clearly quieter than a page title. */
    val EmptyTitle: TextStyle = TextStyle(
        fontFamily = LifeFonts.Sans,
        fontWeight = FontWeight.Medium,
        fontSize = 20.sp,
        lineHeight = 28.sp,
        letterSpacing = 0.2.sp
    )
}
