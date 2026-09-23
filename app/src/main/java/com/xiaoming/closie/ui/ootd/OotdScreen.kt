package com.xiaoming.closie.ui.ootd

import android.app.DatePickerDialog
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
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.xiaoming.closie.data.ImageStore
import com.xiaoming.closie.data.model.*
import com.xiaoming.closie.data.repository.WardrobeRepository
import com.xiaoming.closie.ui.components.ClosieCompactTopBar
import com.xiaoming.closie.ui.components.ClosieImageTile
import com.xiaoming.closie.ui.theme.ClosieColor
import java.io.File
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OotdScreen(repo: WardrobeRepository, back: () -> Unit) {
    val context = LocalContext.current
    val all by repo.items.collectAsState()
    val ootds by repo.ootds.collectAsState()
    var date by remember { mutableStateOf(LocalDate.now().toString()) }
    var editing by remember { mutableStateOf<Ootd?>(null) }
    var draftVisible by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var images by remember { mutableStateOf(listOf<String>()) }
    var confirmingDelete by remember { mutableStateOf(false) }
    var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var initialSnapshot by remember { mutableStateOf<OotdDraftSnapshot?>(null) }

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

    fun cancelDraft() {
        pendingImages.forEach { ImageStore.deletePrivatePath(context, it) }
        pendingImages.clear()
        editing = null
        note = ""
        selected = emptySet()
        images = emptyList()
        initialSnapshot = null
        draftVisible = false
    }

    fun saveDraft() {
        pendingImages.filterNot { it in images }.forEach { ImageStore.deletePrivatePath(context, it) }
        pendingImages.clear()
        editing = null
        note = ""
        selected = emptySet()
        images = emptyList()
        initialSnapshot = null
        draftVisible = false
    }

    fun doStartNew() {
        cancelDraft()
        date = LocalDate.now().toString()
        editing = null
        note = ""
        selected = emptySet()
        images = emptyList()
        initialSnapshot = OotdDraftSnapshot(date, "", emptySet(), emptyList())
        draftVisible = true
    }

    fun doStartEdit(o: Ootd) {
        cancelDraft()
        date = o.date
        editing = o
        note = o.note
        selected = o.itemIds.toSet()
        images = o.images
        initialSnapshot = OotdDraftSnapshot(o.date, o.note, o.itemIds.toSet(), o.images)
        draftVisible = true
    }

    val dirty = draftVisible && initialSnapshot != null &&
        OotdDraftSnapshot(date, note, selected, images) != initialSnapshot

    fun requestNew() { if (dirty) pendingAction = { doStartNew() } else doStartNew() }
    fun requestEdit(o: Ootd) { if (dirty) pendingAction = { doStartEdit(o) } else doStartEdit(o) }
    fun requestBack() {
        when {
            dirty -> pendingAction = { back() }
            draftVisible -> cancelDraft()
            else -> back()
        }
    }

    BackHandler(enabled = draftVisible) { requestBack() }

    Scaffold(
        containerColor = ClosieColor.Canvas,
        topBar = { ClosieCompactTopBar(title = "OOTD", onBack = { requestBack() }) },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { requestNew() },
                containerColor = ClosieColor.Rose,
                contentColor = ClosieColor.Surface,
                shape = MaterialTheme.shapes.extraLarge
            ) { Icon(Icons.Default.Add, contentDescription = "新建 OOTD") }
        }
    ) { pad ->
        LazyColumn(
            modifier = Modifier
                .padding(pad)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                OutlinedButton(
                    onClick = {
                        val d = runCatching { LocalDate.parse(date) }.getOrDefault(LocalDate.now())
                        DatePickerDialog(context, { _, y, m, day ->
                            date = "%04d-%02d-%02d".format(y, m + 1, day)
                        }, d.year, d.monthValue - 1, d.dayOfMonth).show()
                    },
                    shape = MaterialTheme.shapes.large
                ) { Text("选择日期：$date") }
            }

            val dayOotds = ootds.filter { it.date == date }
            if (dayOotds.isEmpty() && !draftVisible) {
                item {
                    Box(Modifier.fillMaxWidth().padding(vertical = 48.dp), contentAlignment = Alignment.Center) {
                        Text("这一天还没有 OOTD", style = MaterialTheme.typography.bodyLarge, color = ClosieColor.InkTertiary)
                    }
                }
            } else {
                items(dayOotds, key = { it.id }) { o ->
                    OotdCard(o, all, onClick = { requestEdit(o) })
                }
            }

            if (draftVisible) {
                item { OotdEditor(repo, editing, note, { note = it }, selected, { selected = it }, images, { images = it }, imagePicker, { cancelDraft() }, { save ->
                    if (save) {
                        repo.saveOotd((editing ?: Ootd(date = date)).copy(date = date, itemIds = selected.toList(), note = note, images = images))
                    }
                    saveDraft()
                }) }
            }
        }
    }

    if (confirmingDelete) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text("删除这条 OOTD？") },
            text = { Text("会同时移除它关联的穿着记录和照片。") },
            confirmButton = {
                TextButton(onClick = { repo.deleteOotd(editing?.id.orEmpty()); cancelDraft(); confirmingDelete = false }) { Text("删除", color = ClosieColor.Error) }
            },
            dismissButton = { TextButton(onClick = { confirmingDelete = false }) { Text("取消") } }
        )
    }

    pendingAction?.let { action ->
        AlertDialog(
            onDismissRequest = { pendingAction = null },
            title = { Text("还有未保存的修改") },
            text = { Text("确定放弃当前 OOTD 草稿吗？未保存的照片会被删除。") },
            confirmButton = {
                TextButton(onClick = {
                    val a = action
                    pendingAction = null
                    a()
                }) { Text("放弃", color = ClosieColor.Error) }
            },
            dismissButton = { TextButton(onClick = { pendingAction = null }) { Text("继续编辑") } }
        )
    }
}

@Composable
private fun OotdCard(o: Ootd, all: List<ClothingItem>, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = MaterialTheme.shapes.large,
        color = ClosieColor.Surface,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        border = androidx.compose.foundation.BorderStroke(1.dp, ClosieColor.Hairline)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (o.images.isNotEmpty()) {
                ClosieImageTile(
                    model = File(o.images.first()),
                    contentDescription = "OOTD 照片",
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                )
            }
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(o.note.ifBlank { "OOTD" }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("${o.itemIds.size} 件单品", style = MaterialTheme.typography.bodyMedium, color = ClosieColor.InkSecondary)
                if (o.itemIds.isNotEmpty()) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(o.itemIds) { id ->
                            val item = all.find { it.id == id }
                            val img = item?.let { it.images.firstOrNull { img -> img.kind == ImageKind.FLAT } ?: it.images.firstOrNull() }
                            ClosieImageTile(
                                model = img?.localPath?.let { File(it) },
                                contentDescription = item?.name ?: "单品",
                                modifier = Modifier.size(48.dp),
                                contentScale = if (img?.kind == ImageKind.FLAT) ContentScale.Fit else ContentScale.Crop,
                                placeholder = {
                                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                        Text(item?.name?.take(1) ?: "?", color = ClosieColor.InkTertiary, style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OotdEditor(
    repo: WardrobeRepository,
    editing: Ootd?,
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
    val all by repo.items.collectAsState()
    val owned = all.filter { it.status == ItemStatus.OWNED }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(if (editing == null) "新建 OOTD" else "编辑 OOTD", style = MaterialTheme.typography.headlineSmall)

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
                        ClosieImageTile(
                            model = File(path),
                            contentDescription = "OOTD 照片",
                            modifier = Modifier.fillMaxSize()
                        )
                        IconButton(
                            onClick = { onImagesChange(images.filterNot { it == path }) },
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .size(24.dp)
                        ) {
                            Icon(Icons.Default.Close, contentDescription = "删除", tint = ClosieColor.Error)
                        }
                    }
                }
                item {
                    Surface(
                        modifier = Modifier
                            .size(96.dp)
                            .clickable { imagePicker.launch(arrayOf("image/*")) },
                        shape = MaterialTheme.shapes.large,
                        color = ClosieColor.SurfaceSoft,
                        border = androidx.compose.foundation.BorderStroke(1.dp, ClosieColor.Hairline)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text("+", style = MaterialTheme.typography.headlineMedium, color = ClosieColor.InkTertiary)
                        }
                    }
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("选择衣物", style = MaterialTheme.typography.titleMedium)
            owned.forEach { i ->
                val checked = i.id in selected
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.large)
                        .background(if (checked) ClosieColor.RoseSoft else ClosieColor.Surface)
                        .border(1.dp, if (checked) ClosieColor.Rose else ClosieColor.Hairline, MaterialTheme.shapes.large)
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
                    Text(i.name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    Checkbox(checked = checked, onCheckedChange = { onSelectedChange(if (it) selected + i.id else selected - i.id) })
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
                colors = ButtonDefaults.buttonColors(containerColor = ClosieColor.Rose, contentColor = ClosieColor.Surface)
            ) { Text("保存") }
        }
    }
}

private data class OotdDraftSnapshot(
    val date: String,
    val note: String,
    val selected: Set<String>,
    val images: List<String>
)
