package com.qq.closie.life.ui.reading

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qq.closie.life.reference.ReferenceItemEntity
import com.qq.closie.life.reference.ReferenceStatus
import com.qq.closie.life.reference.ReferenceType
import com.qq.closie.life.ui.components.LifeDivider
import com.qq.closie.life.ui.components.LifeEmptyState
import com.qq.closie.life.ui.components.LifeGap
import com.qq.closie.life.ui.components.LifePage
import com.qq.closie.life.ui.components.LifeReferenceMetaLine
import com.qq.closie.life.ui.components.LifeReferenceRow
import com.qq.closie.life.ui.components.LifeTopAppBar
import com.qq.closie.life.ui.components.LifeTopBarIconAction
import com.qq.closie.life.ui.reference.ReferenceViewModel
import com.qq.closie.life.ui.reference.typeLabel
import com.qq.closie.life.ui.theme.LifeColors
import com.qq.closie.life.ui.theme.LifeSpacing
import com.qq.closie.life.ui.theme.LifeTheme
import com.qq.closie.life.ui.theme.LifeType
import com.qq.closie.life.ui.theme.rememberLifeDimensions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.Text
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val ReadingStampFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("MM.dd", Locale.US)

/**
 * 阅读 — the first version.
 *
 * **This module owns no database of its own, on purpose.** A "reading" is an article: something the
 * user saved from a link, or marked as reading material. That is exactly what a
 * [ReferenceItemEntity] already is, so 阅读 is a *filtered view* of 资料库 rather than a second
 * store. Building a separate Reading schema in this release would have meant two places a saved
 * article could live, two tag systems, and an eventual merge migration — for a module whose real
 * requirements (progress tracking, ISBN, annual summaries) this release is explicitly not building.
 *
 * **Its own query, though.** The list comes from [ReferenceViewModel.readingItems], which reads
 * `observeActiveReading()` directly. An earlier version filtered the 资料库 list in Kotlin, which
 * made 阅读 inherit the library's live search text and status chip — so searching in 资料库 and then
 * opening 阅读 showed an empty page with no explanation. A view over the same data must not also be
 * a view over someone else's filters.
 *
 * What it does do, and does for real:
 *  - shows everything saved as 阅读 or 文章,
 *  - excludes archived items (archived means filed away),
 *  - lets the user add more through the same capture sheet 资料库 uses,
 *  - opens the same detail screen, where the extracted text reads as body copy.
 *
 * What it does not pretend to do: there is no progress bar, no bookmark position, no library shelf.
 */
@Composable
fun ReadingScreen(
    referenceViewModel: ReferenceViewModel,
    onOpenItem: (String) -> Unit,
    onAddReading: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val reading by referenceViewModel.readingItems.collectAsStateWithLifecycle()

    ReadingContent(
        items = reading,
        onOpenItem = onOpenItem,
        onAddReading = onAddReading,
        onBack = onBack,
        modifier = modifier
    )
}

@Composable
internal fun ReadingContent(
    items: List<ReferenceItemEntity>,
    onOpenItem: (String) -> Unit,
    onAddReading: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val dims = rememberLifeDimensions()

    LifePage(modifier = modifier) {
        item {
            LifeTopAppBar(
                title = "阅读",
                onBack = onBack,
                trailing = {
                    LifeTopBarIconAction(
                        icon = Icons.Outlined.Add,
                        contentDescription = "保存阅读内容",
                        onClick = onAddReading
                    )
                }
            )
        }

        item { LifeGap(dims.blockGap) }

        if (items.isEmpty()) {
            item {
                LifeEmptyState(
                    title = "还没有保存阅读内容",
                    body = "保存一个网页链接，或在资料库里把一条标记为阅读。",
                    actionLabel = "保存链接",
                    onAction = onAddReading
                )
            }
            return@LifePage
        }

        items.forEachIndexed { index, item ->
            if (index > 0) {
                item { LifeDivider() }
            }
            item {
                Column(modifier = Modifier.fillMaxWidth()) {
                    LifeReferenceRow(
                        title = item.title.ifBlank { "未命名" },
                        summary = item.summary,
                        media = null,
                        onClick = { onOpenItem(item.id) }
                    )
                    LifeReferenceMetaLine(
                        stamp = Instant.ofEpochMilli(item.createdAt)
                            .atZone(ZoneId.systemDefault())
                            .format(ReadingStampFormatter),
                        label = buildString {
                            append(typeLabel(item.referenceType))
                            item.sourceName?.takeIf { it.isNotBlank() }?.let { append(" · $it") }
                        }
                    )
                    Spacer(Modifier.height(LifeSpacing.xxs))
                }
            }
        }

        item { LifeGap(dims.sectionGap) }

        item {
            // Honest scope note. The user asked for a usable MVP, not a fake bookshelf, so the page
            // says what it is rather than showing disabled "阅读进度" rows.
            Text(
                text = "阅读进度、书籍与统计会在之后的版本里出现。",
                style = LifeType.BodySecondary,
                color = LifeColors.TextTertiary
            )
        }

        item { Spacer(Modifier.height(LifeSpacing.lg)) }
    }
}

// ------------------------------------------------------------------
//  Previews
// ------------------------------------------------------------------

private fun previewReading(
    id: String,
    title: String,
    type: ReferenceType,
    source: String?
): ReferenceItemEntity {
    val stamp = Instant.parse("2026-09-24T01:20:00Z").toEpochMilli()
    return ReferenceItemEntity(
        id = id,
        lifeEntityId = "le-$id",
        title = title,
        referenceType = type,
        status = ReferenceStatus.ORGANIZED,
        summary = "留一点时间读完它。",
        sourceName = source,
        createdAt = stamp,
        updatedAt = stamp
    )
}

@Preview(showBackground = true, widthDp = 393, heightDp = 852, name = "阅读 — 393x852")
@Preview(showBackground = true, widthDp = 360, heightDp = 800, name = "阅读 — 360x800")
@Preview(showBackground = true, widthDp = 411, heightDp = 891, name = "阅读 — 411x891")
@Composable
private fun ReadingPreview() {
    LifeTheme {
        ReadingContent(
            items = listOf(
                previewReading("a1", "为什么纸质的清单更容易被完成", ReferenceType.ARTICLE, "少数派"),
                previewReading("a2", "慢慢读完一本厚书的方法", ReferenceType.READING, "豆瓣")
            ),
            onOpenItem = {}, onAddReading = {}, onBack = {}
        )
    }
}

@Preview(showBackground = true, widthDp = 393, heightDp = 852, name = "阅读 — empty 393x852")
@Composable
private fun ReadingEmptyPreview() {
    LifeTheme {
        ReadingContent(items = emptyList(), onOpenItem = {}, onAddReading = {}, onBack = {})
    }
}
