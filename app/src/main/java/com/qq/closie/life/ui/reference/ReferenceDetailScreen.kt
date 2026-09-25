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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.qq.closie.life.core.TagEntity
import com.qq.closie.life.reference.ReferenceItemEntity
import com.qq.closie.life.reference.ReferenceStatus
import com.qq.closie.life.reference.ReferenceType
import com.qq.closie.life.ui.components.LifeDivider
import com.qq.closie.life.ui.components.LifeEmptyState
import com.qq.closie.life.ui.components.LifeGap
import com.qq.closie.life.ui.components.LifePage
import com.qq.closie.life.ui.components.LifeReferenceMetaLine
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
import kotlinx.coroutines.flow.flowOf

private val DetailStampFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm", Locale.US)

/**
 * 资料详情 — one saved thing, read properly.
 *
 * The page's job is to make a screenshot usable: the extracted text is set as *body copy* at the
 * default reading size, because that text is the reason the item was saved. A design that showed the
 * image and hid the text would leave the user with the same problem they had in their photo gallery.
 *
 * Menu actions live behind a single "···" rather than as a row of buttons at the bottom: 编辑 / 归档
 * / 删除 are occasional, and three permanent buttons would give destruction the same visual weight
 * as reading. 删除 is confirmed; 归档 is not, because it is reversible.
 */
@Composable
fun ReferenceDetailScreen(
    viewModel: ReferenceViewModel,
    referenceId: String,
    onBack: () -> Unit,
    onEdit: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val itemFlow = remember(referenceId) { viewModel.observeItem(referenceId) }
    val item by itemFlow.collectAsStateWithLifecycle(initialValue = null)

    val tagsFlow = remember(item) {
        item?.let { viewModel.observeTags(it) } ?: flowOf(emptyList())
    }
    val tags by tagsFlow.collectAsStateWithLifecycle(initialValue = emptyList())

    // The managed image for this reference, if it has one. A `produceState` rather than a Flow
    // because this is a single one-shot lookup keyed by the entity id — see
    // ReferenceViewModel.thumbnailFor. Null for a text-only reference, in which case no image block
    // is drawn at all.
    val thumbnail by produceState<String?>(initialValue = null, item?.lifeEntityId) {
        value = item?.lifeEntityId?.let { viewModel.thumbnailFor(it) }
    }

    ReferenceDetailContent(
        item = item,
        tags = tags,
        thumbnail = thumbnail,
        onBack = onBack,
        onEdit = { item?.let { onEdit(it.id) } },
        onArchive = { item?.let { viewModel.archive(it) } },
        onUnarchive = { item?.let { viewModel.unarchive(it) } },
        onMarkOrganized = { item?.let { viewModel.markOrganized(it) } },
        onDelete = { item?.let { viewModel.delete(it); onBack() } },
        onAddTag = { name -> item?.let { viewModel.addTag(it, name) } },
        onRemoveTag = { tagId -> item?.let { viewModel.removeTag(it, tagId) } },
        modifier = modifier
    )
}

@Composable
internal fun ReferenceDetailContent(
    item: ReferenceItemEntity?,
    tags: List<TagEntity>,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onArchive: () -> Unit,
    onUnarchive: () -> Unit,
    onMarkOrganized: () -> Unit,
    onDelete: () -> Unit,
    onAddTag: (String) -> Unit,
    onRemoveTag: (String) -> Unit,
    modifier: Modifier = Modifier,
    /** Managed path of the reference's image, or null. No image → no image block. */
    thumbnail: String? = null
) {
    val dims = rememberLifeDimensions()
    var menuOpen by remember { mutableStateOf(false) }
    var confirmingDelete by remember { mutableStateOf(false) }
    var newTag by remember { mutableStateOf("") }

    LifePage(modifier = modifier) {
        item {
            LifeTopAppBar(
                title = item?.title?.ifBlank { "未命名" } ?: "资料",
                // The title is the user's own text (or a fetched page title), so it must not use the
                // default PageTitle serif face — that is a subset and would fall back to the system
                // font for any character outside it. UserNote is the full-charset counterpart at
                // near-identical size, so the header keeps its weight but every glyph is bundled.
                titleStyle = LifeType.UserNote,
                onBack = onBack,
                trailing = {
                    Box {
                        LifeTopBarIconAction(
                            icon = Icons.Outlined.MoreHoriz,
                            contentDescription = "更多操作",
                            onClick = { menuOpen = true }
                        )
                        DropdownMenu(
                            expanded = menuOpen,
                            onDismissRequest = { menuOpen = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("编辑", style = LifeType.Action, color = LifeColors.TextPrimary) },
                                onClick = { menuOpen = false; onEdit() }
                            )
                            if (item?.status == ReferenceStatus.INBOX) {
                                DropdownMenuItem(
                                    text = { Text("标记为已整理", style = LifeType.Action, color = LifeColors.TextPrimary) },
                                    onClick = { menuOpen = false; onMarkOrganized() }
                                )
                            }
                            if (item?.status == ReferenceStatus.ARCHIVED) {
                                DropdownMenuItem(
                                    text = { Text("从归档取回", style = LifeType.Action, color = LifeColors.TextPrimary) },
                                    onClick = { menuOpen = false; onUnarchive() }
                                )
                            } else {
                                DropdownMenuItem(
                                    text = { Text("归档", style = LifeType.Action, color = LifeColors.TextPrimary) },
                                    onClick = { menuOpen = false; onArchive() }
                                )
                            }
                            DropdownMenuItem(
                                text = { Text("删除", style = LifeType.Action, color = LifeColors.Alert) },
                                onClick = { menuOpen = false; confirmingDelete = true }
                            )
                        }
                    }
                }
            )
        }

        if (item == null) {
            item { LifeGap(dims.sectionGap) }
            item {
                LifeEmptyState(
                    title = "这条资料不在了",
                    body = "它可能已经被删除。返回资料库看看其他内容。"
                )
            }
            return@LifePage
        }

        item { Spacer(Modifier.height(LifeSpacing.sm)) }

        // Metadata: 时间 · 类型 · 来源, with the timestamp in the typewriter face.
        item {
            LifeReferenceMetaLine(
                stamp = Instant.ofEpochMilli(item.createdAt)
                    .atZone(ZoneId.systemDefault())
                    .format(DetailStampFormatter),
                label = buildString {
                    append(typeLabel(item.referenceType))
                    item.sourceName?.takeIf { it.isNotBlank() }?.let { append(" · $it") }
                    item.author?.takeIf { it.isNotBlank() }?.let { append(" · $it") }
                }
            )
        }

        item { LifeGap(dims.sectionGap) }

        // The saved image — for a screenshot this IS the thing the user came to look at, so it sits
        // directly under the metadata and above the extracted text. Rendered only when a managed
        // path resolved, so a text-only reference gets no empty frame.
        if (thumbnail != null) {
            item {
                AsyncImage(
                    model = thumbnail,
                    // Decorative here: the page's own heading already names the item, so an alt text
                    // repeating it would make a screen reader announce the title twice.
                    contentDescription = null,
                    contentScale = ContentScale.FillWidth,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            color = LifeColors.SurfaceInset,
                            shape = RoundedCornerShape(LifeShape.medium)
                        )
                        .clip(RoundedCornerShape(LifeShape.medium))
                )
            }
            item { LifeGap(dims.sectionGap) }
        }

        if (!item.summary.isNullOrBlank()) {
            item {
                Text(
                    text = item.summary!!,
                    style = LifeType.Body,
                    color = LifeColors.TextPrimary
                )
            }
            item { LifeGap(dims.sectionGap) }
        }

        // Source URL — its own block, because a link is a thing you act on, not metadata.
        if (!item.sourceUrl.isNullOrBlank()) {
            item {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "来源",
                        style = LifeType.SectionTitle,
                        color = LifeColors.TextPrimary
                    )
                    Spacer(Modifier.height(LifeSpacing.xs))
                    Text(
                        text = item.sourceUrl!!,
                        style = LifeType.BodySecondary,
                        color = LifeColors.Accent,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            item { LifeGap(dims.sectionGap) }
        }

        // The extracted text *is* the content for a screenshot or a clipped page.
        if (!item.ocrText.isNullOrBlank()) {
            item {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "内容",
                        style = LifeType.SectionTitle,
                        color = LifeColors.TextPrimary
                    )
                    Spacer(Modifier.height(LifeSpacing.xs))
                    Text(
                        text = item.ocrText!!,
                        style = LifeType.Body,
                        color = LifeColors.TextPrimary
                    )
                }
            }
            item { LifeGap(dims.sectionGap) }
        }

        // --- Tags -------------------------------------------------------------------------
        item {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "标签",
                    style = LifeType.SectionTitle,
                    color = LifeColors.TextPrimary
                )
                Spacer(Modifier.height(LifeSpacing.xs))

                if (tags.isEmpty()) {
                    Text(
                        text = "还没有标签",
                        style = LifeType.BodySecondary,
                        color = LifeColors.TextSecondary
                    )
                } else {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(LifeSpacing.xs)) {
                        items(tags.size) { index ->
                            val tag = tags[index]
                            TagChip(label = tag.name, onRemove = { onRemoveTag(tag.id) })
                        }
                    }
                }

                Spacer(Modifier.height(LifeSpacing.xs))

                // 输入 → 回车添加. No tag recommender, no tag cloud: the whole feature is a text
                // field and the Enter key, which is all a personal archive needs.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = LifeSpacing.minTouchTarget)
                        .background(
                            color = LifeColors.SurfaceInset,
                            shape = RoundedCornerShape(LifeShape.small)
                        )
                        .padding(horizontal = LifeSpacing.searchPadding),
                    contentAlignment = Alignment.CenterStart
                ) {
                    androidx.compose.material3.TextField(
                        value = newTag,
                        onValueChange = { newTag = it },
                        singleLine = true,
                        placeholder = {
                            Text(
                                text = "输入标签后回车",
                                style = LifeType.BodySecondary,
                                color = LifeColors.TextTertiary
                            )
                        },
                        textStyle = LifeType.Body,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = {
                            if (newTag.isNotBlank()) {
                                onAddTag(newTag)
                                newTag = ""
                            }
                        }),
                        colors = androidx.compose.material3.TextFieldDefaults.colors(
                            focusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                            unfocusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                            focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                            unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                            cursorColor = LifeColors.Accent
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }

        item { LifeGap(dims.sectionGap) }
        item { LifeDivider() }
        item { LifeGap(dims.sectionGap) }

        // Honest roadmap note: these relations exist in the model but have no UI yet. Saying so is
        // better than a disabled-looking row or an invented "关联" button that does nothing.
        item {
            Text(
                text = "关联到园艺、旅行或物品会在之后的版本里出现。",
                style = LifeType.BodySecondary,
                color = LifeColors.TextTertiary
            )
        }

        item { Spacer(Modifier.height(LifeSpacing.lg)) }
    }

    if (confirmingDelete) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = {
                Text(
                    text = "删除这条资料？",
                    style = LifeType.EditorialTitle,
                    color = LifeColors.TextPrimary
                )
            },
            text = {
                Text(
                    text = "删除后无法恢复。原始图片不会被删除。",
                    style = LifeType.BodySecondary,
                    color = LifeColors.TextSecondary
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmingDelete = false; onDelete() }) {
                    Text(text = "删除", style = LifeType.Action, color = LifeColors.Alert)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmingDelete = false }) {
                    Text(text = "取消", style = LifeType.Action, color = LifeColors.TextPrimary)
                }
            },
            containerColor = LifeColors.SurfaceRaised
        )
    }
}

/** A removable tag. The whole chip is not clickable — only the ×, so tapping the label is harmless. */
@Composable
private fun TagChip(label: String, onRemove: () -> Unit) {
    Row(
        modifier = Modifier
            .heightIn(min = LifeSpacing.minTouchTarget)
            .background(
                color = LifeColors.SurfaceInset,
                shape = RoundedCornerShape(LifeShape.small)
            )
            .padding(horizontal = LifeSpacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = LifeType.Caption,
            color = LifeColors.TextPrimary
        )
        Spacer(Modifier.width(LifeSpacing.xs))
        Box(
            modifier = Modifier
                .heightIn(min = LifeSpacing.minTouchTarget)
                .clickable(onClick = onRemove),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "×",
                style = LifeType.Caption,
                color = LifeColors.TextSecondary
            )
        }
    }
}
// ------------------------------------------------------------------
//  Previews
// ------------------------------------------------------------------

private fun previewDetailItem(): ReferenceItemEntity {
    val stamp = Instant.parse("2026-09-24T01:20:00Z").toEpochMilli()
    return ReferenceItemEntity(
        id = "r1",
        lifeEntityId = "le-r1",
        title = "用 Room 做显式迁移的正确姿势",
        referenceType = ReferenceType.TUTORIAL,
        status = ReferenceStatus.INBOX,
        summary = "把 schema 导出并提交到仓库，然后手写 Migration。",
        ocrText = "不要把 schema 导出关掉。\n每次升级都写一个 Migration。\n" +
            "禁止 fallbackToDestructiveMigration。",
        sourceUrl = "https://sspai.com/post/example",
        sourceName = "少数派",
        createdAt = stamp,
        updatedAt = stamp
    )
}

@Preview(showBackground = true, widthDp = 393, heightDp = 852, name = "资料详情 — 393x852")
@Preview(showBackground = true, widthDp = 360, heightDp = 800, name = "资料详情 — 360x800")
@Composable
private fun ReferenceDetailPreview() {
    LifeTheme {
        ReferenceDetailContent(
            item = previewDetailItem(),
            tags = listOf(
                TagEntity(id = "t1", name = "Android", normalizedName = "android", createdAt = 0L),
                TagEntity(id = "t2", name = "参考资料", normalizedName = "参考资料", createdAt = 0L)
            ),
            onBack = {}, onEdit = {}, onArchive = {}, onUnarchive = {},
            onMarkOrganized = {}, onDelete = {}, onAddTag = {}, onRemoveTag = {}
        )
    }
}
