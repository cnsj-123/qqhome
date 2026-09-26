package com.qq.closie.life.ui.reference

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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qq.closie.life.reference.ReferenceItemEntity
import com.qq.closie.life.reference.ReferenceStatus
import com.qq.closie.life.reference.ReferenceType
import com.qq.closie.life.ui.components.LifeDivider
import com.qq.closie.life.ui.components.LifeEmptyState
import com.qq.closie.life.ui.components.LifeGap
import com.qq.closie.life.ui.components.LifeMetaText
import com.qq.closie.life.ui.components.LifePage
import com.qq.closie.life.ui.components.LifeReferenceMetaLine
import com.qq.closie.life.ui.components.LifeReferenceRow
import com.qq.closie.life.ui.components.LifeSearchField
import com.qq.closie.life.ui.components.LifeTopAppBar
import com.qq.closie.life.ui.components.LifeTopBarIconAction
import com.qq.closie.life.ui.theme.LifeColors
import com.qq.closie.life.ui.theme.LifeShape
import com.qq.closie.life.ui.theme.LifeSpacing
import com.qq.closie.life.ui.theme.LifeTheme
import com.qq.closie.life.ui.theme.LifeType
import com.qq.closie.life.ui.theme.rememberLifeDimensions
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** 09.24 — the Latin-only stamp that sits in the typewriter face. */
private val ReferenceStampFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("MM.dd", Locale.US)

/**
 * 资料库 — the curated archive.
 *
 * The screen answers one question: "what did I save, and what still needs filing?" So the order is
 * title → search → filter chips → list, and the default filter is 全部 rather than 待整理. Opening a
 * library onto a filtered view would hide most of the shelf and make the app look emptier than it is.
 *
 * Visual rules it inherits and must not break: no coloured collection cards, no grid of covers, no
 * per-item badges. Rows are separated by hairlines and whitespace; the only colour on the page is
 * the accent used for the active filter and the search cursor.
 */
@Composable
fun ReferenceLibraryScreen(
    viewModel: ReferenceViewModel,
    onOpenItem: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * Opens the global capture sheet — the same one the ＋ tab opens.
     *
     * 资料库 without an add affordance was a one-way street: the only ways in were the bottom bar's
     * ＋ (which is hidden on this screen, because 资料库 is not a tab) or 记录 → 存进资料库. So the
     * page you reach when you decide to save something offered no way to save it. Reusing the one
     * sheet keeps a single capture entry point rather than growing a second, library-specific one.
     */
    onAddItem: () -> Unit = {}
) {
    val items by viewModel.items.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val filter by viewModel.filter.collectAsStateWithLifecycle()
    val inboxCount by viewModel.inboxCount.collectAsStateWithLifecycle()
    val thumbnails by viewModel.thumbnails.collectAsStateWithLifecycle()

    ReferenceLibraryContent(
        items = items,
        query = query,
        filter = filter,
        inboxCount = inboxCount,
        thumbnails = thumbnails,
        onQueryChange = viewModel::onQueryChange,
        onFilterChange = viewModel::onFilterChange,
        onOpenItem = onOpenItem,
        onAddItem = onAddItem,
        onBack = onBack,
        modifier = modifier
    )
}

@Composable
internal fun ReferenceLibraryContent(
    items: List<ReferenceItemEntity>,
    query: String,
    filter: ReferenceFilter,
    inboxCount: Int,
    onQueryChange: (String) -> Unit,
    onFilterChange: (ReferenceFilter) -> Unit,
    onOpenItem: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    /** Managed image path per `lifeEntityId`; absent means the row renders with no image. */
    thumbnails: Map<String, String> = emptyMap(),
    onAddItem: () -> Unit = {}
) {
    val dims = rememberLifeDimensions()

    LifePage(modifier = modifier) {
        item {
            LifeTopAppBar(
                title = "资料库",
                onBack = onBack,
                trailing = {
                    LifeTopBarIconAction(
                        icon = Icons.Outlined.Add,
                        contentDescription = "添加资料",
                        onClick = onAddItem
                    )
                }
            )
        }

        item { LifeGap(dims.blockGap) }

        item {
            LifeSearchField(
                value = query,
                onValueChange = onQueryChange,
                placeholder = "搜索标题、内容、来源",
                contentDescription = "搜索资料"
            )
        }

        item { Spacer(Modifier.height(LifeSpacing.sm)) }

        item {
            ReferenceFilterRow(
                selected = filter,
                inboxCount = inboxCount,
                onSelect = onFilterChange
            )
        }

        item { Spacer(Modifier.height(LifeSpacing.md)) }

        if (items.isEmpty()) {
            item {
                // Two different empties, and the distinction matters: "you have saved nothing yet"
                // is an invitation, while "your search found nothing" is a dead end that needs a
                // way back. Showing the invitation for a failed search would read as data loss.
                if (query.isNotBlank()) {
                    LifeEmptyState(
                        title = "没有找到相关内容",
                        body = "换个词试试，或者清空搜索看全部。"
                    )
                } else {
                    LifeEmptyState(
                        title = emptyTitleFor(filter),
                        body = emptyBodyFor(filter)
                    )
                }
            }
        } else {
            items.forEachIndexed { index, item ->
                if (index > 0) {
                    item { LifeDivider() }
                }
                item {
                    ReferenceRow(
                        item = item,
                        thumbnail = thumbnails[item.lifeEntityId],
                        onClick = { onOpenItem(item.id) }
                    )
                }
            }
            // A small tail so the last row is never flush against the bottom bar.
            item { Spacer(Modifier.height(LifeSpacing.lg)) }
        }
    }
}

/** One row: title, then 时间 · 来源 · 类型, then a summary if there is one. */
@Composable
private fun ReferenceRow(
    item: ReferenceItemEntity,
    thumbnail: String?,
    onClick: () -> Unit
) {
    LifeReferenceRow(
        title = item.title.ifBlank { "未命名" },
        // A managed file path, so Coil loads the copy Life OS owns. Null for a text-only reference,
        // in which case the row gives the whole width to the text rather than an empty square.
        media = thumbnail,
        summary = item.summary,
        onClick = onClick
    )
    // The metadata line is rendered separately rather than inside LifeReferenceRow so the timestamp
    // can keep the typewriter face while the Chinese label stays in Noto Sans SC.
    ReferenceRowMeta(item)
}

@Composable
private fun ReferenceRowMeta(item: ReferenceItemEntity) {
    Column {
        LifeReferenceMetaLine(
            stamp = Instant.ofEpochMilli(item.createdAt)
                .atZone(ZoneId.systemDefault())
                .format(ReferenceStampFormatter),
            label = buildString {
                append(typeLabel(item.referenceType))
                item.sourceName?.takeIf { it.isNotBlank() }?.let { append(" · $it") }
                val status = statusLabel(item.status)
                if (status.isNotEmpty()) append(" · $status")
            }
        )
        Spacer(Modifier.height(LifeSpacing.xxs))
    }
}

/** Lightweight status filter. Text, a hairline and one accent — never a coloured pill row. */
@Composable
private fun ReferenceFilterRow(
    selected: ReferenceFilter,
    inboxCount: Int,
    onSelect: (ReferenceFilter) -> Unit
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(LifeSpacing.sm)
    ) {
        items(ReferenceFilter.entries.size) { index ->
            val option = ReferenceFilter.entries[index]
            // Only 待整理 carries a number: it is the one count that means "there is work waiting".
            // Numbering 全部 / 已整理 / 已归档 too would turn the bar into a stats strip.
            val label = if (option == ReferenceFilter.INBOX && inboxCount > 0) {
                "${option.label} $inboxCount"
            } else {
                option.label
            }
            FilterChip(
                label = label,
                selected = option == selected,
                onClick = { onSelect(option) }
            )
        }
    }
}

@Composable
private fun FilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .heightIn(min = LifeSpacing.minTouchTarget)
            .clickable(onClick = onClick)
            .padding(vertical = LifeSpacing.xs),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = label,
                style = LifeType.Action,
                color = if (selected) LifeColors.Accent else LifeColors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(LifeSpacing.xxs))
            // The selected state is a short underline, not a filled pill — it reads as a printed tab.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .background(
                        color = if (selected) LifeColors.Accent else Color.Transparent,
                        shape = RoundedCornerShape(999.dp)
                    )
            )
        }
    }
}

// ------------------------------------------------------------------
//  Labels
// ------------------------------------------------------------------

internal fun typeLabel(type: ReferenceType): String = when (type) {
    ReferenceType.ARTICLE -> "文章"
    ReferenceType.TUTORIAL -> "教程"
    ReferenceType.GUIDE -> "指南"
    ReferenceType.NOTE -> "笔记"
    ReferenceType.REFERENCE -> "资料"
    ReferenceType.READING -> "阅读"
    ReferenceType.PRODUCT_INFO -> "商品"
    ReferenceType.OTHER -> "其他"
}

internal fun statusLabel(status: ReferenceStatus): String = when (status) {
    ReferenceStatus.INBOX -> "待整理"
    ReferenceStatus.ORGANIZED -> "已整理"
    ReferenceStatus.ARCHIVED -> "已归档"
}

private fun emptyTitleFor(filter: ReferenceFilter): String = when (filter) {
    ReferenceFilter.ALL -> "还没有资料"
    ReferenceFilter.INBOX -> "没有待整理的内容"
    ReferenceFilter.ORGANIZED -> "还没有整理过的资料"
    ReferenceFilter.ARCHIVED -> "还没有归档的资料"
}

private fun emptyBodyFor(filter: ReferenceFilter): String = when (filter) {
    ReferenceFilter.ALL -> "把截图、网页或一段文字收进来，之后可以在这里找到它们。"
    ReferenceFilter.INBOX -> "收进来的内容都已经整理好了。"
    ReferenceFilter.ORGANIZED -> "整理过的资料会出现在这里。"
    ReferenceFilter.ARCHIVED -> "归档的资料会留在这里，随时可以取回。"
}

// ------------------------------------------------------------------
//  Previews — sample data local to this file, never reachable from production code
// ------------------------------------------------------------------

private fun previewReference(
    id: String,
    title: String,
    type: ReferenceType,
    status: ReferenceStatus,
    source: String?,
    summary: String?
): ReferenceItemEntity {
    val stamp = Instant.parse("2026-09-24T01:20:00Z").toEpochMilli()
    return ReferenceItemEntity(
        id = id,
        lifeEntityId = "le-$id",
        title = title,
        referenceType = type,
        status = status,
        summary = summary,
        sourceName = source,
        createdAt = stamp,
        updatedAt = stamp
    )
}

private fun previewReferences(): List<ReferenceItemEntity> = listOf(
    previewReference(
        "r1", "用 Room 做显式迁移的正确姿势", ReferenceType.TUTORIAL,
        ReferenceStatus.INBOX, "少数派", "把 schema 导出提交，然后手写 Migration。"
    ),
    previewReference(
        "r2", "如何挑选日常使用的帆布鞋", ReferenceType.ARTICLE,
        ReferenceStatus.ORGANIZED, "知乎", "从楦型、鞋底到保养的完整建议。"
    ),
    previewReference(
        "r3", "关于整理截图的一个想法", ReferenceType.NOTE,
        ReferenceStatus.INBOX, null, null
    )
)

@Preview(showBackground = true, widthDp = 360, heightDp = 800, name = "资料库 — 360x800")
@Preview(showBackground = true, widthDp = 393, heightDp = 852, name = "资料库 — 393x852")
@Preview(showBackground = true, widthDp = 411, heightDp = 891, name = "资料库 — 411x891")
@Composable
private fun ReferenceLibraryPreview() {
    LifeTheme {
        ReferenceLibraryContent(
            items = previewReferences(),
            query = "",
            filter = ReferenceFilter.ALL,
            inboxCount = 2,
            onQueryChange = {},
            onFilterChange = {},
            onOpenItem = {},
            onBack = {}
        )
    }
}

@Preview(showBackground = true, widthDp = 393, heightDp = 852, name = "资料库 — empty 393x852")
@Composable
private fun ReferenceLibraryEmptyPreview() {
    LifeTheme {
        ReferenceLibraryContent(
            items = emptyList(),
            query = "",
            filter = ReferenceFilter.ALL,
            inboxCount = 0,
            onQueryChange = {},
            onFilterChange = {},
            onOpenItem = {},
            onBack = {}
        )
    }
}

@Preview(showBackground = true, widthDp = 393, heightDp = 852, name = "资料库 — 搜索无结果 393x852")
@Composable
private fun ReferenceLibraryNoResultPreview() {
    LifeTheme {
        ReferenceLibraryContent(
            items = emptyList(),
            query = "不存在的关键词",
            filter = ReferenceFilter.ALL,
            inboxCount = 0,
            onQueryChange = {},
            onFilterChange = {},
            onOpenItem = {},
            onBack = {}
        )
    }
}
