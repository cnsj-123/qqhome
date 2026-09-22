package com.xiaoming.closie.ui.outfit

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import coil.compose.AsyncImage
import com.xiaoming.closie.data.ImageStore
import com.xiaoming.closie.data.model.ImageKind
import com.xiaoming.closie.data.model.ItemStatus
import com.xiaoming.closie.data.model.Outfit
import com.xiaoming.closie.data.model.Placement
import com.xiaoming.closie.data.repository.WardrobeRepository
import com.xiaoming.closie.ui.BackButton
import com.xiaoming.closie.ui.Rose
import java.io.File
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OutfitStudioScreen(repo: WardrobeRepository, outfitId: String?, back: () -> Unit) {
    val context = LocalContext.current
    val items by repo.items.collectAsState()
    val outfits by repo.outfits.collectAsState()
    val original = outfits.firstOrNull { it.id == outfitId }

    var name by remember(outfitId) { mutableStateOf(original?.name.orEmpty()) }
    var note by remember(outfitId) { mutableStateOf(original?.note.orEmpty()) }
    var placements by remember(outfitId) { mutableStateOf(original?.placements ?: emptyList()) }
    var tryOnImages by remember(outfitId) { mutableStateOf(original?.tryOnImages ?: emptyList()) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var showDiscardDialog by remember { mutableStateOf(false) }

    val pendingTryOn = remember { mutableStateListOf<String>() }
    DisposableEffect(Unit) {
        onDispose { pendingTryOn.forEach { ImageStore.deletePrivatePath(context, it) } }
    }

    val tryOnPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { selected ->
            ImageStore.copyOutfitFromUri(context, selected)?.let { path ->
                pendingTryOn += path
                tryOnImages = tryOnImages + path
            }
        }
    }

    val owned = items.filter { it.status == ItemStatus.OWNED }
    val itemById = items.associateBy { it.id }
    val density = LocalDensity.current
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }

    val dirty = if (original == null) {
        name.isNotBlank() || note.isNotBlank() || placements.isNotEmpty() || tryOnImages.isNotEmpty()
    } else {
        name != original.name || note != original.note || placements != original.placements || tryOnImages != original.tryOnImages
    }

    fun saveCleanup() {
        // 保留仍在 tryOnImages 中的 pending 文件，删除添加后又被移除的
        pendingTryOn.filterNot { it in tryOnImages }.forEach { ImageStore.deletePrivatePath(context, it) }
        pendingTryOn.clear()
    }

    fun discardCleanup() {
        // 放弃：删除本次会话新增的全部 pending 文件
        pendingTryOn.forEach { ImageStore.deletePrivatePath(context, it) }
        pendingTryOn.clear()
    }

    fun requestBack() {
        if (dirty) showDiscardDialog = true else { discardCleanup(); back() }
    }

    BackHandler(enabled = dirty) { showDiscardDialog = true }

    fun save() {
        val base = original ?: Outfit(name = name)
        val normalized = placements
            .map { it.copy(x = it.x.coerceIn(0f, 1f), y = it.y.coerceIn(0f, 1f)) }
            .sortedBy { it.zIndex }
            .mapIndexed { index, p -> p.copy(zIndex = index) }
        repo.saveOutfit(base.copy(name = name, note = note, itemIds = normalized.map { it.itemId }, placements = normalized, tryOnImages = tryOnImages))
        saveCleanup()
        back()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (original == null) "新建搭配" else "编辑搭配") },
                navigationIcon = { BackButton { requestBack() } },
                actions = { TextButton(onClick = { save() }) { Text("保存") } }
            )
        }
    ) { pad ->
        Column(Modifier.padding(pad).padding(16.dp).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(name, { name = it }, label = { Text("搭配名称") }, modifier = Modifier.fillMaxWidth())

            // Canvas
            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFFF3E5E7))
                    .onSizeChanged { canvasSize = it }
            ) {
                placements.forEach { p ->
                    val item = itemById[p.itemId] ?: return@forEach
                    val image = item.images.firstOrNull { it.kind == ImageKind.FLAT } ?: item.images.firstOrNull()
                    val itemSizePx = with(density) { (140.dp * p.scale).roundToPx() }
                    val px = (p.x * canvasSize.width - itemSizePx / 2f).roundToInt()
                    val py = (p.y * canvasSize.height - itemSizePx / 2f).roundToInt()
                    val selected = selectedId == p.itemId

                    Box(
                        Modifier
                            .offset { IntOffset(px, py) }
                            .size(140.dp * p.scale)
                            .zIndex(p.zIndex.toFloat())
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color.White)
                            .border(if (selected) 2.dp else 1.dp, if (selected) Rose else Color(0xFFD9C7CB), RoundedCornerShape(8.dp))
                            .pointerInput(p.itemId) {
                                detectTransformGestures { _, pan, zoom, _ ->
                                    selectedId = p.itemId
                                    val w = canvasSize.width.coerceAtLeast(1)
                                    val h = canvasSize.height.coerceAtLeast(1)
                                    placements = placements.map {
                                        if (it.itemId == p.itemId) it.copy(
                                            x = (it.x + pan.x / w).coerceIn(0.02f, 0.98f),
                                            y = (it.y + pan.y / h).coerceIn(0.02f, 0.98f),
                                            scale = (it.scale * zoom).coerceIn(0.5f, 2.5f)
                                        ) else it
                                    }
                                }
                            }
                            .clickable { selectedId = p.itemId }
                    ) {
                        if (image?.localPath != null) {
                            AsyncImage(File(image.localPath), item.name, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                        } else {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text(item.name, Modifier.padding(4.dp), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                if (placements.isEmpty()) {
                    Text("从下方衣橱选择单品添加到画布", Modifier.align(Alignment.Center), color = Rose)
                }
            }

            // Selected placement controls
            val selectedPlacement = placements.firstOrNull { it.itemId == selectedId }
            if (selectedPlacement != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("缩放", style = MaterialTheme.typography.bodySmall)
                    Slider(
                        value = selectedPlacement.scale,
                        onValueChange = { s ->
                            placements = placements.map { if (it.itemId == selectedPlacement.itemId) it.copy(scale = s) else it }
                        },
                        valueRange = 0.5f..2.5f,
                        modifier = Modifier.weight(1f)
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        placements = placements.map { if (it.itemId == selectedPlacement.itemId) it.copy(zIndex = it.zIndex + 1) else it }
                    }) { Text("前移") }
                    OutlinedButton(onClick = {
                        placements = placements.map { if (it.itemId == selectedPlacement.itemId) it.copy(zIndex = it.zIndex - 1) else it }
                    }) { Text("后移") }
                    TextButton(onClick = {
                        placements = placements.filterNot { it.itemId == selectedPlacement.itemId }
                        selectedId = null
                    }) { Text("移除") }
                }
            }

            // Item picker
            Text("从衣橱添加单品", style = MaterialTheme.typography.titleMedium)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(owned, key = { it.id }) { item ->
                    val added = placements.any { it.itemId == item.id }
                    val image = item.images.firstOrNull { it.kind == ImageKind.FLAT } ?: item.images.firstOrNull()
                    Column(
                        Modifier
                            .width(80.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(enabled = !added) {
                                val z = (placements.maxOfOrNull { it.zIndex } ?: 0) + 1
                                placements = placements + Placement(item.id, zIndex = z)
                                selectedId = item.id
                            }
                            .alpha(if (added) 0.4f else 1f)
                    ) {
                        Box(Modifier.size(80.dp).background(Color.White), contentAlignment = Alignment.Center) {
                            if (image?.localPath != null) {
                                AsyncImage(File(image.localPath), item.name, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                            } else {
                                Text(item.name, Modifier.padding(4.dp), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        Text(item.name, Modifier.padding(4.dp), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            // Try-on photos
            Text("试穿照片", style = MaterialTheme.typography.titleMedium)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(tryOnImages, key = { it }) { path ->
                    Column {
                        AsyncImage(File(path), "试穿照片", Modifier.size(96.dp), contentScale = ContentScale.Crop)
                        TextButton(onClick = { tryOnImages = tryOnImages.filterNot { it == path } }, modifier = Modifier.fillMaxWidth()) { Text("删除") }
                    }
                }
                item { OutlinedButton(onClick = { tryOnPicker.launch(arrayOf("image/*")) }) { Text("+ 添加照片") } }
            }

            Button(
                onClick = { save() },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Rose)
            ) { Text("保存搭配") }
        }
    }

    if (showDiscardDialog) {
        AlertDialog(
            onDismissRequest = { showDiscardDialog = false },
            title = { Text("还有未保存的修改") },
            text = { Text("确定离开吗？未保存的修改和刚添加的照片会被丢弃。") },
            confirmButton = {
                TextButton(onClick = { discardCleanup(); back() }) { Text("放弃修改") }
            },
            dismissButton = { TextButton(onClick = { showDiscardDialog = false }) { Text("继续编辑") } }
        )
    }
}
