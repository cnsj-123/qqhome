package com.xiaoming.closie.ui.ootd

import android.app.DatePickerDialog
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.xiaoming.closie.data.ImageStore
import com.xiaoming.closie.data.model.*
import com.xiaoming.closie.data.repository.WardrobeRepository
import com.xiaoming.closie.ui.BackButton
import com.xiaoming.closie.ui.Rose
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

    // Discard: delete every image created during this draft, regardless of current state.
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

    // Save: keep pending images still referenced, drop ones removed before saving.
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

    fun requestNew() {
        if (dirty) pendingAction = { doStartNew() } else doStartNew()
    }

    fun requestEdit(o: Ootd) {
        if (dirty) pendingAction = { doStartEdit(o) } else doStartEdit(o)
    }

    fun requestBack() {
        when {
            dirty -> pendingAction = { back() }
            draftVisible -> cancelDraft()
            else -> back()
        }
    }

    BackHandler(enabled = draftVisible) { requestBack() }

    Scaffold(
        topBar = { TopAppBar(title = { Text("OOTD 日历") }, navigationIcon = { BackButton { requestBack() } }) },
        floatingActionButton = { FloatingActionButton(onClick = { requestNew() }) { Text("+") } }
    ) { pad ->
        LazyColumn(Modifier.padding(pad).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                OutlinedButton(onClick = {
                    val d = LocalDate.parse(date)
                    DatePickerDialog(context, { _, y, m, day ->
                        date = "%04d-%02d-%02d".format(y, m + 1, day)
                    }, d.year, d.monthValue - 1, d.dayOfMonth).show()
                }) { Text("选择日期：$date") }
            }

            val dayOotds = ootds.filter { it.date == date }
            if (dayOotds.isEmpty() && !draftVisible) {
                item {
                    Text("这一天还没有 OOTD", color = Rose, modifier = Modifier.padding(8.dp))
                }
            } else {
                items(dayOotds) { o ->
                    Card(Modifier.fillMaxWidth().clickable { requestEdit(o) }) {
                        Column(Modifier.padding(12.dp)) {
                            Text(o.note.ifBlank { "OOTD" })
                            Text("${o.itemIds.size} 件单品")
                            if (o.images.isNotEmpty()) Text("含 ${o.images.size} 张照片", style = MaterialTheme.typography.bodySmall, color = Rose)
                        }
                    }
                }
            }

            if (draftVisible) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(if (editing == null) "新建 OOTD" else "编辑 OOTD", style = MaterialTheme.typography.titleMedium)
                        OutlinedTextField(note, { note = it }, label = { Text("备注") }, modifier = Modifier.fillMaxWidth())

                        Text("OOTD 照片", style = MaterialTheme.typography.labelLarge)
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(images, key = { it }) { path ->
                                Column {
                                    AsyncImage(File(path), "OOTD 照片", Modifier.size(96.dp), contentScale = ContentScale.Crop)
                                    TextButton(onClick = { images = images.filterNot { it == path } }, modifier = Modifier.fillMaxWidth()) { Text("删除") }
                                }
                            }
                            item { OutlinedButton(onClick = { imagePicker.launch(arrayOf("image/*")) }) { Text("+ 添加照片") } }
                        }

                        Text("选择衣物", style = MaterialTheme.typography.labelLarge)
                        all.filter { it.status == ItemStatus.OWNED }.forEach { i ->
                            Row(
                                Modifier.fillMaxWidth().clickable { selected = if (i.id in selected) selected - i.id else selected + i.id }.padding(6.dp)
                            ) {
                                Checkbox(i.id in selected, onCheckedChange = { selected = if (it) selected + i.id else selected - i.id })
                                Text(i.name, Modifier.padding(start = 8.dp))
                            }
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Button(
                                enabled = selected.isNotEmpty(),
                                onClick = {
                                    repo.saveOotd((editing ?: Ootd(date = date)).copy(date = date, itemIds = selected.toList(), note = note, images = images))
                                    saveDraft()
                                }
                            ) { Text("保存 OOTD") }
                            OutlinedButton(onClick = { cancelDraft() }) { Text("取消") }
                            if (editing != null) TextButton(onClick = { confirmingDelete = true }) { Text("删除", color = MaterialTheme.colorScheme.error) }
                        }
                    }
                }
            }
        }
    }

    if (confirmingDelete) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text("删除这条 OOTD？") },
            text = { Text("会同时移除它关联的穿着记录和照片。") },
            confirmButton = {
                TextButton(onClick = {
                    repo.deleteOotd(editing?.id.orEmpty())
                    cancelDraft()
                    confirmingDelete = false
                }) { Text("删除") }
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
                }) { Text("放弃") }
            },
            dismissButton = { TextButton(onClick = { pendingAction = null }) { Text("继续编辑") } }
        )
    }
}

private data class OotdDraftSnapshot(
    val date: String,
    val note: String,
    val selected: Set<String>,
    val images: List<String>
)
