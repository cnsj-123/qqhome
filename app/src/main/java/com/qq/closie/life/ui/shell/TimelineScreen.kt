package com.qq.closie.life.ui.shell

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qq.closie.life.capture.CaptureItemEntity
import com.qq.closie.life.capture.CaptureSource
import com.qq.closie.life.capture.CaptureStatus
import com.qq.closie.life.repository.CaptureRepository
import com.qq.closie.life.ui.components.LifeDivider
import com.qq.closie.life.ui.components.LifeEmptyState
import com.qq.closie.life.ui.components.LifeGap
import com.qq.closie.life.ui.components.LifeMediaPreview
import com.qq.closie.life.ui.components.LifePage
import com.qq.closie.life.ui.components.LifeSearchField
import com.qq.closie.life.ui.components.LifeTopBar
import com.qq.closie.life.ui.theme.LifeColors
import com.qq.closie.life.ui.theme.LifeDimensions
import com.qq.closie.life.ui.theme.LifeSpacing
import com.qq.closie.life.ui.theme.LifeTheme
import com.qq.closie.life.ui.theme.LifeType
import com.qq.closie.life.ui.theme.rememberLifeDimensions
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch

private val TimelineDateFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("M月d日 EEEE", Locale.CHINA)

private val TimelineTimeFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("HH:mm", Locale.CHINA)

/**
 * 记录 — the timeline.
 *
 * v0.1 has very little data in it, but the structure is final: a date header per day, then item
 * containers that can hold media, body copy and a metadata row. Future modules (照片 / 消费 / 旅行
 * / 园艺 / 阅读 / OOTD / Plog) all render into this same shape, so this is deliberately built as
 * "spaced editorial list" rather than a dense RecyclerView-style feed.
 *
 * **v0.3.0 — records became things you can act on.** Two changes, both long overdue:
 *
 *  1. **A record opens.** Tapping a row (anywhere except the "···") goes to 记录详情, where the user
 *     can add a title and a note. Before this, a capture could only be deleted — the timeline was a
 *     list of things the app had taken from you and would not let you touch.
 *  2. **A record can be searched.** §8 asked for searchable records and the search field is the
 *     first thing under the title. It searches title, raw text, notes and URL through the DAO's
 *     `LIKE`, so a screenshot whose *text* was OCR'd is findable by what it says.
 *
 * Every record still carries one restrained affordance: a light "···" at its top-right opening a
 * menu with 编辑 and 删除. Deletion asks for confirmation — "删除这条记录？删除后无法恢复。" — and
 * goes through [CaptureRepository.delete]; the Flow the screen observes does the rest.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Composable
fun TimelineScreen(
    captureRepository: CaptureRepository,
    onOpenRecord: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val queryFlow = remember { MutableStateFlow("") }
    val query by queryFlow.collectAsStateWithLifecycle()

    // `flatMapLatest` over the query rather than filtering in Kotlin: the DAO's `LIKE` runs in
    // SQLite and stays correct as the history grows, whereas an in-memory filter would have to hold
    // every capture in memory to search old ones. Debouncing is deliberately absent — a local
    // SQLite query on an indexed table returns faster than a debounce interval would delay it, so a
    // debounce here would only add lag.
    val records by remember(captureRepository) {
        queryFlow.flatMapLatest { q ->
            if (q.isBlank()) captureRepository.observeAll() else captureRepository.search(q)
        }
    }.collectAsStateWithLifecycle(initialValue = emptyList())

    TimelineContent(
        records = records,
        query = query,
        onQueryChange = { queryFlow.value = it },
        onOpenRecord = onOpenRecord,
        onDelete = { record ->
            // The UI layer must not treat a delete as a blocking operation, and the
            // repository already tolerates missing rows (no crash on double-delete). The
            // collectAsState above refreshes the list the moment Room commits.
            scope.launch { captureRepository.delete(record.id) }
        },
        modifier = modifier
    )
}

/**
 * Groups captures into day buckets, newest day first, newest record first inside a day.
 *
 * A plain function (no Compose, no Android framework types beyond java.time) so it can be unit
 * tested directly.
 */
internal fun groupByDate(
    items: List<CaptureItemEntity>,
    zone: ZoneId = ZoneId.systemDefault()
): List<Pair<LocalDate, List<CaptureItemEntity>>> =
    items
        .groupBy { Instant.ofEpochMilli(it.createdAt).atZone(zone).toLocalDate() }
        .toSortedMap(compareByDescending { it })
        .map { (day, records) -> day to records.sortedByDescending { it.createdAt } }

@Composable
internal fun TimelineContent(
    records: List<CaptureItemEntity>,
    modifier: Modifier = Modifier,
    query: String = "",
    onQueryChange: (String) -> Unit = {},
    onOpenRecord: (String) -> Unit = {},
    onDelete: (CaptureItemEntity) -> Unit = {}
) {
    val dimensions = rememberLifeDimensions()
    // The record awaiting confirmation lives here — at the page level — so exactly one dialog
    // can exist at a time and the menu that opened it can close before the dialog shows.
    var pendingDelete by remember { mutableStateOf<CaptureItemEntity?>(null) }

    LifePage(modifier = modifier) {
        item {
            LifeTopBar(title = "记录")
        }

        item { LifeGap(dimensions.blockGap) }

        item {
            LifeSearchField(
                value = query,
                onValueChange = onQueryChange,
                placeholder = "搜索记录内容、备注或链接",
                contentDescription = "搜索记录"
            )
        }

        item { LifeGap(dimensions.sectionGap) }

        if (records.isEmpty()) {
            item {
                // Two different empty states, because they mean different things. "You have no
                // records" should invite a capture; "your search matched nothing" should not, or
                // the user reads it as "my records are gone".
                if (query.isBlank()) {
                    LifeEmptyState(
                        title = "还没有记录",
                        body = "采集进来的内容会按日期出现在这里。"
                    )
                } else {
                    LifeEmptyState(
                        title = "没有找到匹配的记录",
                        body = "试试别的关键词。",
                        actionLabel = "清除搜索",
                        onAction = { onQueryChange("") }
                    )
                }
            }
        } else {
            groupByDate(records).forEach { (day, dayRecords) ->
                item {
                    Text(
                        text = day.format(TimelineDateFormatter),
                        style = LifeType.SectionTitle,
                        color = LifeColors.TextSecondary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = LifeSpacing.xs)
                    )
                }
                dayRecords.forEachIndexed { index, record ->
                    if (index > 0) {
                        item { LifeDivider() }
                    }
                    item {
                        TimelineItem(
                            record = record,
                            onOpen = { onOpenRecord(record.id) },
                            onAskDelete = { pendingDelete = record }
                        )
                    }
                }
                item { LifeGap(dimensions.sectionGap) }
            }
        }
    }

    pendingDelete?.let { record ->
        DeleteConfirmDialog(
            onConfirm = {
                pendingDelete = null
                onDelete(record)
            },
            onDismiss = { pendingDelete = null }
        )
    }
}

/**
 * The confirmation before destruction. 删除 is the destructive action and wears the alert tone;
 * 取消 is the escape hatch and sits left of it, where a thumb expects the safe choice.
 */
@Composable
private fun DeleteConfirmDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = "删除这条记录？", style = LifeType.EditorialTitle, color = LifeColors.TextPrimary) },
        text = { Text(text = "删除后无法恢复。", style = LifeType.BodySecondary, color = LifeColors.TextSecondary) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(text = "删除", style = LifeType.Action, color = LifeColors.Alert)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = "取消", style = LifeType.Action, color = LifeColors.TextPrimary)
            }
        },
        containerColor = LifeColors.SurfaceRaised
    )
}

/**
 * One record. Body and metadata get the width; the "···" is a quiet affordance at the row's
 * top-right — visible when you look for it, invisible when you read. The menu is per-row state,
 * so an open menu can only ever belong to the record it was opened on; deletion itself carries
 * the record's id, never a list index.
 *
 * The whole row is tappable and opens 记录详情, but the "···" sits *outside* that clickable area so
 * tapping the affordance never also opens the record. Two overlapping touch targets on one row is
 * the classic source of "I tried to delete it and it opened instead" — here the menu box consumes
 * its own taps and the row's tap only fires for the rest of the width.
 */
@Composable
private fun TimelineItem(
    record: CaptureItemEntity,
    onOpen: () -> Unit,
    onAskDelete: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = LifeSpacing.md)
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(top = 2.dp)
                .clickable(onClick = onOpen)
        ) {
            // A title the user gave it, if any — the strongest signal of what this record *is*.
            record.displayTitle?.takeIf { it.isNotBlank() }?.let { heading ->
                Text(
                    text = heading,
                    style = LifeType.EditorialTitle,
                    color = LifeColors.TextPrimary,
                    maxLines = 2
                )
                Spacer(Modifier.height(LifeSpacing.xs))
            }

            if (record.primaryMediaAssetId != null) {
                LifeMediaPreview(label = "图片")
                Spacer(Modifier.height(LifeSpacing.sm))
            }

            val body = record.rawText?.takeIf { it.isNotBlank() }
                ?: if (record.primaryMediaAssetId != null) "（图片）" else "（空记录）"
            Text(
                text = body,
                style = LifeType.Body,
                color = LifeColors.TextPrimary,
                maxLines = 3
            )

            // The user's own note. Uses [LifeType.UserNote] (full-charset sans) rather than
            // [LifeType.HandNote]: notes are usually Chinese, and Caveat has no CJK glyphs — a
            // Chinese note fell through to the system font, which is exactly the theme-font leak
            // the bundled fonts exist to prevent.
            record.note?.takeIf { it.isNotBlank() }?.let { userNote ->
                Spacer(Modifier.height(LifeSpacing.xs))
                Text(
                    text = userNote,
                    style = LifeType.UserNote,
                    color = LifeColors.TextSecondary,
                    maxLines = 2
                )
            }

            Spacer(Modifier.height(LifeSpacing.xs))

            // Same rule as the home row: only the "HH:mm" stamp may touch the Latin-only
            // typewriter face. The Chinese source/status words go through Noto Sans SC
            // explicitly, so they can never be resolved by the system font.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = Instant.ofEpochMilli(record.createdAt)
                        .atZone(ZoneId.systemDefault())
                        .format(TimelineTimeFormatter),
                    style = LifeType.Timestamp,
                    color = LifeColors.TextSecondary
                )
                Text(
                    text = " · ${sourceLabel(record.source)} · ${statusLabel(record.status)}",
                    style = LifeType.Caption,
                    color = LifeColors.TextSecondary
                )
            }
        }

        Box {
            Box(
                modifier = Modifier
                    // `minimumInteractiveComponentSize` keeps the touch target at 48dp while
                    // the "···" glyph itself stays visually light.
                    .minimumInteractiveComponentSize()
                    .clickable { menuOpen = true }
                    .semantics { contentDescription = "更多操作" },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "···",
                    style = LifeType.SectionTitle,
                    color = LifeColors.TextTertiary
                )
            }
            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false }
            ) {
                // 打开 first, delete last: the destructive action is never the one under the
                // thumb that just closed the menu, and the safe action is the shortest reach.
                DropdownMenuItem(
                    text = { Text(text = "打开", style = LifeType.Action, color = LifeColors.TextPrimary) },
                    onClick = {
                        menuOpen = false
                        onOpen()
                    }
                )
                DropdownMenuItem(
                    text = { Text(text = "删除", style = LifeType.Action, color = LifeColors.Alert) },
                    onClick = {
                        menuOpen = false
                        onAskDelete()
                    }
                )
            }
        }
    }
}

private fun sourceLabel(source: CaptureSource): String = when (source) {
    CaptureSource.SCREENSHOT -> "截屏"
    CaptureSource.SHARE -> "分享"
    CaptureSource.CLIPBOARD -> "剪贴板"
    CaptureSource.CAMERA -> "相机"
    CaptureSource.GALLERY -> "相册"
    CaptureSource.FLOATING_BALL -> "悬浮球"
    CaptureSource.NOTIFICATION -> "通知"
    CaptureSource.MANUAL -> "手动"
}

private fun statusLabel(status: CaptureStatus): String = when (status) {
    CaptureStatus.NEW -> "新记录"
    CaptureStatus.PROCESSING -> "处理中"
    CaptureStatus.NEEDS_REVIEW -> "待确认"
    CaptureStatus.CONFIRMED -> "已确认"
    CaptureStatus.FAILED -> "失败"
    CaptureStatus.DISMISSED -> "已丢弃"
}

// ------------------------------------------------------------------
//  Preview-only sample data (never referenced by production code)
// ------------------------------------------------------------------

private fun previewTimelineRecords(): List<CaptureItemEntity> {
    val day = Instant.parse("2026-03-24T09:20:00Z").toEpochMilli()
    val earlier = Instant.parse("2026-03-23T21:05:00Z").toEpochMilli()
    return listOf(
        CaptureItemEntity(
            id = "t1",
            source = CaptureSource.SCREENSHOT,
            status = CaptureStatus.NEEDS_REVIEW,
            rawText = null,
            primaryMediaAssetId = "asset-preview-1",
            createdAt = day,
            updatedAt = day
        ),
        CaptureItemEntity(
            id = "t2",
            source = CaptureSource.CLIPBOARD,
            status = CaptureStatus.NEW,
            rawText = "一段从剪贴板收进来的文字",
            createdAt = day - 3_600_000,
            updatedAt = day - 3_600_000
        ),
        CaptureItemEntity(
            id = "t3",
            source = CaptureSource.SHARE,
            status = CaptureStatus.CONFIRMED,
            rawText = "昨天分享进来的一条链接",
            sourceUrl = "https://example.com",
            createdAt = earlier,
            updatedAt = earlier
        )
    )
}

// Device matrix (360 compact / 393 primary / 411 regular), populated and empty.
@Preview(showBackground = true, widthDp = 360, heightDp = 800, name = "记录 — 360x800")
@Preview(showBackground = true, widthDp = 393, heightDp = 852, name = "记录 — 393x852")
@Preview(showBackground = true, widthDp = 411, heightDp = 891, name = "记录 — 411x891")
@Composable
private fun TimelinePreview() {
    LifeTheme {
        TimelineContent(records = previewTimelineRecords())
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 800, name = "记录 — empty 360x800")
@Preview(showBackground = true, widthDp = 393, heightDp = 852, name = "记录 — empty 393x852")
@Preview(showBackground = true, widthDp = 411, heightDp = 891, name = "记录 — empty 411x891")
@Composable
private fun TimelineEmptyPreview() {
    LifeTheme {
        TimelineContent(records = emptyList())
    }
}
