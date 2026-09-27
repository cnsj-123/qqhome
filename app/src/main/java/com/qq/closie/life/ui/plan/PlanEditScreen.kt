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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.qq.closie.life.plan.PlanItemEntity
import com.qq.closie.life.ui.components.LifeDivider
import com.qq.closie.life.ui.components.LifeGap
import com.qq.closie.life.ui.components.LifePage
import com.qq.closie.life.ui.components.LifePrimaryAction
import com.qq.closie.life.ui.components.LifeSecondaryAction
import com.qq.closie.life.ui.components.LifeTopAppBar
import com.qq.closie.life.ui.components.LifeTopBarAction
import com.qq.closie.life.ui.theme.LifeColors
import com.qq.closie.life.ui.theme.LifeShape
import com.qq.closie.life.ui.theme.LifeSpacing
import com.qq.closie.life.ui.theme.LifeTheme
import com.qq.closie.life.ui.theme.LifeType
import com.qq.closie.life.ui.theme.rememberLifeDimensions
import java.time.LocalDate
import java.time.ZoneId

/**
 * 新增 / 编辑计划.
 *
 * The date picker is deliberately a row of quick choices — 今天 / 明天 / 本周末 / 不设定 — rather than
 * a Material `DatePickerDialog`. Two reasons, and the second is the important one:
 *
 *  1. The quick choices cover almost every real plan ("I'll do it today", "sometime next week"). A
 *     full calendar for those is more taps to say the same thing.
 *  2. Choosing a specific day is done by *shifting* the chosen quick date (± days), so the field
 *     stays one line. A date picker dialog would also be the single largest piece of Material chrome
 *     in Life OS, and it would arrive with its own header, headline and tonal surface — visually
 *     nothing like the rest of the app.
 *
 * The same page serves both create and edit: [plan] is null for a new one.
 */
@Composable
fun PlanEditScreen(
    plan: PlanItemEntity?,
    onSave: (title: String, note: String?, dueAt: Long?, clearDue: Boolean) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val zone = remember { ZoneId.systemDefault() }
    var title by remember(plan?.id) { mutableStateOf(plan?.title.orEmpty()) }
    var note by remember(plan?.id) { mutableStateOf(plan?.note.orEmpty()) }
    var dueAt by remember(plan?.id) { mutableStateOf(plan?.dueAt) }

    PlanEditContent(
        title = title,
        note = note,
        dueAt = dueAt,
        isEditing = plan != null,
        onTitleChange = { title = it },
        onNoteChange = { note = it },
        onDueChange = { dueAt = it },
        onSave = {
            onSave(
                title.trim(),
                note.trim().takeIf { it.isNotEmpty() },
                dueAt,
                dueAt == null && plan?.dueAt != null
            )
        },
        onBack = onBack,
        zone = zone,
        modifier = modifier
    )
}

@Composable
internal fun PlanEditContent(
    title: String,
    note: String,
    dueAt: Long?,
    isEditing: Boolean,
    onTitleChange: (String) -> Unit,
    onNoteChange: (String) -> Unit,
    onDueChange: (Long?) -> Unit,
    onSave: () -> Unit,
    onBack: () -> Unit,
    zone: ZoneId = ZoneId.systemDefault(),
    modifier: Modifier = Modifier
) {
    val dims = rememberLifeDimensions()
    val today = remember(zone) { LocalDate.now(zone) }

    LifePage(modifier = modifier) {
        item {
            LifeTopAppBar(
                title = if (isEditing) "编辑计划" else "新增计划",
                onBack = onBack,
                trailing = {
                    LifeTopBarAction(
                        label = "保存",
                        enabled = title.isNotBlank(),
                        onClick = onSave
                    )
                }
            )
        }

        item { LifeGap(dims.blockGap) }

        item {
            Column(modifier = Modifier.fillMaxWidth()) {
                FieldLabel("要做什么")
                PlanTextField(
                    value = title,
                    onValueChange = onTitleChange,
                    placeholder = "写下来就好"
                )

                Spacer(Modifier.height(LifeSpacing.md))

                FieldLabel("备注")
                PlanTextField(
                    value = note,
                    onValueChange = onNoteChange,
                    placeholder = "可选",
                    singleLine = false,
                    minLines = 3
                )
            }
        }

        item { LifeGap(dims.sectionGap) }

        item { LifeDivider() }

        item { LifeGap(dims.sectionGap) }

        item {
            Column(modifier = Modifier.fillMaxWidth()) {
                FieldLabel("什么时候")
                LazyRow(horizontalArrangement = Arrangement.spacedBy(LifeSpacing.sm)) {
                    val options = dueOptions(today, zone)
                    items(options.size) { index ->
                        val option = options[index]
                        DateChip(
                            label = option.label,
                            // 不设定 is selected exactly when there is no date, which sameEpochDay
                            // cannot express (it returns false for two nulls). Handling both cases
                            // here keeps that rule visible next to the chip it describes.
                            selected = if (option.value == null) {
                                dueAt == null
                            } else {
                                sameEpochDay(dueAt, option.value, zone)
                            },
                            onClick = { onDueChange(option.value) }
                        )
                    }
                }
            }
        }

        item { LifeGap(dims.blockGap) }

        item {
            LifePrimaryAction(
                label = "保存",
                enabled = title.isNotBlank(),
                onClick = onSave
            )
        }

        item { Spacer(Modifier.height(LifeSpacing.sm)) }

        item {
            // "Clear the date" is only meaningful once a date exists. Showing it on a new plan would
            // ask the user to clear something that was never set.
            if (dueAt != null) {
                LifeSecondaryAction(
                    label = "清除日期",
                    onClick = { onDueChange(null) }
                )
            }
        }

        item { Spacer(Modifier.height(LifeSpacing.lg)) }
    }
}

/** A quick-choice date, with epoch millis as the value so the field stays one comparable type. */
internal data class DueOption(val label: String, val value: Long?)

/**
 * 今天 / 明天 / 本周末 / 不设定, resolved against the device time zone.
 *
 * "不设定" carries a null value rather than a sentinel like 0 or -1: an undated plan is a legitimate
 * plan (it lives in 接下来), and encoding it as a magic number would be exactly the kind of thing
 * that later reads as "due 1970".
 */
internal fun dueOptions(today: LocalDate, zone: ZoneId): List<DueOption> {
    fun epoch(date: LocalDate): Long = date.atStartOfDay(zone).toInstant().toEpochMilli()
    // Saturday is the weekend anchor; if today is already Saturday or Sunday, use today so the chip
    // never offers a date in the past.
    val daysUntilSaturday = (6 - today.dayOfWeek.value + 7) % 7
    val weekend = today.plusDays(daysUntilSaturday.toLong())
    return listOf(
        DueOption("今天", epoch(today)),
        DueOption("明天", epoch(today.plusDays(1))),
        DueOption("本周末", epoch(weekend)),
        DueOption("不设定", null)
    )
}

/**
 * True when two epoch-milli instants land on the same calendar day in [zone].
 *
 * The chips hold epoch millis ([DueOption.value]) while the current selection ([dueAt]) is also an
 * epoch, so comparing them through a [LocalDate] round-trip would mean converting the candidate back
 * to a date and then back again. Comparing the two instants directly is both shorter and less
 * error-prone: it is the *day* in the user's zone that has to match, not the millisecond.
 *
 * Two nulls are deliberately NOT "the same day" here. A chip with no date (不设定) and a plan with
 * no date are consistent with each other, but that case is handled at the call site where the label
 * is known — returning true here would also make two undated *chips* light up as selected at once.
 */
internal fun sameEpochDay(a: Long?, b: Long?, zone: ZoneId): Boolean {
    if (a == null || b == null) return false
    val dayA = java.time.Instant.ofEpochMilli(a).atZone(zone).toLocalDate()
    val dayB = java.time.Instant.ofEpochMilli(b).atZone(zone).toLocalDate()
    return dayA == dayB
}

@Composable
private fun FieldLabel(text: String) {
    Text(
        text = text,
        style = LifeType.SectionTitle,
        color = LifeColors.TextPrimary
    )
    Spacer(Modifier.height(LifeSpacing.xs))
}

@Composable
private fun PlanTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    singleLine: Boolean = true,
    minLines: Int = 1
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = LifeSpacing.minTouchTarget)
            .background(
                color = LifeColors.SurfaceInset,
                shape = RoundedCornerShape(LifeShape.small)
            ),
        contentAlignment = Alignment.Center
    ) {
        TextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = singleLine,
            minLines = minLines,
            placeholder = {
                Text(
                    text = placeholder,
                    style = LifeType.BodySecondary,
                    color = LifeColors.TextTertiary
                )
            },
            textStyle = LifeType.Body,
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                disabledContainerColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                disabledIndicatorColor = Color.Transparent,
                cursorColor = LifeColors.Accent,
                focusedTextColor = LifeColors.TextPrimary,
                unfocusedTextColor = LifeColors.TextPrimary
            ),
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun DateChip(label: String, selected: Boolean, onClick: () -> Unit) {
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
                color = if (selected) LifeColors.Accent else LifeColors.TextSecondary
            )
            Spacer(Modifier.height(LifeSpacing.xxs))
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
//  Preview
// ------------------------------------------------------------------

@Preview(showBackground = true, widthDp = 393, heightDp = 852, name = "新增计划 — 393x852")
@Preview(showBackground = true, widthDp = 360, heightDp = 800, name = "新增计划 — 360x800")
@Composable
private fun PlanEditPreview() {
    LifeTheme {
        PlanEditContent(
            title = "把上个月的截图整理进资料库",
            note = "先处理待整理的几条",
            dueAt = System.currentTimeMillis(),
            isEditing = false,
            onTitleChange = {}, onNoteChange = {}, onDueChange = {},
            onSave = {}, onBack = {}
        )
    }
}
