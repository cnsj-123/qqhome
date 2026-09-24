package com.qq.closie.life.ui.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Life OS spacing scale.
 *
 * One scale for every screen. Pages are 20–24dp from the bezel, sections 28–32dp apart, and
 * content is never allowed to fill the viewport edge-to-edge: at least ~40% of a Life OS page is
 * breathing room, which is what separates "quiet archive" from "dashboard".
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

    // Semantic
    /** Page content inset from the screen edge. Compact-width safe. */
    val pageHorizontal: Dp = 20.dp

    /** Top inset — generous, so the first line never crowds the status bar. */
    val pageTop: Dp = 32.dp

    /** Bottom inset reserved for the navigation bar. */
    val pageBottom: Dp = 96.dp

    /** Vertical rhythm between sections. */
    val sectionGap: Dp = 28.dp

    /** Between sibling rows / items inside one section. */
    val itemGap: Dp = 12.dp

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
