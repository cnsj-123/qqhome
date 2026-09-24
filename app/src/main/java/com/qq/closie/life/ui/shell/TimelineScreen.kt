package com.qq.closie.life.ui.shell

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.qq.closie.life.capture.CaptureItemEntity
import com.qq.closie.life.capture.CaptureSource
import com.qq.closie.life.capture.CaptureStatus
import com.qq.closie.life.repository.CaptureRepository
import com.qq.closie.life.ui.components.LifeDivider
import com.qq.closie.life.ui.components.LifeEmptyState
import com.qq.closie.life.ui.components.LifeGap
import com.qq.closie.life.ui.components.LifeMediaPreview
import com.qq.closie.life.ui.components.LifeMetaText
import com.qq.closie.life.ui.components.LifePage
import com.qq.closie.life.ui.components.LifeTopBar
import com.qq.closie.life.ui.theme.LifeColors
import com.qq.closie.life.ui.theme.LifeSpacing
import com.qq.closie.life.ui.theme.LifeTheme
import com.qq.closie.life.ui.theme.LifeType
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

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
 */
@Composable
fun TimelineScreen(
    captureRepository: CaptureRepository,
    modifier: Modifier = Modifier
) {
    val records by captureRepository.observeAll().collectAsState(initial = emptyList())
    TimelineContent(records = records, modifier = modifier)
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
    modifier: Modifier = Modifier
) {
    LifePage(modifier = modifier) {
        item {
            LifeTopBar(title = "记录")
        }

        item { LifeGap(LifeSpacing.xl) }

        if (records.isEmpty()) {
            item {
                LifeEmptyState(
                    title = "还没有记录",
                    body = "采集进来的内容会按日期出现在这里。",
                    modifier = Modifier.padding(top = LifeSpacing.xl)
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
                        TimelineItem(record = record)
                    }
                }
                item { LifeGap(LifeSpacing.sectionGap) }
            }
        }
    }
}

@Composable
private fun TimelineItem(record: CaptureItemEntity) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = LifeSpacing.md)
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

        LifeMetaText(
            text = listOf(
                Instant.ofEpochMilli(record.createdAt)
                    .atZone(ZoneId.systemDefault())
                    .format(TimelineTimeFormatter),
                sourceLabel(record.source),
                statusLabel(record.status)
            ).joinToString(" · ")
        )
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

@Preview(showBackground = true, name = "记录 — with records")
@Composable
private fun TimelinePreview() {
    LifeTheme {
        TimelineContent(records = previewTimelineRecords())
    }
}

@Preview(showBackground = true, name = "记录 — empty")
@Composable
private fun TimelineEmptyPreview() {
    LifeTheme {
        TimelineContent(records = emptyList())
    }
}
