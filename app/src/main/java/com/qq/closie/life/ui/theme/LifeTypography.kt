package com.qq.closie.life.ui.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Life OS typography.
 *
 * Two families only:
 *  - [FontFamily.Serif] for display / page / editorial titles. Android resolves this to the
 *    system serif (or falls back safely for CJK glyphs); no font is bundled or downloaded.
 *  - [FontFamily.Default] (system sans) for everything the user has to actually read. Chinese
 *    body copy is never decorated — readability wins over "premium" texture.
 *  - [FontFamily.Monospace] for numbers, dates and receipt-like metadata.
 *
 * Body copy sits at 16sp with 1.6+ line height. Nothing ships below 13sp: a 11–12sp caption with
 * [LifeColors.MistLight] is unreadable on a compact phone and is the fastest way to make the app
 * feel like a debug build.
 */
object LifeType {

    /** Hero editorial line — 旅行 / Plog / 图册 covers. */
    val Display: TextStyle = TextStyle(
        fontFamily = FontFamily.Serif,
        fontSize = 32.sp,
        lineHeight = 40.sp,
        fontWeight = FontWeight.Normal,
        letterSpacing = 0.sp
    )

    /** Page title and the home-screen date. */
    val PageTitle: TextStyle = TextStyle(
        fontFamily = FontFamily.Serif,
        fontSize = 26.sp,
        lineHeight = 34.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.sp
    )

    /** Section header above a group of rows. */
    val SectionTitle: TextStyle = TextStyle(
        fontFamily = FontFamily.Default,
        fontSize = 15.sp,
        lineHeight = 22.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.4.sp
    )

    /** Default reading size. */
    val Body: TextStyle = TextStyle(
        fontFamily = FontFamily.Default,
        fontSize = 16.sp,
        lineHeight = 26.sp,
        fontWeight = FontWeight.Normal,
        letterSpacing = 0.1.sp
    )

    /** Secondary copy: empty-state body, subtitles, long metadata. */
    val BodySecondary: TextStyle = TextStyle(
        fontFamily = FontFamily.Default,
        fontSize = 15.sp,
        lineHeight = 24.sp,
        fontWeight = FontWeight.Normal,
        letterSpacing = 0.1.sp
    )

    /** Smallest permitted size. Timestamps, status chips, footnotes. Never smaller. */
    val Caption: TextStyle = TextStyle(
        fontFamily = FontFamily.Default,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        fontWeight = FontWeight.Normal,
        letterSpacing = 0.2.sp
    )

    /** Dates, counts, amounts — anything that should line up vertically. */
    val MonoNumber: TextStyle = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 15.sp,
        lineHeight = 22.sp,
        fontWeight = FontWeight.Normal,
        letterSpacing = 0.sp
    )

    /** Row / button label. */
    val Action: TextStyle = TextStyle(
        fontFamily = FontFamily.Default,
        fontSize = 15.sp,
        lineHeight = 22.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.2.sp
    )
}
