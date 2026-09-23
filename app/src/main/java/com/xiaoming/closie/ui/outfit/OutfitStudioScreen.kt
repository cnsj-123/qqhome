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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
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
import com.xiaoming.closie.ui.components.ClosieCompactTopBar
import com.xiaoming.closie.ui.components.ClosieImageTile
import com.xiaoming.closie.ui.theme.ClosieColor
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
        pendingTryOn.filterNot { it in tryOnImages }.forEach { ImageStore.deletePrivatePath(context, it) }
        pendingTryOn.clear()
    }

    fun discardCleanup() {
        pendingTryOn.forEach { ImageStore.deletePrivatePath(context, it) }
        pendingTryOn.clear()
    }

    fun requestBack() { if (dirty) showDiscardDialog = true else { discardCleanup(); back() } }
    BackHandler(enabled = dirty) { showDiscardDialog = true }

    fun save() {
        val normalized = placements
            .map { it.copy(x = it.x.coerceIn(0f, 1f), y = it.y.coerceIn(0f, 1f)) }
            .sortedBy { it.zIndex }
            .mapIndexed { index, p -> p.copy(zIndex = index) }
        repo.saveOutfit((original ?: Outfit(name = name)).copy(name = name, note = note, itemIds = normalized.map { it.itemId }, placements = normalized, tryOnImages = tryOnImages))
        saveCleanup()
        back()
    }

    Scaffold(
        containerColor = ClosieColor.Canvas,
        topBar = {
            ClosieCompactTopBar(
                title = if (original == null) "新建搭配" else "编辑搭配",
                onBack = { requestBack() },
                actions = { TextButton(onClick = { save() }) { Text("保存", color = ClosieColor.Rose) } }
            )
        },
        bottomBar = {
            if (selectedId != null) {
                val selectedPlacement = placements.firstOrNull { it.itemId == selectedId }
                selectedPlacement?.let { p ->
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = ClosieColor.Surface,
                        tonalElevation = 0.dp,
                        shadowElevation = 0.dp,
                        border = androidx.compose.foundation.BorderStroke(1.dp, ClosieColor.Hairline)
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("缩放", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(40.dp))
                                Slider(
                                    value = p.scale,
                                    onValueChange = { s ->
                                        placements = placements.map { if (it.itemId == p.itemId) it.copy(scale = s) else it }
                                    },
                                    valueRange = 0.5f..2.5f,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { placements = placements.map { if (it.itemId == p.itemId) it.copy(zIndex = it.zIndex + 1) else it } }, modifier = Modifier.weight(1f)) { Text("前移") }
                                OutlinedButton(onClick = { placements = placements.map { if (it.itemId == p.itemId) it.copy(zIndex = it.zIndex - 1) else it } }, modifier = Modifier.weight(1f)) { Text("后移") }
                                OutlinedButton(onClick = { placements = placements.filterNot { it.itemId == p.itemId }; selectedId = null }, modifier = Modifier.weight(1f)) { Text("移除") }
                            }
                        }
                    }
                }
            }
        }
    ) { pad ->
        Column(
            modifier = Modifier
                .padding(pad)
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("搭配名称") },
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large
            )

            // Canvas
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(RoundedCornerShape(16.dp))
                    .background(ClosieColor.SurfaceSoft)
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
                        modifier = Modifier
                            .offset { IntOffset(px, py) }
                            .size(140.dp * p.scale)
                            .zIndex(p.zIndex.toFloat())
                            .clip(RoundedCornerShape(12.dp))
                            .background(ClosieColor.Surface)
                            .border(if (selected) 2.dp else 1.dp, if (selected) ClosieColor.Rose else ClosieColor.Hairline, RoundedCornerShape(12.dp))
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
                    Text("从下方衣橱选择单品添加到画布", Modifier.align(Alignment.Center), color = ClosieColor.InkTertiary)
                }
            }

            // Item picker
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("从衣橱添加单品", style = MaterialTheme.typography.titleMedium)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(owned, key = { it.id }) { item ->
                        val added = placements.any { it.itemId == item.id }
                        val image = item.images.firstOrNull { it.kind == ImageKind.FLAT } ?: item.images.firstOrNull()
                        Column(
                            modifier = Modifier
                                .width(72.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .clickable(enabled = !added) {
                                    val z = (placements.maxOfOrNull { it.zIndex } ?: 0) + 1
                                    placements = placements + Placement(item.id, zIndex = z)
                                    selectedId = item.id
                                }
                                .alpha(if (added) 0.4f else 1f),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            ClosieImageTile(
                                model = image?.localPath?.let { File(it) },
                                contentDescription = item.name,
                                modifier = Modifier.size(72.dp),
                                contentScale = if (image?.kind == ImageKind.FLAT) ContentScale.Fit else ContentScale.Crop,
                                placeholder = {
                                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                        Text(item.name, Modifier.padding(4.dp), style = MaterialTheme.typography.bodySmall, color = ClosieColor.InkTertiary)
                                    }
                                }
                            )
                            Text(item.name, Modifier.padding(4.dp), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            // Try-on photos
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("试穿照片", style = MaterialTheme.typography.titleMedium)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(tryOnImages, key = { it }) { path ->
                        Box(Modifier.size(96.dp)) {
                            ClosieImageTile(
                                model = File(path),
                                contentDescription = "试穿照片",
                                modifier = Modifier.fillMaxSize()
                            )
                            IconButton(
                                onClick = { tryOnImages = tryOnImages.filterNot { it == path } },
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .size(24.dp)
                            ) {
                                Icon(Icons.Default.Delete, contentDescription = "删除", tint = ClosieColor.Error)
                            }
                        }
                    }
                    item {
                        Surface(
                            modifier = Modifier
                                .size(96.dp)
                                .clickable { tryOnPicker.launch(arrayOf("image/*")) },
                            shape = MaterialTheme.shapes.large,
                            color = ClosieColor.SurfaceSoft,
                            border = androidx.compose.foundation.BorderStroke(1.dp, ClosieColor.Hairline)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.Add, contentDescription = "添加试穿照片", tint = ClosieColor.InkTertiary)
                            }
                        }
                    }
                }
            }
        }
    }

    if (showDiscardDialog) {
        AlertDialog(
            onDismissRequest = { showDiscardDialog = false },
            title = { Text("还有未保存的修改") },
            text = { Text("确定离开吗？未保存的修改和刚添加的照片会被丢弃。") },
            confirmButton = { TextButton(onClick = { discardCleanup(); back() }) { Text("放弃修改", color = ClosieColor.Error) } },
            dismissButton = { TextButton(onClick = { showDiscardDialog = false }) { Text("继续编辑") } }
        )
    }
}
