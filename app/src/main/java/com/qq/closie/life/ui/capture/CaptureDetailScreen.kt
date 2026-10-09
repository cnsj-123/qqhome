package com.qq.closie.life.ui.capture

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.qq.closie.life.capture.CaptureItemEntity
import com.qq.closie.life.capture.CaptureSource
import com.qq.closie.life.capture.CaptureStatus
import com.qq.closie.life.ui.components.LifeDivider
import com.qq.closie.life.ui.components.LifeEmptyState
import com.qq.closie.life.ui.components.LifeGap
import com.qq.closie.life.ui.components.LifePage
import com.qq.closie.life.ui.components.LifeTopAppBar
import com.qq.closie.life.ui.components.LifeTopBarAction
import com.qq.closie.life.ui.theme.LifeColors
import com.qq.closie.life.ui.theme.LifeShape
import com.qq.closie.life.ui.theme.LifeSpacing
import com.qq.closie.ui.lifeos.theme.LifeOsTheme
import com.qq.closie.life.ui.theme.LifeType
import com.qq.closie.life.ui.theme.rememberLifeDimensions
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val DetailStampFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm", Locale.CHINA)

@Composable
fun CaptureDetailScreen(viewModel: CaptureDetailViewModel, onBack: () -> Unit,
    onFileToLibrary: (String) -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.exit) { if (state.exit) onBack() }
    CaptureDetailContent(record = state.record, isLoaded = state.loaded,
        isNew = state.record == null && viewModel.isNew, isEditing = state.editing,
        title = state.title, note = state.note, onTitleChange = viewModel::title,
        onNoteChange = viewModel::note, onStartEdit = viewModel::edit,
        onCancelEdit = viewModel::cancel, onSave = viewModel::save,
        onBack = onBack, onDiscarded = onBack,
        onFileToLibrary = { state.record?.id?.let(onFileToLibrary) },
        mediaPath = state.mediaPath, error = state.error, busy = state.busy)
}

@Composable
internal fun CaptureDetailContent(
    record: CaptureItemEntity?,
    isLoaded: Boolean,
    isEditing: Boolean,
    title: String,
    note: String,
    onTitleChange: (String) -> Unit,
    onNoteChange: (String) -> Unit,
    onStartEdit: () -> Unit,
    onCancelEdit: () -> Unit,
    onSave: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onFileToLibrary: () -> Unit = {},

    isNew: Boolean = false,

    onDiscarded: () -> Unit = onBack,

    mediaPath: String? = null,
    error: String? = null,
    busy: Boolean = false
) {
    val dims = rememberLifeDimensions()

    val missing = isLoaded && record == null && !isNew

    LifePage(modifier = modifier) {
        item {
            LifeTopAppBar(
                title = if (isEditing) "编辑记录" else "记录",
                onBack = when {
                    isEditing && isNew -> onDiscarded
                    isEditing -> onCancelEdit
                    else -> onBack
                },
                backLabel = "返回",
                trailing = {
                    if (isEditing) {
                        LifeTopBarAction(label = if (busy) "保存中" else "保存", onClick = { if (!busy) onSave() })
                    } else if (record != null) {
                        LifeTopBarAction(label = "编辑", onClick = onStartEdit)
                    }
                }
            )
        }

        error?.let { item { Text(it, color = LifeColors.Alert) } }
        item { LifeGap(dims.sectionGap) }

        when {
            !isLoaded -> Unit

            missing -> item {
                LifeEmptyState(
                    title = "这条记录已经不存在",
                    body = "它可能已经在别处被删除了。",
                    actionLabel = "返回",
                    onAction = onBack
                )
            }

            isEditing -> {
                if (isNew) {
                    item {
                        Text(
                            text = "写点什么，保存后才会出现在记录里。",
                            style = LifeType.BodySecondary,
                            color = LifeColors.TextSecondary
                        )
                    }
                    item { LifeGap(LifeSpacing.md) }
                }
                item {
                    EditField(
                        label = "标题",
                        value = title,
                        onValueChange = onTitleChange,
                        placeholder = "给这条记录起个名字（可不填）",
                        singleLine = true
                    )
                }
                item { LifeGap(LifeSpacing.md) }
                item {
                    EditField(
                        label = "我的备注",
                        value = note,
                        onValueChange = onNoteChange,
                        placeholder = "为什么留下它，之后要做什么",
                        singleLine = false
                    )
                }
                item { LifeGap(LifeSpacing.md) }
                if (record != null) {
                    item {
                        ReadOnlyBlock(
                            label = "采集到的内容",
                            value = record.rawText?.takeIf { it.isNotBlank() }
                                ?: "（这条记录没有文字内容）"
                        )
                    }
                }
            }

            else -> {
                val current = record
                if (current == null) {
                    item {
                        LifeEmptyState(
                            title = "这条记录已经不存在",
                            body = "它可能已经在别处被删除了。",
                            actionLabel = "返回",
                            onAction = onBack
                        )
                    }
                } else {
                current.displayTitle?.takeIf { it.isNotBlank() }?.let { heading ->
                    item {
                        Text(
                            text = heading,
                            style = LifeType.EditorialTitle,
                            color = LifeColors.TextPrimary
                        )
                    }
                    item { LifeGap(LifeSpacing.xs) }
                }

                item {
                    Text(
                        text = buildStamp(current),
                        style = LifeType.Caption,
                        color = LifeColors.TextSecondary
                    )
                }

                if (current.primaryMediaAssetId != null) {
                    item { LifeGap(LifeSpacing.md) }
                    item {
                        if (mediaPath != null) {
                            AsyncImage(
                                model = mediaPath,
                                contentDescription = "采集的图片",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(220.dp)
                                    .clip(RoundedCornerShape(LifeShape.medium))
                                    .background(LifeColors.SurfaceInset)
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(180.dp)
                                    .background(
                                        color = LifeColors.SurfaceInset,
                                        shape = RoundedCornerShape(LifeShape.medium)
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "图片无法读取",
                                    style = LifeType.Caption,
                                    color = LifeColors.TextTertiary
                                )
                            }
                        }
                    }
                }

                current.rawText?.takeIf { it.isNotBlank() }?.let { body ->
                    item { LifeGap(dims.blockGap) }
                    item {
                        Text(
                            text = body,
                            style = LifeType.Body,
                            color = LifeColors.TextPrimary
                        )
                    }
                }

                current.note?.takeIf { it.isNotBlank() }?.let { userNote ->
                    item { LifeGap(dims.blockGap) }
                    item { LifeDivider() }
                    item { LifeGap(LifeSpacing.sm) }
                    item {
                        Text(
                            text = "我的备注",
                            style = LifeType.SectionTitle,
                            color = LifeColors.TextPrimary
                        )
                    }
                    item { LifeGap(LifeSpacing.xs) }
                    item {
                        Text(
                            text = userNote,
                            style = LifeType.UserNote,
                            color = LifeColors.TextPrimary
                        )
                    }
                }

                current.sourceUrl?.takeIf { it.isNotBlank() }?.let { url ->
                    item { LifeGap(dims.blockGap) }
                    item {
                        Column {
                            Text(
                                text = "来源",
                                style = LifeType.SectionTitle,
                                color = LifeColors.TextPrimary
                            )
                            Spacer(Modifier.height(LifeSpacing.xs))
                            Text(
                                text = url,
                                style = LifeType.Caption,
                                color = LifeColors.TextSecondary,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                item { LifeGap(dims.blockGap) }
                item { LifeDivider() }
                item { LifeGap(LifeSpacing.sm) }

                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = LifeSpacing.minTouchTarget)
                            .clickable(onClick = onFileToLibrary),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        Text(
                            text = "存进资料库",
                            style = LifeType.Action,
                            color = LifeColors.Accent
                        )
                    }
                }
                }
            }
        }
    }
}

@Composable
private fun EditField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    singleLine: Boolean
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = LifeType.SectionTitle,
            color = LifeColors.TextPrimary
        )
        Spacer(Modifier.height(LifeSpacing.xs))
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = {
                Text(
                    text = placeholder,
                    style = LifeType.BodySecondary,
                    color = LifeColors.TextTertiary
                )
            },
            textStyle = LifeType.Body,
            singleLine = singleLine,
            minLines = if (singleLine) 1 else 3,
            shape = RoundedCornerShape(LifeShape.small),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = LifeColors.ColdWhite,
                unfocusedContainerColor = LifeColors.ColdWhite,
                focusedIndicatorColor = LifeColors.Accent,
                unfocusedIndicatorColor = LifeColors.Hairline,
                focusedTextColor = LifeColors.TextPrimary,
                unfocusedTextColor = LifeColors.TextPrimary,
                cursorColor = LifeColors.Accent
            )
        )
    }
}

@Composable
private fun ReadOnlyBlock(label: String, value: String) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = LifeType.SectionTitle,
            color = LifeColors.TextSecondary
        )
        Spacer(Modifier.height(LifeSpacing.xs))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    color = LifeColors.SurfaceInset,
                    shape = RoundedCornerShape(LifeShape.small)
                )
                .padding(LifeSpacing.sm)
        ) {
            Text(
                text = value,
                style = LifeType.BodySecondary,
                color = LifeColors.TextSecondary
            )
        }
    }
}

private fun buildStamp(record: CaptureItemEntity): String {
    val stamp = Instant.ofEpochMilli(record.createdAt)
        .atZone(ZoneId.systemDefault())
        .format(DetailStampFormatter)
    return "$stamp · ${detailSourceLabel(record.source)} · ${detailStatusLabel(record.status)}"
}

private fun detailSourceLabel(source: CaptureSource): String = when (source) {
    CaptureSource.SCREENSHOT -> "截屏"
    CaptureSource.SHARE -> "分享"
    CaptureSource.CLIPBOARD -> "剪贴板"
    CaptureSource.CAMERA -> "相机"
    CaptureSource.GALLERY -> "相册"
    CaptureSource.FLOATING_BALL -> "悬浮球"
    CaptureSource.NOTIFICATION -> "通知"
    CaptureSource.MANUAL -> "手动"
}

private fun detailStatusLabel(status: CaptureStatus): String = when (status) {
    CaptureStatus.NEW -> "新记录"
    CaptureStatus.PROCESSING -> "处理中"
    CaptureStatus.NEEDS_REVIEW -> "待确认"
    CaptureStatus.CONFIRMED -> "已确认"
    CaptureStatus.FAILED -> "失败"
    CaptureStatus.DISMISSED -> "已丢弃"
}

private fun previewCapture(
    title: String?,
    text: String?,
    note: String?,
    media: String? = null
): CaptureItemEntity {
    val now = Instant.parse("2026-09-24T05:24:00Z").toEpochMilli()
    return CaptureItemEntity(
        id = "preview",
        source = CaptureSource.CLIPBOARD,
        status = CaptureStatus.NEW,
        rawText = text,
        displayTitle = title,
        note = note,
        primaryMediaAssetId = media,
        createdAt = now,
        updatedAt = now
    )
}

@Preview(showBackground = true, widthDp = 393, heightDp = 852, name = "记录详情 — 只读 393x852")
@Preview(showBackground = true, widthDp = 360, heightDp = 800, name = "记录详情 — 只读 360x800")
@Composable
private fun CaptureDetailReadPreview() {
    LifeOsTheme {
        CaptureDetailContent(
            record = previewCapture(
                title = "关于周末的那家咖啡店",
                text = "在街角看到的一家小店，招牌是手写的。下次带书过去坐一下午。",
                note = "周五下班后去，避开人多的时段。"
            ),
            isLoaded = true,
            isEditing = false,
            title = "",
            note = "",
            onTitleChange = {},
            onNoteChange = {},
            onStartEdit = {},
            onCancelEdit = {},
            onSave = {},
            onBack = {}
        )
    }
}

@Preview(showBackground = true, widthDp = 393, heightDp = 852, name = "记录详情 — 编辑 393x852")
@Composable
private fun CaptureDetailEditPreview() {
    LifeOsTheme {
        CaptureDetailContent(
            record = previewCapture(
                title = "关于周末的那家咖啡店",
                text = "在街角看到的一家小店，招牌是手写的。",
                note = null
            ),
            isLoaded = true,
            isEditing = true,
            title = "关于周末的那家咖啡店",
            note = "",
            onTitleChange = {},
            onNoteChange = {},
            onStartEdit = {},
            onCancelEdit = {},
            onSave = {},
            onBack = {}
        )
    }
}

@Preview(showBackground = true, widthDp = 393, heightDp = 852, name = "记录详情 — 图片记录 393x852")
@Composable
private fun CaptureDetailMediaPreview() {
    LifeOsTheme {
        CaptureDetailContent(
            record = previewCapture(
                title = null,
                text = null,
                note = null,
                media = "asset-preview"
            ),
            isLoaded = true,
            isEditing = false,
            title = "",
            note = "",
            onTitleChange = {},
            onNoteChange = {},
            onStartEdit = {},
            onCancelEdit = {},
            onSave = {},
            onBack = {}
        )
    }
}

@Preview(showBackground = true, widthDp = 393, heightDp = 852, name = "记录详情 — 已删除 393x852")
@Composable
private fun CaptureDetailMissingPreview() {
    LifeOsTheme {
        CaptureDetailContent(
            record = null,
            isLoaded = true,
            isEditing = false,
            title = "",
            note = "",
            onTitleChange = {},
            onNoteChange = {},
            onStartEdit = {},
            onCancelEdit = {},
            onSave = {},
            onBack = {}
        )
    }
}

@Preview(showBackground = true, widthDp = 393, heightDp = 852, name = "记录详情 — 新建空白 393x852")
@Composable
private fun CaptureDetailNewPreview() {
    LifeOsTheme {
        CaptureDetailContent(
            record = null,
            isLoaded = true,
            isNew = true,
            isEditing = true,
            title = "",
            note = "",
            onTitleChange = {},
            onNoteChange = {},
            onStartEdit = {},
            onCancelEdit = {},
            onSave = {},
            onBack = {}
        )
    }
}
