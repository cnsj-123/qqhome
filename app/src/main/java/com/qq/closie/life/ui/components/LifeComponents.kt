package com.qq.closie.life.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.qq.closie.life.ui.theme.LifeColors
import com.qq.closie.life.ui.theme.LifeShape
import com.qq.closie.life.ui.theme.LifeSpacing
import com.qq.closie.life.ui.theme.LifeType

/**
 * Canonical Life OS page: background, page padding and the bottom inset reserved for the
 * navigation bar, so every screen inherits the same rhythm instead of re-deriving it.
 *
 * Life OS pages are mostly whitespace by design — that is what makes a page read as an archive
 * rather than a dashboard.
 */
@Composable
fun LifePage(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(
        start = LifeSpacing.pageHorizontal,
        end = LifeSpacing.pageHorizontal,
        top = LifeSpacing.pageTop,
        bottom = LifeSpacing.pageBottom
    ),
    content: LazyListScope.() -> Unit
) {
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(LifeColors.Paper),
        contentPadding = contentPadding,
        content = content
    )
}

/**
 * Page headline. Serif, large, and given room above and below: on a compact phone a title that
 * touches the status bar reads as cramped no matter how good the type is.
 */
@Composable
fun LifeTopBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = LifeType.Display,
            color = LifeColors.TextPrimary
        )
        if (subtitle != null) {
            Spacer(Modifier.height(LifeSpacing.xxs))
            Text(
                text = subtitle,
                style = LifeType.Caption,
                color = LifeColors.TextSecondary
            )
        }
    }
}

/**
 * A titled group. Section headers are separated by whitespace, not by a card — hairlines and
 * spacing do the grouping, cards are reserved for content that has real physical meaning
 * (a receipt, a photo, a record).
 */
@Composable
fun LifeSection(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = LifeType.SectionTitle,
            color = LifeColors.TextSecondary
        )
        Spacer(Modifier.height(LifeSpacing.xs))
        content(this)
    }
}

/** 1dp separator. Faint — it confirms a grouping that whitespace already established. */
@Composable
fun LifeDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(
        modifier = modifier.fillMaxWidth(),
        thickness = 1.dp,
        color = LifeColors.Hairline
    )
}

/** Secondary information: timestamps, status, source. `mono` aligns numbers vertically. */
@Composable
fun LifeMetaText(
    text: String,
    modifier: Modifier = Modifier,
    mono: Boolean = false
) {
    Text(
        text = text,
        style = if (mono) LifeType.MonoNumber else LifeType.Caption,
        color = LifeColors.TextSecondary,
        modifier = modifier
    )
}

/**
 * Empty state.
 *
 * Never fakes data — an empty archive shows an empty archive, with a quiet line explaining what
 * will appear here. The optional [action] is a text button, not a filled CTA: nothing on a Life OS
 * page should shout.
 */
@Composable
fun LifeEmptyState(
    title: String,
    modifier: Modifier = Modifier,
    body: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = LifeSpacing.xl),
        horizontalAlignment = Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(LifeSpacing.xs)
    ) {
        Text(
            text = title,
            style = LifeType.PageTitle,
            color = LifeColors.TextTertiary
        )
        if (body != null) {
            Text(
                text = body,
                style = LifeType.BodySecondary,
                color = LifeColors.TextTertiary
            )
        }
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(LifeSpacing.xs))
            Box(
                modifier = Modifier
                    .height(LifeSpacing.minTouchTarget)
                    // background → clickable → padding: the ripple is drawn on the filled shape
                    // and the whole row stays tappable, padding included. Reversing the order
                    // would shrink the target down to the text.
                    .background(
                        color = LifeColors.SurfaceInset,
                        shape = RoundedCornerShape(LifeShape.small)
                    )
                    .clickable(onClick = onAction)
                    .padding(horizontal = LifeSpacing.md),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = actionLabel,
                    style = LifeType.Action,
                    color = LifeColors.Accent,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

/**
 * Media placeholder for timeline / record previews.
 *
 * v0.1 has no image pipeline wired to the Life UI, so this renders a neutral surface with an
 * optional caption instead of pretending to load a thumbnail. It reserves a fixed 4:3 block so
 * the timeline keeps its rhythm whether or not a record carries media.
 */
@Composable
fun LifeMediaPreview(
    modifier: Modifier = Modifier,
    label: String? = null,
    aspectRatio: Float = 4f / 3f
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(aspectRatio)
            .background(
                color = LifeColors.SurfaceInset,
                shape = RoundedCornerShape(LifeShape.medium)
            ),
        contentAlignment = Alignment.Center
    ) {
        if (label != null) {
            Text(
                text = label,
                style = LifeType.Caption,
                color = LifeColors.TextTertiary
            )
        }
    }
}
