package com.qq.closie.life.ui.components

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.qq.closie.life.ui.theme.LifeColors
import com.qq.closie.life.ui.theme.LifeShape
import com.qq.closie.life.ui.theme.LifeSpacing
import com.qq.closie.life.ui.theme.LifeType

/**
 * The standard row used across 我的 / 设置 / any structured list.
 *
 * Two rules that every row follows:
 *  1. `clickable` comes **before** `padding`, so the whole row — not just the label — is the touch
 *     target, and it is at least [LifeSpacing.minTouchTarget] tall.
 *  2. A disabled row passes `enabled = false` to `clickable`. That is what removes the ripple, the
 *     focus ring and the press animation. A no-op lambda would still ripple and read as "tapped,
 *     but broken" — the one thing a Coming soon row must never do.
 */
@Composable
fun LifeListRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    value: String? = null,
    enabled: Boolean = true,
    showChevron: Boolean = true,
    onClick: (() -> Unit)? = null
) {
    val clickable = onClick != null && enabled
    val titleColor = if (enabled) LifeColors.TextPrimary else LifeColors.TextDisabled
    val valueColor = if (enabled) LifeColors.TextSecondary else LifeColors.TextDisabled

    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = LifeSpacing.minTouchTarget)
            .clickable(enabled = clickable) { onClick?.invoke() }
            .padding(horizontal = LifeSpacing.cardPadding, vertical = LifeSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = title,
                style = LifeType.Body,
                color = titleColor
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = LifeType.Caption,
                    color = valueColor
                )
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(LifeSpacing.xs)
        ) {
            if (value != null) {
                Text(
                    text = value,
                    style = LifeType.Caption,
                    color = valueColor
                )
            }
            if (showChevron && clickable) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                    contentDescription = null,
                    tint = LifeColors.TextTertiary,
                    modifier = Modifier.size(LifeSpacing.iconSize)
                )
            }
        }
    }
}

/**
 * A 生活 module entry: 衣橱 / 财务 / 物品 / 旅行 / 园艺 / 阅读 / 计划.
 *
 * Deliberately *not* a coloured card. Seven coloured cards would turn the module directory into a
 * dashboard, which is exactly the look Life OS avoids. Instead: a title, a hairline, and a status
 * word. Only 衣橱 is live in v0.1; everything else is disabled and says 即将开放.
 */
@Composable
fun LifeModuleRow(
    title: String,
    status: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = LifeSpacing.minTouchTarget)
            .clickable(enabled = enabled && onClick != null) { onClick?.invoke() }
            .padding(vertical = LifeSpacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = title,
            style = LifeType.ModuleTitle,
            color = if (enabled) LifeColors.TextPrimary else LifeColors.TextDisabled
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(LifeSpacing.xs)
        ) {
            Text(
                text = status,
                style = LifeType.Caption,
                color = if (enabled) LifeColors.Accent else LifeColors.TextTertiary
            )
            if (enabled && onClick != null) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                    contentDescription = null,
                    tint = LifeColors.TextTertiary,
                    modifier = Modifier.size(LifeSpacing.iconSize)
                )
            }
        }
    }
}

/**
 * Primary action. Filled with [LifeColors.Accent] — a desaturated sage, not a saturated brand
 * colour — at [LifeSpacing.minTouchTarget] tall and a 12dp radius. No oversized floating shapes.
 */
@Composable
fun LifePrimaryAction(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = LifeSpacing.minTouchTarget)
            .background(
                color = if (enabled) LifeColors.Accent else LifeColors.SurfaceInset,
                shape = RoundedCornerShape(LifeShape.medium)
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = LifeSpacing.lg),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = LifeType.Action,
            color = if (enabled) LifeColors.OnAccent else LifeColors.TextDisabled,
            maxLines = 1
        )
    }
}

/**
 * Secondary action: hairline outline, accent text, no fill. Used for the quieter of two choices
 * (e.g. 稍后整理 next to 开始采集) so the page keeps one visual anchor instead of two.
 */
@Composable
fun LifeSecondaryAction(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = LifeSpacing.minTouchTarget)
            .background(
                color = LifeColors.SurfaceRaised,
                shape = RoundedCornerShape(LifeShape.medium)
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = LifeSpacing.lg),
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

/** Vertical whitespace block — the main structuring tool on a Life OS page. */
@Composable
fun LifeGap(height: Dp = LifeSpacing.sectionGap) {
    Spacer(Modifier.fillMaxWidth().height(height))
}

/** Indent helper so a row's leading icon and label line up with page content. */
@Composable
fun LifeRowIconSpacer() {
    Spacer(Modifier.width(LifeSpacing.sm))
}
