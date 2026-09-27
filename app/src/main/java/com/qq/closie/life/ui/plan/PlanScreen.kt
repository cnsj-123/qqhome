package com.qq.closie.life.ui.plan

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qq.closie.life.plan.PlanItemEntity
import com.qq.closie.life.repository.PlanRepository
import com.qq.closie.life.ui.components.LifeDivider
import com.qq.closie.life.ui.components.LifeEmptyState
import com.qq.closie.life.ui.components.LifeGap
import com.qq.closie.life.ui.components.LifePage
import com.qq.closie.life.ui.components.LifeTopAppBar
import com.qq.closie.life.ui.components.LifeTopBarIconAction
import com.qq.closie.life.ui.theme.LifeColors
import com.qq.closie.life.ui.theme.LifeSpacing
import com.qq.closie.life.ui.theme.LifeTheme
import com.qq.closie.life.ui.theme.LifeType
import com.qq.closie.life.ui.theme.rememberLifeDimensions

/**
 * 计划 — today, next, done.
 *
 * Three sections and nothing else. No progress ring, no completion percentage, no streak counter,
 * no overdue-red. The page is a list of intentions the user wrote down for themselves, and the most
 * important design decision in it is what is *absent*.
 *
 * Completion is a checkbox-style circle on the left rather than a swipe: a swipe-to-complete gesture
 * is invisible until discovered and easy to trigger by accident while scrolling, whereas a 48dp
 * circle is discoverable, reversible and announced correctly to a screen reader.
 */
@Composable
fun PlanScreen(
    viewModel: PlanViewModel,
    onAddPlan: () -> Unit,
    onEditPlan: (PlanItemEntity) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val today by viewModel.today.collectAsStateWithLifecycle()
    val overdue by viewModel.overdue.collectAsStateWithLifecycle()
    val upcoming by viewModel.upcoming.collectAsStateWithLifecycle()
    val completed by viewModel.completed.collectAsStateWithLifecycle()
    val isEmpty by viewModel.isEmpty.collectAsStateWithLifecycle()

    PlanContent(
        today = today,
        overdue = overdue,
        upcoming = upcoming,
        completed = completed,
        isEmpty = isEmpty,
        onAddPlan = onAddPlan,
        onEditPlan = onEditPlan,
        onComplete = viewModel::complete,
        onUncomplete = viewModel::uncomplete,
        onDelete = viewModel::delete,
        onBack = onBack,
        modifier = modifier
    )
}

@Composable
internal fun PlanContent(
    today: List<PlanItemEntity>,
    overdue: List<PlanItemEntity>,
    upcoming: List<PlanItemEntity>,
    completed: List<PlanItemEntity>,
    isEmpty: Boolean,
    onAddPlan: () -> Unit,
    onEditPlan: (PlanItemEntity) -> Unit,
    onComplete: (PlanItemEntity) -> Unit,
    onUncomplete: (PlanItemEntity) -> Unit,
    onDelete: (PlanItemEntity) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val dims = rememberLifeDimensions()
    var pendingDelete by remember { mutableStateOf<PlanItemEntity?>(null) }

    LifePage(modifier = modifier) {
        item {
            LifeTopAppBar(
                title = "计划",
                onBack = onBack,
                trailing = {
                    LifeTopBarIconAction(
                        icon = Icons.Outlined.Add,
                        contentDescription = "新增计划",
                        onClick = onAddPlan
                    )
                }
            )
        }

        if (isEmpty) {
            item { LifeGap(dims.blockGap) }
            item {
                // One honest empty state for the whole page rather than three empty section headers
                // stacked on top of each other, which reads as a broken page.
                LifeEmptyState(
                    title = "今天没有安排",
                    body = "写下一件想做的事，它会出现在这里。",
                    actionLabel = "新增计划",
                    onAction = onAddPlan
                )
            }
            return@LifePage
        }

        item { LifeGap(dims.blockGap) }

        // --- 此前 --------------------------------------------------------------------------
        // Open items whose date has passed. This section is the whole reason the page no longer
        // loses work: before it, an unfinished plan due yesterday matched no section at all and was
        // simply absent from the app. It sits above 今天 because it is the oldest unhandled intent —
        // but it renders in the same neutral styling as every other section (no red, no badge, no
        // count), so it reads as "still open" rather than "you failed".
        if (overdue.isNotEmpty()) {
            item { SectionHeader(PlanSection.OVERDUE.label) }
            overdue.forEachIndexed { index, plan ->
                if (index > 0) item { LifeDivider() }
                item {
                    PlanRow(
                        plan = plan,
                        onToggle = { onComplete(plan) },
                        onEdit = { onEditPlan(plan) },
                        onAskDelete = { pendingDelete = plan }
                    )
                }
            }
            item { LifeGap(dims.sectionGap) }
        }

        // --- 今天 --------------------------------------------------------------------------
        // Rendered whenever anything is *due* today, even if all of it is done — otherwise
        // completing the last task of the day would make the section disappear and the user would
        // lose the small confirmation of having finished it.
        if (today.isNotEmpty()) {
            item { SectionHeader(PlanSection.TODAY.label) }
            today.forEachIndexed { index, plan ->
                if (index > 0) item { LifeDivider() }
                item {
                    PlanRow(
                        plan = plan,
                        onToggle = { if (plan.completedAt == null) onComplete(plan) else onUncomplete(plan) },
                        onEdit = { onEditPlan(plan) },
                        onAskDelete = { pendingDelete = plan }
                    )
                }
            }
            item { LifeGap(dims.sectionGap) }
        }

        // --- 接下来 ------------------------------------------------------------------------
        if (upcoming.isNotEmpty()) {
            item { SectionHeader(PlanSection.UPCOMING.label) }
            upcoming.forEachIndexed { index, plan ->
                if (index > 0) item { LifeDivider() }
                item {
                    PlanRow(
                        plan = plan,
                        onToggle = { onComplete(plan) },
                        onEdit = { onEditPlan(plan) },
                        onAskDelete = { pendingDelete = plan }
                    )
                }
            }
            item { LifeGap(dims.sectionGap) }
        }

        // --- 已完成 ------------------------------------------------------------------------
        if (completed.isNotEmpty()) {
            item { SectionHeader(PlanSection.COMPLETED.label) }
            completed.take(COMPLETED_PREVIEW_LIMIT).forEachIndexed { index, plan ->
                if (index > 0) item { LifeDivider() }
                item {
                    PlanRow(
                        plan = plan,
                        onToggle = { onUncomplete(plan) },
                        onEdit = { onEditPlan(plan) },
                        onAskDelete = { pendingDelete = plan }
                    )
                }
            }
            // The done list is capped: it is a record, not the point of the page, and letting it
            // grow without bound would eventually push 今天 below the fold.
            if (completed.size > COMPLETED_PREVIEW_LIMIT) {
                item {
                    Text(
                        text = "还有 ${completed.size - COMPLETED_PREVIEW_LIMIT} 条已完成",
                        style = LifeType.Caption,
                        color = LifeColors.TextTertiary,
                        modifier = Modifier.padding(vertical = LifeSpacing.sm)
                    )
                }
            }
            item { LifeGap(dims.sectionGap) }
        }

        item { Spacer(Modifier.height(LifeSpacing.lg)) }
    }

    pendingDelete?.let { plan ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = {
                Text(
                    text = "删除这条计划？",
                    style = LifeType.EditorialTitle,
                    color = LifeColors.TextPrimary
                )
            },
            text = {
                Text(
                    text = "删除后无法恢复。",
                    style = LifeType.BodySecondary,
                    color = LifeColors.TextSecondary
                )
            },
            confirmButton = {
                TextButton(onClick = { pendingDelete = null; onDelete(plan) }) {
                    Text(text = "删除", style = LifeType.Action, color = LifeColors.Alert)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(text = "取消", style = LifeType.Action, color = LifeColors.TextPrimary)
                }
            },
            containerColor = LifeColors.SurfaceRaised
        )
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = LifeType.SectionTitle,
        color = LifeColors.TextPrimary,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = LifeSpacing.xs)
    )
}

/**
 * One plan.
 *
 * The circle is the only interactive affordance that is always visible; "···" is quiet and appears
 * to the right. A completed row keeps its place, gains a strikethrough and fades to secondary ink —
 * it is not removed, because seeing what you already did is the reward, not a distraction.
 */
@Composable
private fun PlanRow(
    plan: PlanItemEntity,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    onAskDelete: () -> Unit
) {
    val done = plan.completedAt != null
    var menuOpen by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = LifeSpacing.sm),
        verticalAlignment = Alignment.Top
    ) {
        // Toggle: 48dp target, 20dp visual. `clickable` before any padding so the whole box is hit.
        Box(
            modifier = Modifier
                .size(LifeSpacing.minTouchTarget)
                .clickable(onClick = onToggle),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(LifeSpacing.planCheck)
                    .background(
                        color = if (done) LifeColors.Accent else LifeColors.SurfaceRaised,
                        shape = CircleShape
                    )
                    .clickable(onClick = onToggle),
                contentAlignment = Alignment.Center
            ) {
                if (done) {
                    Icon(
                        imageVector = Icons.Outlined.Check,
                        contentDescription = null,
                        tint = LifeColors.OnAccent,
                        modifier = Modifier.size(LifeSpacing.planCheckIcon)
                    )
                }
            }
        }

        Spacer(Modifier.width(LifeSpacing.xxs))

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(top = LifeSpacing.planRowTextInset)
                .clickable(onClick = onEdit)
        ) {
            Text(
                text = plan.title,
                style = LifeType.Body,
                color = if (done) LifeColors.TextSecondary else LifeColors.TextPrimary,
                textDecoration = if (done) TextDecoration.LineThrough else TextDecoration.None,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
            if (!plan.note.isNullOrBlank()) {
                Spacer(Modifier.height(LifeSpacing.xxs))
                Text(
                    text = plan.note!!,
                    style = LifeType.BodySecondary,
                    color = LifeColors.TextSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            PlanRepository.dueLabel(plan.dueAt)?.let { label ->
                Spacer(Modifier.height(LifeSpacing.xxs))
                Text(
                    text = label,
                    style = LifeType.Caption,
                    color = LifeColors.TextTertiary
                )
            }
        }

        Box {
            Box(
                modifier = Modifier
                    .size(LifeSpacing.minTouchTarget)
                    .clickable { menuOpen = true },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Outlined.MoreHoriz,
                    contentDescription = "更多操作",
                    tint = LifeColors.TextTertiary,
                    modifier = Modifier.size(LifeSpacing.iconSize)
                )
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("编辑", style = LifeType.Action, color = LifeColors.TextPrimary) },
                    onClick = { menuOpen = false; onEdit() }
                )
                DropdownMenuItem(
                    text = {
                        Text(
                            text = if (done) "取消完成" else "标记完成",
                            style = LifeType.Action,
                            color = LifeColors.TextPrimary
                        )
                    },
                    onClick = { menuOpen = false; onToggle() }
                )
                DropdownMenuItem(
                    text = { Text("删除", style = LifeType.Action, color = LifeColors.Alert) },
                    onClick = { menuOpen = false; onAskDelete() }
                )
            }
        }
    }
}

/** How many finished items the 已完成 section shows before summarising the rest. */
private const val COMPLETED_PREVIEW_LIMIT = 8

// ------------------------------------------------------------------
//  Previews
// ------------------------------------------------------------------

private fun previewPlan(
    id: String,
    title: String,
    dueAt: Long?,
    completedAt: Long? = null,
    note: String? = null
): PlanItemEntity = PlanItemEntity(
    id = id,
    lifeEntityId = "le-$id",
    title = title,
    note = note,
    dueAt = dueAt,
    completedAt = completedAt,
    createdAt = 1_700_000_000_000L,
    updatedAt = 1_700_000_000_000L
)

@Preview(showBackground = true, widthDp = 393, heightDp = 852, name = "计划 — 393x852")
@Preview(showBackground = true, widthDp = 360, heightDp = 800, name = "计划 — 360x800")
@Preview(showBackground = true, widthDp = 411, heightDp = 891, name = "计划 — 411x891")
@Composable
private fun PlanPreview() {
    val today = System.currentTimeMillis()
    LifeTheme {
        PlanContent(
            today = listOf(
                previewPlan("p1", "把上个月的截图整理进资料库", today, note = "先处理待整理的几条"),
                previewPlan("p2", "给房间换个灯泡", today, completedAt = today)
            ),
            upcoming = listOf(
                previewPlan("p3", "读完之后写一段笔记", null),
                previewPlan("p4", "整理一下鞋柜", today + 86_400_000L * 3)
            ),
            overdue = listOf(
                previewPlan("p0", "把去年的电费单归档", today - 86_400_000L * 4)
            ),
            completed = listOf(previewPlan("p5", "备份手机相册", today - 86_400_000L, completedAt = today - 86_400_000L)),
            isEmpty = false,
            onAddPlan = {}, onEditPlan = {}, onComplete = {},
            onUncomplete = {}, onDelete = {}, onBack = {}
        )
    }
}

@Preview(showBackground = true, widthDp = 393, heightDp = 852, name = "计划 — empty 393x852")
@Composable
private fun PlanEmptyPreview() {
    LifeTheme {
        PlanContent(
            today = emptyList(), overdue = emptyList(), upcoming = emptyList(), completed = emptyList(),
            isEmpty = true,
            onAddPlan = {}, onEditPlan = {}, onComplete = {},
            onUncomplete = {}, onDelete = {}, onBack = {}
        )
    }
}

/**
 * 此前 only — the shape an item takes the morning after it was due. Deliberately shown at all three
 * widths because it is the section most likely to be "fixed" later by someone adding a red badge;
 * having a preview makes that change visible in review.
 */
@Preview(showBackground = true, widthDp = 393, heightDp = 852, name = "计划 — 此前 393x852")
@Composable
private fun PlanOverduePreview() {
    val today = System.currentTimeMillis()
    LifeTheme {
        PlanContent(
            today = emptyList(),
            overdue = listOf(
                previewPlan("o1", "把上个月的截图整理进资料库", today - 86_400_000L * 2),
                previewPlan("o2", "给绿萝换盆", today - 86_400_000L * 9, note = "已经拖了两周")
            ),
            upcoming = listOf(previewPlan("u1", "读完剩下的两章", null)),
            completed = emptyList(),
            isEmpty = false,
            onAddPlan = {}, onEditPlan = {}, onComplete = {},
            onUncomplete = {}, onDelete = {}, onBack = {}
        )
    }
}
