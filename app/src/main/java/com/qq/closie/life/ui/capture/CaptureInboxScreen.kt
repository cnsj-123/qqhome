package com.qq.closie.life.ui.capture

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.qq.closie.life.ui.components.LifeDivider
import com.qq.closie.life.ui.components.LifeEmptyState
import com.qq.closie.life.ui.components.LifeGap
import com.qq.closie.life.ui.components.LifeMediaPreview
import com.qq.closie.life.ui.components.LifePage
import com.qq.closie.life.ui.components.LifeSearchField
import com.qq.closie.life.ui.components.LifeTopAppBar
import com.qq.closie.life.ui.components.LifeTopBarAction
import com.qq.closie.life.ui.theme.LifeColors
import com.qq.closie.life.ui.theme.LifeSpacing
import com.qq.closie.ui.lifeos.theme.LifeOsTheme
import com.qq.closie.life.ui.theme.LifeType
import com.qq.closie.life.ui.theme.rememberLifeDimensions
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val TimelineDateFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("M月d日 EEEE", Locale.CHINA)

private val TimelineTimeFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("HH:mm", Locale.CHINA)

@Composable
fun CaptureInboxScreen(viewModel: CaptureInboxViewModel, onOpenRecord: (String) -> Unit,
    onBack: () -> Unit, onCapture: () -> Unit) {
    val query by viewModel.query.collectAsStateWithLifecycle()
    val records by viewModel.records.collectAsStateWithLifecycle()
    CaptureInboxContent(records = records, query = query, onQueryChange = viewModel::search,
        onOpenRecord = onOpenRecord,
        onBack = onBack, onCapture = onCapture)
}

internal fun groupByDate(
    items: List<CaptureItemEntity>,
    zone: ZoneId = ZoneId.systemDefault()
): List<Pair<LocalDate, List<CaptureItemEntity>>> =
    items
        .groupBy { Instant.ofEpochMilli(it.createdAt).atZone(zone).toLocalDate() }
        .toSortedMap(compareByDescending { it })
        .map { (day, records) -> day to records.sortedByDescending { it.createdAt } }

@Composable
internal fun CaptureInboxContent(
    records: List<CaptureItemEntity>,
    modifier: Modifier = Modifier,
    query: String = "",
    onQueryChange: (String) -> Unit = {},
    onOpenRecord: (String) -> Unit = {},
    onBack: () -> Unit = {},
    onCapture: () -> Unit = {}
) {
    val dimensions = rememberLifeDimensions()

    LifePage(modifier = modifier) {
        item {
            LifeTopAppBar(title = "收件箱", onBack = onBack, trailing = {
                LifeTopBarAction(label = "记一下", onClick = onCapture)
            })
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
                            onOpen = { onOpenRecord(record.id) }
                        )
                    }
                }
                item { LifeGap(dimensions.sectionGap) }
            }
        }
    }
}

@Composable
private fun TimelineItem(
    record: CaptureItemEntity,
    onOpen: () -> Unit
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
                    text = { Text(text = "打开", style = LifeType.Action, color = LifeColors.TextPrimary) },
                    onClick = {
                        menuOpen = false
                        onOpen()
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

@Preview(showBackground = true, widthDp = 360, heightDp = 800, name = "记录 — 360x800")
@Preview(showBackground = true, widthDp = 393, heightDp = 852, name = "记录 — 393x852")
@Preview(showBackground = true, widthDp = 411, heightDp = 891, name = "记录 — 411x891")
@Composable
private fun TimelinePreview() {
    LifeOsTheme {
        CaptureInboxContent(records = previewTimelineRecords())
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 800, name = "记录 — empty 360x800")
@Preview(showBackground = true, widthDp = 393, heightDp = 852, name = "记录 — empty 393x852")
@Preview(showBackground = true, widthDp = 411, heightDp = 891, name = "记录 — empty 411x891")
@Composable
private fun TimelineEmptyPreview() {
    LifeOsTheme {
        CaptureInboxContent(records = emptyList())
    }
}
