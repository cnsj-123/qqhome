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
import androidx.compose.runtime.collectAsState
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
import com.qq.closie.life.capture.CaptureItemEntity
import com.qq.closie.life.capture.CaptureSource
import com.qq.closie.life.capture.CaptureStatus
import com.qq.closie.life.repository.CaptureRepository
import com.qq.closie.life.ui.components.LifeDivider
import com.qq.closie.life.ui.components.LifeEmptyState
import com.qq.closie.life.ui.components.LifeGap
import com.qq.closie.life.ui.components.LifeMediaPreview
import com.qq.closie.life.ui.components.LifePage
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
 * Every record carries one restrained affordance: a light "···" at its top-right corner opening
 * a menu whose only v0.2 entry is 删除 (编辑 / 归档 / 关联 come later). Deletion asks for
 * confirmation — "删除这条记录？删除后无法恢复。" — and then goes through
 * [CaptureRepository.delete]; the Flow the screen observes does the rest.
 */
@Composable
fun TimelineScreen(
    captureRepository: CaptureRepository,
    modifier: Modifier = Modifier
) {
    val records by captureRepository.observeAll().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    TimelineContent(
        records = records,
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

        item { LifeGap(dimensions.sectionGap) }

        if (records.isEmpty()) {
            item {
                LifeEmptyState(
                    title = "还没有记录",
                    body = "采集进来的内容会按日期出现在这里。",
                    modifier = Modifier.padding(top = LifeSpacing.lg)
                )
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
 */
@Composable
private fun TimelineItem(
    record: CaptureItemEntity,
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
        ) {
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
