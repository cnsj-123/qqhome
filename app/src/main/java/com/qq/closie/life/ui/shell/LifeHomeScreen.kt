package com.qq.closie.life.ui.shell

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.qq.closie.life.capture.CaptureItemEntity
import com.qq.closie.life.capture.CaptureSource
import com.qq.closie.life.capture.CaptureStatus
import com.qq.closie.life.repository.CaptureRepository
import com.qq.closie.life.ui.components.LifeDivider
import com.qq.closie.life.ui.components.LifeEmptyState
import com.qq.closie.life.ui.components.LifeGap
import com.qq.closie.life.ui.components.LifeMetaText
import com.qq.closie.life.ui.components.LifePage
import com.qq.closie.life.ui.components.LifeSection
import com.qq.closie.life.ui.theme.LifeColors
import com.qq.closie.life.ui.theme.LifeSpacing
import com.qq.closie.life.ui.theme.LifeTheme
import com.qq.closie.life.ui.theme.LifeType
import com.qq.closie.life.ui.theme.rememberLifeDimensions
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** 9月24日 · 星期四 — the small date line under the brand, not the page's biggest text. */
private val HomeDateFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("M月d日 · EEEE", Locale.CHINA)

private val HomeTimeFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("HH:mm", Locale.CHINA)

/**
 * 首页 — a quiet desktop, not a dashboard.
 *
 * Header hierarchy follows the design (top to bottom):
 *
 *   Life OS                      — Serif SemiBold, the page's one big line
 *   9月24日 · 星期四               — small sans date line
 *   今天，把重要的放在眼前。        — one quiet status sentence
 *   — 20–24dp —
 *   最近 → content → 20–24dp → 接下来 → content → divider → 记点什么
 *
 * The date is no longer the page's sole oversized title hanging in empty space, and no two
 * consecutive 40dp+ gaps remain: the page should read as "loaded and quiet", not "not yet
 * rendered".
 */
@Composable
fun LifeHomeScreen(
    captureRepository: CaptureRepository,
    onQuickCapture: () -> Unit,
    modifier: Modifier = Modifier
) {
    val recent by captureRepository.observeAll().collectAsState(initial = emptyList())
    LifeHomeContent(
        recent = recent,
        onQuickCapture = onQuickCapture,
        modifier = modifier
    )
}

@Composable
internal fun LifeHomeContent(
    recent: List<CaptureItemEntity>,
    onQuickCapture: () -> Unit,
    modifier: Modifier = Modifier
) {
    val dimensions = rememberLifeDimensions()
    val today = LocalDate.now()
    val pending = recent.count { it.status in PENDING_STATUSES }

    LifePage(modifier = modifier) {
        item {
            LifeHomeHeader(date = today)
        }

        item { LifeGap(dimensions.blockGap) }

        item {
            LifeSection(title = "最近") {
                if (recent.isEmpty()) {
                    // No action button here: the 记点什么 strip at the bottom of the page is the
                    // single capture anchor. Two identical CTAs on one page is noise.
                    LifeEmptyState(
                        title = "还没有记录",
                        body = "截图、照片、一段文字或一个链接，先收进来，之后再整理。"
                    )
                } else {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        recent.take(3).forEachIndexed { index, item ->
                            if (index > 0) {
                                LifeDivider()
                            }
                            RecentRecordRow(item = item)
                        }
                    }
                }
            }
        }

        item { LifeGap(dimensions.blockGap) }

        item {
            LifeSection(title = "接下来") {
                LifeMetaText(
                    text = if (pending > 0) "有 $pending 条待整理" else "没有待整理的内容",
                    modifier = Modifier.padding(vertical = LifeSpacing.xs)
                )
            }
        }

        item { LifeGap(dimensions.blockGap) }

        item {
            Column(modifier = Modifier.fillMaxWidth()) {
                LifeDivider()
                // clickable BEFORE padding: the whole strip is the touch target, not just the
                // three characters of label. This is the page's one call to action, so it has to
                // be reachable with a thumb.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = LifeSpacing.minTouchTarget)
                        .clickable(onClick = onQuickCapture)
                        .padding(vertical = LifeSpacing.sm),
                    contentAlignment = Alignment.CenterStart
                ) {
                    Text(
                        text = "记点什么",
                        style = LifeType.Action,
                        color = LifeColors.Accent
                    )
                }
            }
        }
    }
}

/**
 * Brand → date → one quiet sentence. Three lines, three weights, no oversized orphan date:
 * the biggest thing on the page is the app's name, the way the design draws it.
 */
@Composable
private fun LifeHomeHeader(date: LocalDate, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = "Life OS",
            style = LifeType.Brand,
            color = LifeColors.TextPrimary
        )
        Spacer(Modifier.height(LifeSpacing.xxs))
        Text(
            text = date.format(HomeDateFormatter),
            style = LifeType.Caption,
            color = LifeColors.TextSecondary
        )
        Spacer(Modifier.height(LifeSpacing.titleGap))
        Text(
            text = "今天，把重要的放在眼前。",
            style = LifeType.BodySecondary,
            color = LifeColors.TextSecondary
        )
    }
}

/**
 * Metadata line: 08:31 · 新记录.
 *
 * Two Text runs, deliberately — NOT one string in one style. Special Elite is Latin/numeric only,
 * so rendering "08:31 · 新记录" as a single [LifeType.Timestamp] run would push the Chinese
 * characters into the *system* fallback font, which is exactly how a vivo theme font gets back
 * into the Life OS UI. The time keeps the typewriter stamp; the separator and the Chinese status
 * label are explicitly [LifeType.Caption] (Noto Sans SC). No glyph on this row depends on
 * platform font selection.
 */
@Composable
private fun RecentRecordRow(item: CaptureItemEntity) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = LifeSpacing.sm)
    ) {
        val body = item.rawText?.takeIf { it.isNotBlank() }
            ?: if (item.primaryMediaAssetId != null) "（图片）" else "（空记录）"
        Text(
            text = body,
            style = LifeType.Body,
            color = LifeColors.TextPrimary,
            maxLines = 2
        )
        Spacer(Modifier.height(LifeSpacing.xxs))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = Instant.ofEpochMilli(item.createdAt)
                    .atZone(ZoneId.systemDefault())
                    .format(HomeTimeFormatter),
                style = LifeType.Timestamp,
                color = statusColor(item.status)
            )
            Text(
                text = " · ${statusLabel(item.status)}",
                style = LifeType.Caption,
                color = statusColor(item.status)
            )
        }
    }
}

private val PENDING_STATUSES = setOf(
    CaptureStatus.NEW,
    CaptureStatus.PROCESSING,
    CaptureStatus.NEEDS_REVIEW
)

private fun statusLabel(status: CaptureStatus): String = when (status) {
    CaptureStatus.NEW -> "新记录"
    CaptureStatus.PROCESSING -> "处理中"
    CaptureStatus.NEEDS_REVIEW -> "待确认"
    CaptureStatus.CONFIRMED -> "已确认"
    CaptureStatus.FAILED -> "失败"
    CaptureStatus.DISMISSED -> "已丢弃"
}

/** Failure uses the warm clay tone; everything settled fades to secondary. No saturated red. */
private fun statusColor(status: CaptureStatus): Color = when (status) {
    CaptureStatus.FAILED -> LifeColors.Alert
    else -> LifeColors.TextSecondary
}

// ------------------------------------------------------------------
//  Preview-only sample data
//
//  These builders exist so the previews can show a populated and an empty page. They are private
//  to this file and are never referenced by production code paths, so no preview fixture can leak
//  into the app as fake user data.
// ------------------------------------------------------------------

private fun previewRecord(
    id: String,
    text: String?,
    status: CaptureStatus,
    media: String? = null
): CaptureItemEntity {
    val now = Instant.parse("2026-03-24T09:20:00Z").toEpochMilli()
    return CaptureItemEntity(
        id = id,
        source = CaptureSource.CLIPBOARD,
        status = status,
        rawText = text,
        primaryMediaAssetId = media,
        createdAt = now,
        updatedAt = now
    )
}

private fun previewRecords(): List<CaptureItemEntity> = listOf(
    previewRecord("p1", "一段刚收进来的文字，还没整理。", CaptureStatus.NEW),
    previewRecord("p2", null, CaptureStatus.NEEDS_REVIEW, media = "asset-preview"),
    previewRecord("p3", "已归档的一条记录", CaptureStatus.CONFIRMED)
)

// Device matrix (360 compact / 393 primary / 411 regular) on both the populated and the empty
// page — the compact breakpoint changes page rhythm, so both sides of 400dp must be eyeballed.
@Preview(showBackground = true, widthDp = 360, heightDp = 800, name = "首页 — records 360x800")
@Preview(showBackground = true, widthDp = 393, heightDp = 852, name = "首页 — records 393x852")
@Preview(showBackground = true, widthDp = 411, heightDp = 891, name = "首页 — records 411x891")
@Composable
private fun LifeHomePreview() {
    LifeTheme {
        LifeHomeContent(recent = previewRecords(), onQuickCapture = {})
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 800, name = "首页 — empty 360x800")
@Preview(showBackground = true, widthDp = 393, heightDp = 852, name = "首页 — empty 393x852")
@Preview(showBackground = true, widthDp = 411, heightDp = 891, name = "首页 — empty 411x891")
@Composable
private fun LifeHomeEmptyPreview() {
    LifeTheme {
        LifeHomeContent(recent = emptyList(), onQuickCapture = {})
    }
}
