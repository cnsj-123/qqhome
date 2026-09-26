package com.qq.closie.ui.ootd

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.qq.closie.data.ImageStore
import com.qq.closie.data.draft.DraftImageCleanup
import com.qq.closie.data.draft.DraftStore
import com.qq.closie.data.model.*
import com.qq.closie.data.repository.WardrobeRepository
import com.qq.closie.ui.LocalWardrobeSnapshot
import com.qq.closie.ui.components.ClosieCompactTopBar
import com.qq.closie.ui.components.ClosieImageTile
import com.qq.closie.ui.theme.ClosieColor
import java.io.File
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OotdScreen(repo: WardrobeRepository, back: () -> Unit) {
    val context = LocalContext.current
    // One shared generation: the calendar resolves each OOTD's item ids against `items`, so the two must
    // come from the same emission. Collected once at the top of the navigation graph — see
    // LocalWardrobeSnapshot.
    val wardrobe = LocalWardrobeSnapshot.current
    val all = wardrobe.items
    val ootds = wardrobe.ootds
    val draftStore = remember { DraftStore(context) }

    var viewMonth by remember { mutableStateOf(YearMonth.now()) }
    var selectedDate by remember { mutableStateOf<String?>(null) }
    var deletingOotd by remember { mutableStateOf<Ootd?>(null) }

    // Editor state.
    var editing by remember { mutableStateOf(false) }
    var editingOotd by remember { mutableStateOf<Ootd?>(null) }
    var editingDraftId by remember { mutableStateOf<String?>(null) }
    var date by remember { mutableStateOf(LocalDate.now().toString()) }
    var note by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var images by remember { mutableStateOf(listOf<String>()) }
    var initialSnapshot by remember { mutableStateOf<OotdSnapshot?>(null) }
    var showExitDialog by remember { mutableStateOf(false) }

    var drafts by remember { mutableStateOf(draftStore.listOotdDrafts()) }
    var showDrafts by remember { mutableStateOf(false) }

    val pendingImages = remember { mutableStateListOf<String>() }
    DisposableEffect(Unit) {
        onDispose { pendingImages.forEach { ImageStore.deletePrivatePath(context, it) } }
    }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { selectedUri ->
            ImageStore.copyOotdFromUri(context, selectedUri)?.let { path ->
                pendingImages += path
                images = images + path
            }
        }
    }

    val itemsById = remember(all) { all.associateBy { it.id } }

    val dirty = editing && initialSnapshot != null &&
        OotdSnapshot(date, note, selected, images) != initialSnapshot

    fun refreshDrafts() { drafts = draftStore.listOotdDrafts() }

    // Deletes only images that no live record or remaining draft references anymore.
    fun cleanupDraftImages(removed: Collection<String>) {
        // One emission for all three surfaces: this decides what is safe to delete, so `items`,
        // `ootds` and `outfits` must be read from the same generation or the sweep could judge a
        // path unreferenced using a view the wardrobe has already moved past.
        val current = repo.snapshot.value
        val live = current.items.flatMap { it.images }.mapNotNull { it.localPath } +
            current.ootds.flatMap { it.images } +
            current.outfits.flatMap { it.tryOnImages }
        val remaining = draftStore.listOotdDrafts().flatMap { it.images } +
            draftStore.listOutfitDrafts().flatMap { it.tryOnImages }
        DraftImageCleanup.cleanupOrphans(removed, live, remaining) { ImageStore.deletePrivatePath(context, it) }
    }

    fun exitEditor() {
        editing = false
        editingOotd = null
        editingDraftId = null
        note = ""
        selected = emptySet()
        images = emptyList()
        initialSnapshot = null
    }

    fun discard() {
        pendingImages.forEach { ImageStore.deletePrivatePath(context, it) }
        pendingImages.clear()
        exitEditor()
    }

    fun saveAsDraft() {
        // Reuse the live entity id when editing an existing OOTD, so restoring + saving updates
        // the original instead of duplicating it. Look up the previous draft by the SAME target id
        // regardless of whether we arrived from a draft or a live record.
        val targetId = editingDraftId ?: editingOotd?.id ?: UUID.randomUUID().toString()
        val previousDraft = draftStore.listOotdDrafts().firstOrNull { it.id == targetId }
        val draft = Ootd(
            id = targetId,
            date = date, itemIds = selected.toList(), note = note, images = images
        )
        draftStore.saveOotdDraft(draft)
        cleanupDraftImages((previousDraft?.images.orEmpty()) - images.toSet())
        pendingImages.filterNot { it in images }.forEach { ImageStore.deletePrivatePath(context, it) }
        pendingImages.clear()
        refreshDrafts()
        exitEditor()
    }

    fun save() {
        val targetDraftId = editingDraftId
        val o = (editingOotd ?: Ootd(date = date)).copy(date = date, itemIds = selected.toList(), note = note, images = images)
        repo.saveOotd(o)
        val removedDraft = targetDraftId?.let { draftStore.deleteOotdDraft(it) }
        removedDraft?.let { cleanupDraftImages(it.images - o.images.toSet()) }
        val retained = o.images.toSet()
        pendingImages.filterNot { it in retained }.forEach { ImageStore.deletePrivatePath(context, it) }
        pendingImages.clear()
        refreshDrafts()
        exitEditor()
    }

    fun openEditor(o: Ootd?, draftId: String?) {
        date = o?.date ?: (selectedDate ?: LocalDate.now().toString())
        editingOotd = o
        editingDraftId = draftId
        note = o?.note.orEmpty()
        selected = o?.itemIds?.toSet() ?: emptySet()
        images = o?.images ?: emptyList()
        initialSnapshot = OotdSnapshot(date, note, selected.toSet(), images.toList())
        editing = true
    }

    fun requestBack() {
        when {
            editing -> if (dirty) showExitDialog = true else exitEditor()
            selectedDate != null -> selectedDate = null
            else -> back()
        }
    }

    BackHandler(enabled = editing || selectedDate != null) { requestBack() }

    Scaffold(
        containerColor = ClosieColor.Canvas,
        topBar = { ClosieCompactTopBar(title = if (editing) "编辑 OOTD" else "OOTD", onBack = { requestBack() }) },
        floatingActionButton = {
            if (!editing && selectedDate == null) {
                FloatingActionButton(
                    onClick = { openEditor(null, null) },
                    containerColor = ClosieColor.Fig,
                    contentColor = ClosieColor.Surface,
                    shape = MaterialTheme.shapes.extraLarge
                ) { Icon(Icons.Default.Add, contentDescription = "新建 OOTD") }
            }
        }
    ) { pad ->
        when {
            editing -> {
                Column(
                    modifier = Modifier
                        .padding(pad)
                        .fillMaxSize()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    OotdEditor(
                        repo = repo,
                        date = date,
                        onDateChange = { date = it },
                        note = note,
                        onNoteChange = { note = it },
                        selected = selected,
                        onSelectedChange = { selected = it },
                        images = images,
                        onImagesChange = { images = it },
                        imagePicker = imagePicker,
                        onCancel = { requestBack() },
                        onDone = { if (it) save() else exitEditor() }
                    )
                }
            }

            selectedDate != null -> {
                val dateStr = selectedDate!!
                val dayOotds = ootds.filter { it.date == dateStr }
                OotdDayDetail(
                    dateStr = dateStr,
                    dayOotds = dayOotds,
                    itemsById = itemsById,
                    onOotdClick = { o -> openEditor(o, null) },
                    onDelete = { o -> deletingOotd = o },
                    onNew = { openEditor(null, null) }
                )
            }

            else -> {
                OotdCalendar(
                    viewMonth = viewMonth,
                    onPrevMonth = { viewMonth = viewMonth.minusMonths(1) },
                    onNextMonth = { viewMonth = viewMonth.plusMonths(1) },
                    ootds = ootds,
                    itemsById = itemsById,
                    draftCount = drafts.size,
                    onDraftClick = { showDrafts = true },
                    onDateClick = { d -> selectedDate = d }
                )
            }
        }
    }

    if (showDrafts) {
        ModalBottomSheet(onDismissRequest = { showDrafts = false }, containerColor = ClosieColor.Surface) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 32.dp)) {
                Text("OOTD 草稿", style = MaterialTheme.typography.titleLarge, color = ClosieColor.Ink)
                Spacer(Modifier.height(8.dp))
                if (drafts.isEmpty()) {
                    Text("还没有草稿", style = MaterialTheme.typography.bodyMedium, color = ClosieColor.InkTertiary)
                } else {
                    drafts.forEach { d ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showDrafts = false; openEditor(d, d.id) }
                                .heightIn(min = 48.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "${d.date} · ${d.itemIds.size} 件",
                                style = MaterialTheme.typography.bodyLarge,
                                color = ClosieColor.Ink,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = {
                                val removed = draftStore.deleteOotdDraft(d.id)
                                removed?.images?.let { cleanupDraftImages(it) }
                                refreshDrafts()
                            }) {
                                Text("删除", color = ClosieColor.Error)
                            }
                        }
                    }
                }
            }
        }
    }

    if (showExitDialog) {
        AlertDialog(
            onDismissRequest = { showExitDialog = false },
            title = { Text("还有未保存的修改") },
            text = { Text("要保存为草稿，还是放弃这次修改？") },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    TextButton(onClick = { showExitDialog = false; saveAsDraft() }) { Text("保存草稿", color = ClosieColor.Fig) }
                    TextButton(onClick = { showExitDialog = false; discard() }) { Text("放弃修改", color = ClosieColor.Error) }
                    TextButton(onClick = { showExitDialog = false }) { Text("继续编辑") }
                }
            }
        )
    }

    deletingOotd?.let { target ->
        AlertDialog(
            onDismissRequest = { deletingOotd = null },
            title = { Text("删除这条 OOTD？") },
            text = { Text("关联的 OOTD 穿着记录会一起移除。") },
            confirmButton = {
                TextButton(onClick = { repo.deleteOotd(target.id); deletingOotd = null }) {
                    Text("删除", color = ClosieColor.Error)
                }
            },
            dismissButton = { TextButton(onClick = { deletingOotd = null }) { Text("取消") } }
        )
    }
}

@Composable
private fun OotdCalendar(
    viewMonth: YearMonth,
    onPrevMonth: () -> Unit,
    onNextMonth: () -> Unit,
    ootds: List<Ootd>,
    itemsById: Map<String, ClothingItem>,
    draftCount: Int,
    onDraftClick: () -> Unit,
    onDateClick: (String) -> Unit
) {
    val cells = remember(viewMonth) { CalendarMath.monthCells(viewMonth) }
    val ootdsByDate = remember(ootds) { ootds.groupBy { it.date } }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onPrevMonth, modifier = Modifier.size(44.dp)) {
                Text("‹", style = MaterialTheme.typography.headlineMedium, color = ClosieColor.Ink)
            }
            Text(
                "${viewMonth.year} 年 ${viewMonth.monthValue} 月",
                style = MaterialTheme.typography.titleMedium,
                color = ClosieColor.Ink
            )
            IconButton(onClick = onNextMonth, modifier = Modifier.size(44.dp)) {
                Text("›", style = MaterialTheme.typography.headlineMedium, color = ClosieColor.Ink)
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("草稿", style = MaterialTheme.typography.bodyMedium, color = ClosieColor.Graphite)
            if (draftCount > 0) {
                TextButton(onClick = onDraftClick) { Text("草稿 $draftCount", color = ClosieColor.Fig) }
            } else {
                Text("无", style = MaterialTheme.typography.bodyMedium, color = ClosieColor.InkTertiary)
            }
        }

        // Weekday headers, Sunday first.
        Row(modifier = Modifier.fillMaxWidth()) {
            CalendarMath.weekdayLabels.forEach { label ->
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    color = ClosieColor.InkTertiary,
                    modifier = Modifier.weight(1f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        }

        cells.chunked(7).forEach { week ->
            Row(modifier = Modifier.fillMaxWidth()) {
                week.forEach { day ->
                    if (day == null) {
                        Spacer(Modifier.weight(1f).aspectRatio(1f))
                    } else {
                        val dayOotds = ootdsByDate[day.toString()].orEmpty()
                        OotdDayCell(
                            day = day,
                            ootds = dayOotds,
                            itemsById = itemsById,
                            modifier = Modifier.weight(1f),
                            onClick = { onDateClick(day.toString()) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun OotdDayCell(
    day: LocalDate,
    ootds: List<Ootd>,
    itemsById: Map<String, ClothingItem>,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val thumb = ootds.firstOrNull()?.let { ootdThumbnail(it, itemsById) }
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .padding(2.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(ClosieColor.Mist)
            .clickable(onClick = onClick)
    ) {
        if (thumb != null) {
            AsyncImage(
                model = File(thumb),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }
        Text(
            day.dayOfMonth.toString(),
            style = MaterialTheme.typography.labelMedium,
            color = ClosieColor.Ink,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(4.dp)
                .background(ClosieColor.Paper.copy(alpha = 0.7f), RoundedCornerShape(6.dp))
                .padding(horizontal = 4.dp, vertical = 1.dp)
        )
        if (ootds.size > 1) {
            Text(
                "+${ootds.size - 1}",
                style = MaterialTheme.typography.labelSmall,
                color = ClosieColor.Paper,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(4.dp)
                    .background(ClosieColor.Fig, RoundedCornerShape(6.dp))
                    .padding(horizontal = 4.dp, vertical = 1.dp)
            )
        }
    }
}

@Composable
private fun OotdDayDetail(
    dateStr: String,
    dayOotds: List<Ootd>,
    itemsById: Map<String, ClothingItem>,
    onOotdClick: (Ootd) -> Unit,
    onDelete: (Ootd) -> Unit,
    onNew: () -> Unit
) {
    val title = runCatching {
        val d = LocalDate.parse(dateStr)
        "${d.monthValue} 月 ${d.dayOfMonth} 日"
    }.getOrDefault(dateStr)

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(title, style = MaterialTheme.typography.headlineSmall, color = ClosieColor.Ink)
            TextButton(onClick = onNew) { Text("+ 新建", color = ClosieColor.Fig) }
        }
        if (dayOotds.isEmpty()) {
            Box(Modifier.fillMaxWidth().padding(vertical = 48.dp), contentAlignment = Alignment.Center) {
                Text("这一天还没有 OOTD", style = MaterialTheme.typography.bodyLarge, color = ClosieColor.InkTertiary)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                items(dayOotds, key = { it.id }) { o ->
                    OotdCard(o = o, itemsById = itemsById, onClick = { onOotdClick(o) }, onDelete = { onDelete(o) })
                }
            }
        }
    }
}

@Composable
private fun OotdCard(o: Ootd, itemsById: Map<String, ClothingItem>, onClick: () -> Unit, onDelete: () -> Unit) {
    val cover = o.images.firstOrNull()?.let { File(it) }
        ?: o.itemIds.mapNotNull { itemsById[it] }.firstOrNull()?.let { item ->
            item.images.firstOrNull { it.kind == ImageKind.FLAT }?.localPath?.let { File(it) }
                ?: item.images.firstOrNull()?.localPath?.let { File(it) }
        }
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = MaterialTheme.shapes.large,
        color = ClosieColor.Surface,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        border = androidx.compose.foundation.BorderStroke(1.dp, ClosieColor.Hairline)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (cover != null) {
                ClosieImageTile(
                    model = cover,
                    contentDescription = "OOTD 照片",
                    modifier = Modifier.fillMaxWidth().aspectRatio(1f)
                )
            }
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val names = o.itemIds.mapNotNull { itemsById[it]?.name }
                if (names.isNotEmpty()) {
                    Text(names.joinToString(" · "), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                }
                if (o.note.isNotBlank()) {
                    Text(o.note, style = MaterialTheme.typography.bodyMedium, color = ClosieColor.Graphite)
                }
                Text("${o.itemIds.size} 件单品", style = MaterialTheme.typography.bodyMedium, color = ClosieColor.InkSecondary)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDelete) { Text("删除", color = ClosieColor.Error) }
                }
            }
        }
    }
}

private fun ootdThumbnail(ootd: Ootd, itemsById: Map<String, ClothingItem>): String? {
    ootd.images.firstOrNull()?.let { return it }
    val item = ootd.itemIds.mapNotNull { itemsById[it] }.firstOrNull() ?: return null
    return item.images.firstOrNull { it.kind == ImageKind.FLAT }?.localPath
        ?: item.images.firstOrNull { it.kind == ImageKind.PRODUCT }?.localPath
        ?: item.images.firstOrNull()?.localPath
}

@Composable
private fun OotdEditor(
    repo: WardrobeRepository,
    date: String,
    onDateChange: (String) -> Unit,
    note: String,
    onNoteChange: (String) -> Unit,
    selected: Set<String>,
    onSelectedChange: (Set<String>) -> Unit,
    images: List<String>,
    onImagesChange: (List<String>) -> Unit,
    imagePicker: androidx.activity.compose.ManagedActivityResultLauncher<Array<String>, android.net.Uri?>,
    onCancel: () -> Unit,
    onDone: (save: Boolean) -> Unit
) {
    val context = LocalContext.current
    val all by repo.items.collectAsState()
    val owned = all.filter { it.status == ItemStatus.OWNED }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(ClosieColor.Mist)
                .clickable {
                    val d = runCatching { LocalDate.parse(date) }.getOrDefault(LocalDate.now())
                    android.app.DatePickerDialog(context, { _, y, m, day ->
                        onDateChange("%04d-%02d-%02d".format(y, m + 1, day))
                    }, d.year, d.monthValue - 1, d.dayOfMonth).show()
                }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("日期", style = MaterialTheme.typography.bodyLarge, color = ClosieColor.Ink)
            Text(date, style = MaterialTheme.typography.bodyLarge, color = ClosieColor.InkSecondary)
        }

        OutlinedTextField(
            value = note,
            onValueChange = onNoteChange,
            label = { Text("备注") },
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large
        )

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("OOTD 照片", style = MaterialTheme.typography.titleMedium)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(images) { path ->
                    Box(Modifier.size(96.dp)) {
                        ClosieImageTile(model = File(path), contentDescription = "OOTD 照片", modifier = Modifier.fillMaxSize())
                        IconButton(
                            onClick = { onImagesChange(images.filterNot { it == path }) },
                            modifier = Modifier.align(Alignment.TopEnd).size(24.dp)
                        ) { Icon(Icons.Default.Close, contentDescription = "删除", tint = ClosieColor.Error) }
                    }
                }
                item {
                    Surface(
                        modifier = Modifier.size(96.dp).clickable { imagePicker.launch(arrayOf("image/*")) },
                        shape = MaterialTheme.shapes.large,
                        color = ClosieColor.SurfaceSoft,
                        border = androidx.compose.foundation.BorderStroke(1.dp, ClosieColor.Hairline)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.Add, contentDescription = "添加照片", tint = ClosieColor.InkTertiary)
                        }
                    }
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.weight(1f)) {
            Text("选择衣物", style = MaterialTheme.typography.titleMedium)
            if (owned.isEmpty()) {
                Text("还没有可用的衣物", style = MaterialTheme.typography.bodyMedium, color = ClosieColor.InkTertiary)
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(owned, key = { it.id }) { i ->
                        val checked = i.id in selected
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(MaterialTheme.shapes.large)
                                .background(if (checked) ClosieColor.FigSoft else ClosieColor.Surface)
                                .border(1.dp, if (checked) ClosieColor.Fig else ClosieColor.Hairline, MaterialTheme.shapes.large)
                                .clickable { onSelectedChange(if (checked) selected - i.id else selected + i.id) }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            val img = i.images.firstOrNull { it.kind == ImageKind.FLAT } ?: i.images.firstOrNull()
                            ClosieImageTile(
                                model = img?.localPath?.let { File(it) },
                                contentDescription = i.name,
                                modifier = Modifier.size(40.dp),
                                contentScale = if (img?.kind == ImageKind.FLAT) ContentScale.Fit else ContentScale.Crop,
                                placeholder = {
                                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                        Text(i.name.take(1), color = ClosieColor.InkTertiary, style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            )
                            Text(i.name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Checkbox(checked = checked, onCheckedChange = { onSelectedChange(if (it) selected + i.id else selected - i.id) })
                        }
                    }
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f), shape = MaterialTheme.shapes.large) { Text("取消") }
            Button(
                onClick = { onDone(true) },
                enabled = selected.isNotEmpty(),
                modifier = Modifier.weight(1f),
                shape = MaterialTheme.shapes.large,
                colors = ButtonDefaults.buttonColors(containerColor = ClosieColor.Fig, contentColor = ClosieColor.Surface)
            ) { Text("保存") }
        }
    }
}

private data class OotdSnapshot(
    val date: String,
    val note: String,
    val selected: Set<String>,
    val images: List<String>
)
