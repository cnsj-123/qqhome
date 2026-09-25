package com.qq.closie.life.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.qq.closie.life.ui.theme.LifeColors
import com.qq.closie.life.ui.theme.LifeSpacing
import com.qq.closie.life.ui.theme.LifeType

/**
 * The one page header for every Life OS screen.
 *
 * v0.2 had [LifeTopBar], which only drew a title. That was fine while the shell had five top-level
 * pages and nothing else — but v0.3.0 adds 资料库, 计划, 阅读, the reference detail, the module
 * landing pages and the capture editors, and every one of them needs a way back. Rather than let
 * eight screens each hand-roll an arrow (which is how navigation affordances drift — one ends up at
 * 44dp, another at 48dp, one has no contentDescription), the back affordance lives here and only
 * here.
 *
 * **[onBack] is the whole contract**: pass null on a top-level page (首页 / 记录 / 生活 / 我的) and
 * no arrow is drawn; pass a lambda on any second-level page and a 48dp touch target appears with the
 * correct accessibility label. A page cannot accidentally show a back arrow to nowhere, and cannot
 * accidentally omit one — the type system decides it.
 *
 * Deliberately not Material's `TopAppBar`: that brings a 64dp container, a scroll behaviour, a
 * surface tint and a title slot whose typography we would then fight. This is a quiet editorial
 * header — serif title, optional sans subtitle, optional actions — that sits inside the page's own
 * padding and inherits the compact page rhythm rather than imposing its own.
 */
@Composable
fun LifeTopAppBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    backLabel: String = "返回",
    /**
     * Typeface for [title].
     *
     * Defaults to [LifeType.PageTitle] (serif), which is right for the fixed page names the shell
     * ships — 记录 / 资料库 / 计划 / 阅读. It is **wrong** for a title that came from the user or
     * from a network fetch (a 资料库 item's title, a captured page's title): serif is a *subset* face
     * carrying fixed UI strings only, so a title containing a character outside that subset would
     * silently fall back to the system font mid-string. Callers passing dynamic text must pass a
     * full-charset style ([LifeType.EditorialTitle] is the usual choice) so the whole title is drawn
     * from a bundled resource.
     */
    titleStyle: androidx.compose.ui.text.TextStyle = LifeType.PageTitle,
    trailing: @Composable (() -> Unit)? = null
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (onBack != null) {
                // The arrow sits in a fixed 48dp box so its touch target is correct regardless of
                // how small the glyph is, and so the title's left edge lines up between a page that
                // has a back button and one that does not.
                Box(
                    modifier = Modifier
                        .size(LifeSpacing.minTouchTarget)
                        .clickable(onClick = onBack)
                        .semantics { contentDescription = backLabel },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                        contentDescription = null,
                        tint = LifeColors.TextPrimary,
                        modifier = Modifier.size(LifeSpacing.iconSize)
                    )
                }
                Spacer(Modifier.width(LifeSpacing.xxs))
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = titleStyle,
                    color = LifeColors.TextPrimary,
                    // A long page title must truncate rather than push the trailing action off
                    // screen at 360dp — tested at the compact breakpoint in the previews.
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (subtitle != null) {
                    Spacer(Modifier.height(LifeSpacing.xxs))
                    Text(
                        text = subtitle,
                        style = LifeType.Caption,
                        color = LifeColors.TextSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            if (trailing != null) {
                Spacer(Modifier.width(LifeSpacing.xs))
                trailing()
            }
        }
    }
}

/**
 * A compact text action for a [LifeTopAppBar]'s trailing slot (完成 / 保存 / 编辑).
 *
 * Kept at [LifeSpacing.minTouchTarget] tall with horizontal padding so it is comfortable to hit,
 * and coloured with [LifeColors.Accent] only when enabled — a disabled action is grey rather than
 * grey-but-still-rippling.
 */
@Composable
fun LifeTopBarAction(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Box(
        modifier = modifier
            .heightIn(min = LifeSpacing.minTouchTarget)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = LifeSpacing.sm),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = LifeType.Action,
            color = if (enabled) LifeColors.Accent else LifeColors.TextDisabled,
            maxLines = 1
        )
    }
}

/**
 * A small square icon action for a [LifeTopAppBar]'s trailing slot.
 *
 * [contentDescription] is required rather than optional: an icon-only control with no label is
 * invisible to a screen reader, and every one of these is a real action, not decoration.
 */
@Composable
fun LifeTopBarIconAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(LifeSpacing.minTouchTarget)
            .clickable(onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = LifeColors.TextPrimary,
            modifier = Modifier.size(LifeSpacing.iconSize)
        )
    }
}

/** Convenience row of trailing actions with the standard gap. */
@Composable
fun LifeTopBarActions(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        content()
    }
}
