package com.qq.closie.life.ui.shell

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.qq.closie.life.capture.CaptureItemEntity
import com.qq.closie.life.capture.CaptureSource
import com.qq.closie.life.capture.CaptureStatus
import com.qq.closie.life.repository.CaptureRepository
import com.qq.closie.life.repository.MediaRepository
import com.qq.closie.life.ui.components.LifeDivider
import com.qq.closie.life.ui.components.LifeEmptyState
import com.qq.closie.life.ui.components.LifeGap
import com.qq.closie.life.ui.components.LifePage
import com.qq.closie.life.ui.components.LifeTopAppBar
import com.qq.closie.life.ui.components.LifeTopBarAction
import com.qq.closie.life.ui.theme.LifeColors
import com.qq.closie.life.ui.theme.LifeShape
import com.qq.closie.life.ui.theme.LifeSpacing
import com.qq.closie.life.ui.theme.LifeTheme
import com.qq.closie.life.ui.theme.LifeType
import com.qq.closie.life.ui.theme.rememberLifeDimensions
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.launch

private val DetailStampFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm", Locale.CHINA)

/**
 * 记录详情 — one capture, openable and editable.
 *
 * §8 asked for records to be openable / editable / searchable. Before this screen a capture could
 * only be *deleted*: tapping a row in 记录 did nothing at all, which made the whole timeline feel
 * like a read-only log that the app was merely showing you. A record you cannot open is a record you
 * cannot act on.
 *
 * Two rules shape what is editable:
 *
 *  1. **[CaptureItemEntity.rawText] is shown, never edited.** It is what was captured — the verbatim
 *     history. The editor writes [CaptureItemEntity.displayTitle] and [CaptureItemEntity.note].
 *     Collapsing the two would mean the app could no longer tell "the user annotated this" from
 *     "this is what arrived", and the second is the only thing Life OS actually witnessed.
 *  2. **Read mode and edit mode are distinct states**, not eight always-live text fields. A record
 *     page should first *read* like a page — title, body, stamp — and only then offer editing. Live
 *     fields with borders on every line would make every record look like a form.
 *
 * Media is shown as the real managed image, resolved through [MediaRepository]. An earlier revision
 * left a "（图片）" placeholder here on the grounds that decoding was out of scope — but a screenshot
 * record is mostly *picture*, so the placeholder made this page useless for the one thing it is
 * opened for. When the file genuinely cannot be read the placeholder returns, now saying so.
 */
@Composable
fun CaptureDetailScreen(
    captureRepository: CaptureRepository,
    captureId: String,
    onBack: () -> Unit,
    onDeleted: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * Resolves a record's managed media to a previewable file. Null disables image rendering, which
     * is what previews and the smoke test use.
     */
    mediaRepository: MediaRepository? = null,
    /**
     * "存进资料库" — distil this record into a 资料库 entry.
     *
     * The capture is **never** modified or deleted by this. §20 is explicit that Life OS does not
     * tidy up the user's screenshots on its own: a screenshot they took is theirs, and silently
     * tidying it away because the app happened to save a copy would be the app deleting something
     * it did not create. The action only *reads* the record and writes a new reference, so the
     * original stays in 记录 exactly as captured.
     */
    onFileToLibrary: (String) -> Unit = {}
) {
    // `NEW_ID` means "the user tapped 手动记录 and has not written anything yet". No row exists for
    // this screen until the first save — see the 手动记录 handling in LifeShell for why that matters.
    val isNew = captureId == LifeDestination.NEW_ID

    val scope = rememberCoroutineScope()
    // Loaded once per id. The record is small, it is only read here, and re-reading it on every
    // recomposition would fight the local text state the editor keeps while typing.
    var record by remember(captureId) { mutableStateOf<CaptureItemEntity?>(null) }
    var loaded by remember(captureId) { mutableStateOf(false) }

    LaunchedEffect(captureId) {
        // A new record has nothing to load. Marking it loaded immediately stops the screen from
        // sitting on the silent "still loading" branch forever, and `record` stays null.
        record = if (isNew) null else captureRepository.getById(captureId)
        loaded = true
    }

    // A brand-new record opens straight into the editor — there is nothing to read yet — and the
    // fields start empty.
    var editing by remember(captureId) { mutableStateOf(isNew) }
    var title by remember(captureId) { mutableStateOf("") }
    var note by remember(captureId) { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf(false) }
    // Distinguishes "the user chose not to write anything" from "the record is gone". Both render
    // as an empty state, but only the first offers 返回 and neither should claim a deletion that
    // never happened.
    var discarded by remember(captureId) { mutableStateOf(false) }

    CaptureDetailContent(
        record = record,
        isLoaded = loaded,
        isNew = isNew,
        isEditing = editing,
        title = title,
        note = note,
        onTitleChange = { title = it },
        onNoteChange = { note = it },
        onStartEdit = {
            // Seed the fields from the loaded record, not from a Flow, so an in-flight write can
            // never yank the text out from under the cursor.
            title = record?.displayTitle.orEmpty()
            note = record?.note.orEmpty()
            editing = true
        },
        onCancelEdit = {
            // For a record that exists, 取消 just leaves the editor — the row is already there and
            // stays as it was. For a *new* record there is no row to return to, so cancelling means
            // the whole thing is discarded: nothing was ever written, which is the point.
            editing = false
            if (isNew) {
                discarded = true
            }
        },
        onSave = {
            val current = record
            scope.launch {
                if (current == null) {
                    // A new record with nothing in it is not a record. Refusing to write an empty
                    // row here is the other half of bug #3: an abandoned or blank 手动记录 must
                    // create nothing, rather than the "（空记录）" v0.2 left behind.
                    if (title.isBlank() && note.isBlank()) {
                        discarded = true
                        return@launch
                    }
                    val id = UUID.randomUUID().toString()
                    captureRepository.create(id = id, source = CaptureSource.MANUAL)
                    captureRepository.updateUserFields(id, title, note)
                    record = captureRepository.getById(id)
                } else {
                    captureRepository.updateUserFields(current.id, title, note)
                    record = captureRepository.getById(current.id)
                }
                editing = false
            }
        },
        onAskDelete = { confirmDelete = true },
        onFileToLibrary = { record?.let { onFileToLibrary(it.id) } },
        onBack = { if (discarded) onBack() else onDeleted() },
        onDiscarded = onBack,
        mediaRepository = mediaRepository,
        modifier = modifier
    )

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = {
                Text(
                    text = "删除这条记录？",
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
                TextButton(onClick = {
                    confirmDelete = false
                    val id = record?.id ?: captureId
                    scope.launch {
                        captureRepository.delete(id)
                        onDeleted()
                    }
                }) {
                    Text(text = "删除", style = LifeType.Action, color = LifeColors.Alert)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text(text = "取消", style = LifeType.Action, color = LifeColors.TextPrimary)
                }
            },
            containerColor = LifeColors.SurfaceRaised
        )
    }
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
    onAskDelete: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onFileToLibrary: () -> Unit = {},
    /**
     * True when this screen is a *new* record with no row behind it yet. Such a screen shows no
     * 编辑 / 存进资料库 / 删除 actions — they would all act on a record that does not exist — and
     * the editor's back affordance reads 取消 rather than 返回.
     */
    isNew: Boolean = false,
    /** Leaves after a new record was abandoned without saving. */
    onDiscarded: () -> Unit = onBack,
    /** Resolves this record's managed media; null disables image rendering (previews, smoke test). */
    mediaRepository: MediaRepository? = null
) {
    val dims = rememberLifeDimensions()

    // A new record opens directly in the editor, so `record == null` here is not "missing" — it is
    // "not written yet". Gating the empty state on `!isNew` is what keeps the screen from telling
    // the user their new record "已经不存在".
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
                        LifeTopBarAction(label = "保存", onClick = onSave)
                    } else if (record != null) {
                        LifeTopBarAction(label = "编辑", onClick = onStartEdit)
                    }
                }
            )
        }

        item { LifeGap(dims.sectionGap) }

        when {
            // Still loading. Deliberately silent — a spinner on a screen that usually opens in
            // under a frame is more attention-grabbing than the content itself.
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
                // A brand-new record gets a one-line explanation of what 保存 will do, because the
                // alternative — nothing on screen but two empty fields — reads like the record
                // already exists and is simply blank.
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
                // A brand-new record has nothing captured yet, so the read-only block is omitted
                // entirely rather than showing an empty "（这条记录没有文字内容）" box for a record
                // that has not been created.
                if (record != null) {
                    item {
                        // Same containment rule as everywhere else: rawText is shown read-only so
                        // the user can see what they are annotating without being able to
                        // overwrite it.
                        ReadOnlyBlock(
                            label = "采集到的内容",
                            value = record.rawText?.takeIf { it.isNotBlank() }
                                ?: "（这条记录没有文字内容）"
                        )
                    }
                }
            }

            else -> {
                // Reached only when `record != null`: the `missing` branch above already consumed
                // the null case (`missing = isLoaded && record == null && !isNew`, and `isNew`
                // entries always land in the `isEditing` branch because editing starts true).
                // Assigning to a local is what gives the compiler a non-null smart cast across the
                // whole branch — `record` itself is a delegated property and cannot be smart-cast.
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
                        // The real image, resolved through MediaRepository, instead of the literal
                        // text "（图片）".
                        //
                        // A screenshot record is mostly *picture*; showing the two characters 图片 in
                        // a grey box told the user nothing they did not already know and made the
                        // detail page useless for the one thing it is opened for — checking which
                        // screenshot this was. 资料库 was already fixed to show its managed image;
                        // this is the same fix at the record level.
                        //
                        // Resolved via produceState rather than collected as a flow: the value is a
                        // single path that never changes while the screen is open, and a flow would
                        // re-read the database on every recomposition for a constant result. Only
                        // *managed* paths are returned, and only when the file still exists — a
                        // dangling path would render as a permanent broken thumbnail, which is worse
                        // than the honest grey box.
                        val assetId = current.primaryMediaAssetId
                        val mediaPath by produceState<String?>(initialValue = null, assetId) {
                            value = assetId?.let { mediaRepository?.managedImagePathFor(it) }
                        }
                        if (mediaPath != null) {
                            AsyncImage(
                                model = File(mediaPath!!),
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(220.dp)
                                    .clip(RoundedCornerShape(LifeShape.medium))
                                    .background(LifeColors.SurfaceInset)
                            )
                        } else {
                            // Still a placeholder when the bytes are genuinely unavailable — but a
                            // placeholder that says *why*, rather than one that pretends to be the
                            // image.
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
                        // [LifeType.UserNote], not [LifeType.HandNote]. This is text the user
                        // typed, and it is usually Chinese — Caveat (HandNote) has no CJK glyphs,
                        // so a Chinese note fell back to the system font and picked up the device
                        // theme's face. See UserNote's doc comment.
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

                // 存进资料库 sits ABOVE 删除, at the top of the destructive block rather than in a
                // menu. Filing something is the constructive thing a user does on this screen —
                // it is the reason the record exists — so it is a visible action, not a hidden one.
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

                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = LifeSpacing.minTouchTarget)
                            .clickable(onClick = onAskDelete),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        Text(
                            text = "删除这条记录",
                            style = LifeType.Action,
                            color = LifeColors.Alert
                        )
                    }
                }
                }
            }
        }
    }
}

/** A labelled, bordered input. `label` above the field rather than floating inside it. */
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

/** Read-only context block used inside the editor, styled to look deliberately un-editable. */
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

/** `2026.09.24 13:24 · 剪贴板 · 新记录` — one caption line, Chinese labels split off the stamp. */
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

// ------------------------------------------------------------------
//  Previews
// ------------------------------------------------------------------

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
    LifeTheme {
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
            onAskDelete = {},
            onBack = {}
        )
    }
}

@Preview(showBackground = true, widthDp = 393, heightDp = 852, name = "记录详情 — 编辑 393x852")
@Composable
private fun CaptureDetailEditPreview() {
    LifeTheme {
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
            onAskDelete = {},
            onBack = {}
        )
    }
}

@Preview(showBackground = true, widthDp = 393, heightDp = 852, name = "记录详情 — 图片记录 393x852")
@Composable
private fun CaptureDetailMediaPreview() {
    LifeTheme {
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
            onAskDelete = {},
            onBack = {}
        )
    }
}

@Preview(showBackground = true, widthDp = 393, heightDp = 852, name = "记录详情 — 已删除 393x852")
@Composable
private fun CaptureDetailMissingPreview() {
    LifeTheme {
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
            onAskDelete = {},
            onBack = {}
        )
    }
}

/**
 * 手动记录 — a new record that has not been written yet.
 *
 * Worth a preview of its own because this is the state whose *absence* was bug #3: v0.2 never
 * showed this screen (it inserted an empty row and opened that instead), so the empty-editor
 * layout has to be checked visually — two empty fields and a one-line hint, no 编辑 / 存进资料库 /
 * 删除 actions, and 取消 as the way out.
 */
@Preview(showBackground = true, widthDp = 393, heightDp = 852, name = "记录详情 — 新建空白 393x852")
@Composable
private fun CaptureDetailNewPreview() {
    LifeTheme {
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
            onAskDelete = {},
            onBack = {}
        )
    }
}
