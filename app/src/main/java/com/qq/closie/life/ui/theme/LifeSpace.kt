package com.qq.closie.life.ui.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Life OS spacing scale — the fixed micro-scale.
 *
 * One scale for every screen. Content is never allowed to fill the viewport edge-to-edge:
 * at least ~40% of a Life OS page is breathing room, which is what separates "quiet archive"
 * from "dashboard".
 *
 * Page-level rhythm (page horizontal/top/bottom, section and block gaps) is width-aware and
 * lives in [LifeDimensions] via `rememberLifeDimensions()` — do not reintroduce fixed page
 * paddings here, they would fight the compact breakpoint.
 */
object LifeSpacing {

    // Raw scale
    val xxs: Dp = 4.dp
    val xs: Dp = 8.dp
    val sm: Dp = 12.dp
    val md: Dp = 16.dp
    val lg: Dp = 20.dp
    val xl: Dp = 24.dp
    val xxl: Dp = 32.dp
    val xxxl: Dp = 40.dp
    val huge: Dp = 48.dp

    /** Vertical rhythm between sections (fallback for non-page contexts). */
    val sectionGap: Dp = 28.dp

    /** Between a title and the copy directly under it. */
    val titleGap: Dp = 8.dp

    /** Inner padding of a card or grouped surface. */
    val cardPadding: Dp = 16.dp

    /** Inner padding of a sheet. */
    val sheetPadding: Dp = 20.dp

    /** Minimum interactive size. Rows must meet this or they feel unresponsive. */
    val minTouchTarget: Dp = 48.dp

    /** Icon size used inside rows. */
    val iconSize: Dp = 20.dp

    // ------------------------------------------------------------------
    //  v0.3.0 additions
    // ------------------------------------------------------------------

    /**
     * Thumbnail edge in a 资料库 row.
     *
     * 72dp sits inside the 64–88dp band the design allows: big enough to recognise a screenshot at
     * a glance, small enough that a list of them still reads as a list rather than a photo grid.
     */
    val referenceThumb: Dp = 72.dp

    /** Thumbnail edge in the home page's 最近 block — smaller, because the home page is an entry. */
    val homeThumb: Dp = 48.dp

    /** Inner padding of the search field. */
    val searchPadding: Dp = 12.dp

    /** Vertical padding of a filter chip. */
    val chipPadding: Dp = 8.dp

    /**
     * Visual diameter of a plan's completion circle.
     *
     * 20dp against a 48dp touch target: the circle reads as a quiet bullet point rather than a
     * form control, while the tappable area is still comfortably thumb-sized.
     */
    val planCheck: Dp = 20.dp

    /** The tick inside the completion circle. */
    val planCheckIcon: Dp = 13.dp

    /**
     * Nudge that puts a plan's first text line on the circle's optical centre.
     *
     * The circle is vertically centred in its 48dp box while the title sits at the top of a short
     * column, so without this the two baselines disagree by a few dp and the row looks misaligned.
     */
    val planRowTextInset: Dp = 10.dp
}

/**
 * Corner radii: 10 / 12 / 16.
 *
 * Deliberately small. Large "friendly" radii read as a consumer dashboard; Life OS stays closer
 * to a printed page. [sheet] is the one place a larger radius earns its keep.
 */
object LifeShape {
    val small: Dp = 10.dp
    val medium: Dp = 12.dp
    val large: Dp = 16.dp
    val sheet: Dp = 16.dp
}
