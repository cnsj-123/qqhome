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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.qq.closie.life.ui.theme.LifeColors
import com.qq.closie.life.ui.theme.LifeShape
import com.qq.closie.life.ui.theme.LifeSpacing
import com.qq.closie.life.ui.theme.LifeType

/**
 * One row in the 资料库 list — or in any list of saved information.
 *
 * The layout rule that matters: **a row with no image does not reserve image space.** A text-only
 * reference (a pasted note, a clipped article) gets the full width for its title and summary, rather
 * than sitting next to an empty grey square. The [media] slot is therefore optional and the row
 * switches between two genuinely different layouts, not one layout with a blank.
 *
 * Deliberately restrained metadata: title, then a single line carrying source · type · time, then
 * one or two lines of summary. No tag chips, no author, no URL — those belong on the detail page.
 * A library list that shows everything about every row is a database browser, not an archive.
 */
@Composable
fun LifeReferenceRow(
    title: String,
    modifier: Modifier = Modifier,
    meta: String? = null,
    summary: String? = null,
    media: Any? = null,
    mediaSize: Dp = LifeSpacing.referenceThumb,
    onClick: (() -> Unit)? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = LifeSpacing.minTouchTarget)
            .clickable(enabled = onClick != null) { onClick?.invoke() }
            .padding(vertical = LifeSpacing.sm),
        verticalAlignment = Alignment.Top
    ) {
        if (media != null) {
            // Preview thumbnail: a soft-cornered square, not a card. Coil is already a dependency
            // (the closet uses it), so the same loader serves both — no second image pipeline.
            AsyncImage(
                model = media,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(mediaSize)
                    .background(
                        color = LifeColors.SurfaceInset,
                        shape = RoundedCornerShape(LifeShape.small)
                    )
            )
            Spacer(Modifier.width(LifeSpacing.sm))
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = LifeType.Body,
                color = LifeColors.TextPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (meta != null) {
                Spacer(Modifier.height(LifeSpacing.xxs))
                // Not LifeMetaText: this line mixes a Latin timestamp with Chinese type/source
                // words, and the mono face has no CJK glyphs. Callers pass pre-split strings or
                // use LifeReferenceMetaLine below when they need the typewriter stamp.
                Text(
                    text = meta,
                    style = LifeType.Caption,
                    color = LifeColors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (!summary.isNullOrBlank()) {
                Spacer(Modifier.height(LifeSpacing.xxs))
                Text(
                    text = summary,
                    style = LifeType.BodySecondary,
                    color = LifeColors.TextSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * The metadata line for a reference row, with the timestamp kept in the Latin-only typewriter face
 * and everything else in Noto Sans SC.
 *
 * Split into two Text runs on purpose — the same rule the home and timeline rows follow. Rendering
 * "09.24 · 教程 · 少数派" as one mono run would hand the Chinese characters to the system fallback
 * font, which is precisely the leak the bundled-font design exists to prevent.
 */
@Composable
fun LifeReferenceMetaLine(
    stamp: String?,
    label: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        if (!stamp.isNullOrBlank()) {
            Text(
                text = stamp,
                style = LifeType.Timestamp,
                color = LifeColors.TextSecondary
            )
            Text(
                text = " · $label",
                style = LifeType.Caption,
                color = LifeColors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        } else {
            Text(
                text = label,
                style = LifeType.Caption,
                color = LifeColors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
