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
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qq.closie.life.reference.ReferenceItemEntity
import com.qq.closie.life.reference.ReferenceStatus
import com.qq.closie.life.reference.ReferenceType
import com.qq.closie.life.ui.components.LifeDivider
import com.qq.closie.life.ui.components.LifeGap
import com.qq.closie.life.ui.components.LifePage
import com.qq.closie.life.ui.components.LifePrimaryAction
import com.qq.closie.life.ui.components.LifeTopAppBar
import com.qq.closie.life.ui.components.LifeTopBarAction
import com.qq.closie.life.ui.theme.LifeColors
import com.qq.closie.life.ui.theme.LifeShape
import com.qq.closie.life.ui.theme.LifeSpacing
import com.qq.closie.life.ui.theme.LifeTheme
import com.qq.closie.life.ui.theme.LifeType
import com.qq.closie.life.ui.theme.rememberLifeDimensions
import java.time.Instant

/**
 * 编辑资料 — title, summary, type, source, author.
 *
 * Two deliberate omissions:
 *
 *  - **The extracted text is not editable here.** It is a record of what the page or screenshot
 *    actually said; letting an edit pass silently rewrite it would destroy the one thing that makes
 *    the item trustworthy. Re-running OCR is a separate concern, exposed through
 *    [com.qq.closie.life.repository.ReferenceRepository.updateOcrText] rather than a free-text box.
 *  - **The URL is editable but not re-fetched.** Re-fetching on save would make an edit feel like a
 *    network operation and could overwrite a title the user just typed. Refreshing metadata stays an
 *    explicit action elsewhere.
 */
@Composable
fun ReferenceEditScreen(
    viewModel: ReferenceViewModel,
    referenceId: String,
    onDone: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val itemFlow = remember(referenceId) { viewModel.observeItem(referenceId) }
    val item by itemFlow.collectAsStateWithLifecycle(initialValue = null)

    ReferenceEditContent(
        item = item,
        onSave = { title, summary, url, author, type ->
            item?.let {
                viewModel.update(
                    item = it,
                    title = title,
                    summary = summary,
                    sourceUrl = url,
                    author = author,
                    type = type
                )
            }
            onDone()
        },
        onBack = onBack,
        modifier = modifier
    )
}

@Composable
internal fun ReferenceEditContent(
    item: ReferenceItemEntity?,
    onSave: (String, String?, String?, String?, ReferenceType) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val dims = rememberLifeDimensions()

    // Keyed on the item id: when the record finishes loading, the fields initialise once from the
    // loaded values and then belong to the user. Re-keying on `item` itself would wipe whatever
    // they are typing the moment the Flow emitted.
    var title by remember(item?.id) { mutableStateOf(item?.title.orEmpty()) }
    var summary by remember(item?.id) { mutableStateOf(item?.summary.orEmpty()) }
    var url by remember(item?.id) { mutableStateOf(item?.sourceUrl.orEmpty()) }
    var author by remember(item?.id) { mutableStateOf(item?.author.orEmpty()) }
    var type by remember(item?.id) { mutableStateOf(item?.referenceType ?: ReferenceType.OTHER) }

    LifePage(modifier = modifier) {
        item {
            LifeTopAppBar(
                title = "编辑资料",
                onBack = onBack,
                trailing = {
                    LifeTopBarAction(
                        label = "保存",
                        // A reference with no title is unlistable, so save stays disabled rather
                        // than silently writing a blank row the user cannot find again.
                        enabled = title.isNotBlank(),
                        onClick = {
                            onSave(
                                title.trim(),
                                summary.trim().takeIf { it.isNotEmpty() },
                                url.trim().takeIf { it.isNotEmpty() },
                                author.trim().takeIf { it.isNotEmpty() },
                                type
                            )
                        }
                    )
                }
            )
        }

        item { LifeGap(dims.blockGap) }

        item {
            Column(modifier = Modifier.fillMaxWidth()) {
                FieldLabel("标题")
                LifeTextField(
                    value = title,
                    onValueChange = { title = it },
                    placeholder = "这条资料叫什么"
                )

                Spacer(Modifier.height(LifeSpacing.md))

                FieldLabel("摘要")
                LifeTextField(
                    value = summary,
                    onValueChange = { summary = it },
                    placeholder = "一两句话记住它为什么重要",
                    singleLine = false,
                    minLines = 3
                )

                Spacer(Modifier.height(LifeSpacing.md))

                FieldLabel("来源链接")
                LifeTextField(
                    value = url,
                    onValueChange = { url = it },
                    placeholder = "https://",
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next)
                )

                Spacer(Modifier.height(LifeSpacing.md))

                FieldLabel("作者")
                LifeTextField(
                    value = author,
                    onValueChange = { author = it },
                    placeholder = "可选"
                )
            }
        }

        item { LifeGap(dims.sectionGap) }

        item { LifeDivider() }

        item { LifeGap(dims.sectionGap) }

        // Type picker: a scrollable row of text chips, matching the library's filter bar so the two
        // pages do not invent two different chip styles.
        item {
            Column(modifier = Modifier.fillMaxWidth()) {
                FieldLabel("类型")
                LazyRow(horizontalArrangement = Arrangement.spacedBy(LifeSpacing.sm)) {
                    items(ReferenceType.entries.size) { index ->
                        val option = ReferenceType.entries[index]
                        TypeChip(
                            label = typeLabel(option),
                            selected = option == type,
                            onClick = { type = option }
                        )
                    }
                }
            }
        }

        item { LifeGap(dims.sectionGap) }

        item {
            LifePrimaryAction(label = "保存", onClick = {
                if (title.isNotBlank()) {
                    onSave(
                        title.trim(),
                        summary.trim().takeIf { it.isNotEmpty() },
                        url.trim().takeIf { it.isNotEmpty() },
                        author.trim().takeIf { it.isNotEmpty() },
                        type
                    )
                }
            })
        }

        item { Spacer(Modifier.height(LifeSpacing.lg)) }
    }
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
private fun LifeTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    singleLine: Boolean = true,
    minLines: Int = 1,
    keyboardOptions: KeyboardOptions = KeyboardOptions(imeAction = ImeAction.Next)
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
            keyboardOptions = keyboardOptions,
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
private fun TypeChip(label: String, selected: Boolean, onClick: () -> Unit) {
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

@Preview(showBackground = true, widthDp = 393, heightDp = 852, name = "编辑资料 — 393x852")
@Preview(showBackground = true, widthDp = 360, heightDp = 800, name = "编辑资料 — 360x800")
@Composable
private fun ReferenceEditPreview() {
    val stamp = Instant.parse("2026-09-24T01:20:00Z").toEpochMilli()
    LifeTheme {
        ReferenceEditContent(
            item = ReferenceItemEntity(
                id = "r1",
                lifeEntityId = "le-r1",
                title = "用 Room 做显式迁移的正确姿势",
                referenceType = ReferenceType.TUTORIAL,
                status = ReferenceStatus.INBOX,
                summary = "把 schema 导出并提交到仓库。",
                sourceUrl = "https://sspai.com/post/example",
                createdAt = stamp,
                updatedAt = stamp
            ),
            onSave = { _, _, _, _, _ -> },
            onBack = {}
        )
    }
}
