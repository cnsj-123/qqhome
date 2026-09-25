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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qq.closie.life.capture.CaptureItemEntity
import com.qq.closie.life.capture.CaptureSource
import com.qq.closie.life.capture.CaptureStatus
import com.qq.closie.life.plan.PlanItemEntity
import com.qq.closie.life.repository.CaptureRepository
import com.qq.closie.life.repository.PlanRepository
import com.qq.closie.life.ui.plan.PlanViewModel
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
 * **v0.3.0 — the page finally shows real data.** Both blocks used to be lies of omission:
 * 接下来 printed "有 N 条待整理", a *count* presented as if it were a section, with no items and
 * nothing to tap; and 记点什么 was styled as the page's one call to action but was not clickable at
 * all. Home is the first screen of the app, so those two were the first impressions it made.
 *
 * Now:
 *
 *  - **最近** reads the three newest captures through `observeRecent(3)` and each row opens 记录详情.
 *    A limited query rather than `observeAll()` — the home page only ever draws three, and holding
 *    the entire capture history in memory to throw most of it away is exactly the kind of thing that
 *    stops being fine once someone has three thousand screenshots.
 *  - **接下来** reads the 计划 module's soonest open items. It is the same data 计划 owns, not a
 *    parallel "things to tidy" concept — so completing an item here removes it there, and there is
 *    no second place for the user's intentions to get lost in.
 *  - **记点什么** opens the capture sheet. It is a real affordance with a 48dp target.
 */
@Composable
fun LifeHomeScreen(
    captureRepository: CaptureRepository,
    planViewModel: PlanViewModel,
    onQuickCapture: () -> Unit,
    onOpenRecord: (String) -> Unit,
    onOpenPlan: () -> Unit,
    modifier: Modifier = Modifier
) {
    val recent by remember(captureRepository) { captureRepository.observeRecent(HOME_RECENT_LIMIT) }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val upcoming by planViewModel.observeUpcomingForHome(HOME_UPCOMING_LIMIT)
        .collectAsStateWithLifecycle()

    LifeHomeContent(
        recent = recent,
        upcoming = upcoming,
        onQuickCapture = onQuickCapture,
        onOpenRecord = onOpenRecord,
        onOpenPlan = onOpenPlan,
        modifier = modifier
    )
}

/** Home draws at most this many captures. See [LifeHomeScreen] for why it is a query limit. */
internal const val HOME_RECENT_LIMIT = 3

/** Home draws at most this many plans. */
internal const val HOME_UPCOMING_LIMIT = 3

@Composable
internal fun LifeHomeContent(
    recent: List<CaptureItemEntity>,
    upcoming: List<PlanItemEntity>,
    onQuickCapture: () -> Unit,
    onOpenRecord: (String) -> Unit = {},
    onOpenPlan: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val dimensions = rememberLifeDimensions()
    val today = LocalDate.now()

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
                        recent.forEachIndexed { index, item ->
                            if (index > 0) {
                                LifeDivider()
                            }
                            RecentRecordRow(
                                item = item,
                                onClick = { onOpenRecord(item.id) }
                            )
                        }
                    }
                }
            }
        }

        item { LifeGap(dimensions.blockGap) }

        item {
            LifeSection(title = "接下来") {
                if (upcoming.isEmpty()) {
                    LifeEmptyState(
                        title = "还没有计划",
                        body = "想到要做什么，就记在计划里。"
                    )
                } else {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        upcoming.forEachIndexed { index, plan ->
                            if (index > 0) {
                                LifeDivider()
                            }
                            HomePlanRow(plan = plan)
                        }
                    }
                }
                Spacer(Modifier.height(LifeSpacing.sm))
                // The section is a *preview* of 计划, three rows deep. The link to the module is
                // what keeps the home page from having to become the module — a user with twenty
                // plans needs the real screen, and this is how they get there.
                Text(
                    text = "查看全部计划 ›",
                    style = LifeType.Action,
                    color = LifeColors.Accent,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = LifeSpacing.minTouchTarget)
                        .clickable(onClick = onOpenPlan)
                        .padding(vertical = LifeSpacing.sm)
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
private fun RecentRecordRow(item: CaptureItemEntity, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = LifeSpacing.sm)
    ) {
        // The user's own title wins; otherwise fall back to the captured text, then to a label for
        // whatever kind of content it is. A row that says "（空记录）" for an image record would be
        // simply wrong.
        val heading = item.displayTitle?.takeIf { it.isNotBlank() }
        val body = item.rawText?.takeIf { it.isNotBlank() }
            ?: if (item.primaryMediaAssetId != null) "（图片）" else "（空记录）"

        if (heading != null) {
            Text(
                text = heading,
                style = LifeType.Body,
                color = LifeColors.TextPrimary,
                maxLines = 1
            )
            Spacer(Modifier.height(LifeSpacing.xxs))
            Text(
                text = body,
                style = LifeType.BodySecondary,
                color = LifeColors.TextSecondary,
                maxLines = 1
            )
        } else {
            Text(
                text = body,
                style = LifeType.Body,
                color = LifeColors.TextPrimary,
                maxLines = 2
            )
        }

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

/**
 * One plan on the home page.
 *
 * Read-only, deliberately. The home page shows what is coming; ticking something off belongs in 计划
 * where the item has a context (its note, its date, its siblings). A checkbox on the home page would
 * mean the user could complete a task while barely looking at it — and the undo for a mis-tap lives
 * one screen away, which is a bad trade for saving one tap.
 */
@Composable
private fun HomePlanRow(plan: PlanItemEntity) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = LifeSpacing.sm)
    ) {
        Text(
            text = plan.title,
            style = LifeType.Body,
            color = LifeColors.TextPrimary,
            maxLines = 2
        )
        val dueLabel = PlanRepository.dueLabel(plan.dueAt, ZoneId.systemDefault())
        if (dueLabel != null) {
            Spacer(Modifier.height(LifeSpacing.xxs))
            Text(
                text = dueLabel,
                style = LifeType.Caption,
                color = LifeColors.TextSecondary
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

private fun previewPlan(id: String, title: String, dueInDays: Long?): PlanItemEntity {
    val zone = ZoneId.systemDefault()
    val due = dueInDays?.let {
        LocalDate.now().plusDays(it).atStartOfDay(zone).toInstant().toEpochMilli()
    }
    val now = Instant.parse("2026-03-24T09:20:00Z").toEpochMilli()
    return PlanItemEntity(
        id = id,
        lifeEntityId = "entity-$id",
        title = title,
        dueAt = due,
        createdAt = now,
        updatedAt = now
    )
}

private fun previewPlans(): List<PlanItemEntity> = listOf(
    previewPlan("pl1", "把上个月的截图整理进资料库", 0),
    previewPlan("pl2", "读完《城市与狗》剩下的两章", 2),
    previewPlan("pl3", "给阳台的绿萝换个盆", null)
)

// Device matrix (360 compact / 393 primary / 411 regular) on both the populated and the empty
// page — the compact breakpoint changes page rhythm, so both sides of 400dp must be eyeballed.
@Preview(showBackground = true, widthDp = 360, heightDp = 800, name = "首页 — records 360x800")
@Preview(showBackground = true, widthDp = 393, heightDp = 852, name = "首页 — records 393x852")
@Preview(showBackground = true, widthDp = 411, heightDp = 891, name = "首页 — records 411x891")
@Composable
private fun LifeHomePreview() {
    LifeTheme {
        LifeHomeContent(
            recent = previewRecords(),
            upcoming = previewPlans(),
            onQuickCapture = {}
        )
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 800, name = "首页 — empty 360x800")
@Preview(showBackground = true, widthDp = 393, heightDp = 852, name = "首页 — empty 393x852")
@Preview(showBackground = true, widthDp = 411, heightDp = 891, name = "首页 — empty 411x891")
@Composable
private fun LifeHomeEmptyPreview() {
    LifeTheme {
        LifeHomeContent(recent = emptyList(), upcoming = emptyList(), onQuickCapture = {})
    }
}
