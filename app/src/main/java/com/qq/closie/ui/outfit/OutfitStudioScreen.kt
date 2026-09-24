package com.qq.closie.ui.outfit

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
import com.qq.closie.data.ImageStore
import com.qq.closie.data.draft.DraftImageCleanup
import com.qq.closie.data.draft.DraftStore
import com.qq.closie.data.model.ImageKind
import com.qq.closie.data.model.ItemStatus
import com.qq.closie.data.model.Outfit
import com.qq.closie.data.model.Placement
import com.qq.closie.data.repository.WardrobeRepository
import com.qq.closie.ui.components.ClosieCompactTopBar
import com.qq.closie.ui.components.ClosieImageTile
import com.qq.closie.ui.theme.ClosieColor
import java.io.File
import java.util.UUID
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OutfitStudioScreen(repo: WardrobeRepository, outfitId: String?, draftId: String? = null, back: () -> Unit) {
    val context = LocalContext.current
    val items by repo.items.collectAsState()
    val outfits by repo.outfits.collectAsState()
    val draftStore = remember { DraftStore(context) }
    val draft = remember(draftId) { draftId?.let { id -> draftStore.listOutfitDrafts().firstOrNull { it.id == id } } }
    val original = outfits.firstOrNull { it.id == outfitId } ?: draft

    var name by remember(outfitId, draftId) { mutableStateOf(original?.name.orEmpty()) }
    var note by remember(outfitId, draftId) { mutableStateOf(original?.note.orEmpty()) }
    var placements by remember(outfitId, draftId) { mutableStateOf(original?.placements ?: emptyList()) }
    var tryOnImages by remember(outfitId, draftId) { mutableStateOf(original?.tryOnImages ?: emptyList()) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var showDiscardDialog by remember { mutableStateOf(false) }
    var dockTab by remember { mutableStateOf(0) }

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

    fun cleanupKeptImages(kept: Set<String>) {
        pendingTryOn.filterNot { it in kept }.forEach { ImageStore.deletePrivatePath(context, it) }
        pendingTryOn.clear()
    }

    fun discardCleanup() {
        pendingTryOn.forEach { ImageStore.deletePrivatePath(context, it) }
        pendingTryOn.clear()
    }

    fun cleanupDraftImages(removed: Collection<String>) {
        val live = repo.items.value.flatMap { it.images }.mapNotNull { it.localPath } +
            repo.ootds.value.flatMap { it.images } +
            repo.outfits.value.flatMap { it.tryOnImages }
        val remaining = draftStore.listOotdDrafts().flatMap { it.images } +
            draftStore.listOutfitDrafts().flatMap { it.tryOnImages }
        DraftImageCleanup.cleanupOrphans(removed, live, remaining) { ImageStore.deletePrivatePath(context, it) }
    }

    fun saveAsDraft() {
        // Reuse the original entity id when editing an existing live Outfit, so restoring the
        // draft and saving updates the original instead of duplicating it. Look up the previous
        // draft by the SAME target id regardless of the entry path.
        val targetId = draftId ?: original?.id ?: UUID.randomUUID().toString()
        val previousDraft = draftStore.listOutfitDrafts().firstOrNull { it.id == targetId }
        val draftOutfit = Outfit(
            id = targetId,
            name = name, note = note,
            itemIds = placements.map { it.itemId },
            placements = placements,
            tryOnImages = tryOnImages
        )
        draftStore.saveOutfitDraft(draftOutfit)
        cleanupDraftImages((previousDraft?.tryOnImages.orEmpty()) - tryOnImages.toSet())
        cleanupKeptImages(tryOnImages.toSet())
        back()
    }

    fun save() {
        val targetDraftId = draftId
        val normalized = placements
            .map { it.copy(x = it.x.coerceIn(0f, 1f), y = it.y.coerceIn(0f, 1f)) }
            .sortedBy { it.zIndex }
            .mapIndexed { index, p -> p.copy(zIndex = index) }
        repo.saveOutfit((original ?: Outfit(name = name)).copy(name = name, note = note, itemIds = normalized.map { it.itemId }, placements = normalized, tryOnImages = tryOnImages))
        val removedDraft = targetDraftId?.let { draftStore.deleteOutfitDraft(it) }
        removedDraft?.let { cleanupDraftImages(it.tryOnImages - tryOnImages.toSet()) }
        cleanupKeptImages(tryOnImages.toSet())
        back()
    }

    fun requestBack() {
        if (dirty) showDiscardDialog = true else { discardCleanup(); back() }
    }
    BackHandler(enabled = dirty) { showDiscardDialog = true }

    Scaffold(
        containerColor = ClosieColor.Canvas,
        topBar = {
            ClosieCompactTopBar(
                title = if (original == null && draftId == null) "新建搭配" else "编辑搭配",
                onBack = { requestBack() },
                actions = { TextButton(onClick = { save() }) { Text("保存", color = ClosieColor.Fig) } }
            )
        },
        bottomBar = {
            Surface(
                modifier = Modifier.fillMaxWidth().navigationBarsPadding(),
                color = ClosieColor.Surface,
                tonalElevation = 0.dp,
                shadowElevation = 0.dp,
                border = androidx.compose.foundation.BorderStroke(1.dp, ClosieColor.Hairline)
            ) {
                val selectedPlacement = selectedId?.let { id -> placements.firstOrNull { it.itemId == id } }
                if (selectedPlacement != null) {
                    SelectedItemDock(
                        placement = selectedPlacement,
                        onScale = { s -> placements = placements.map { if (it.itemId == selectedPlacement.itemId) it.copy(scale = s) else it } },
                        onFront = { placements = placements.map { if (it.itemId == selectedPlacement.itemId) it.copy(zIndex = it.zIndex + 1) else it } },
                        onBack = { placements = placements.map { if (it.itemId == selectedPlacement.itemId) it.copy(zIndex = it.zIndex - 1) else it } },
                        onRemove = { placements = placements.filterNot { it.itemId == selectedPlacement.itemId }; selectedId = null }
                    )
                } else {
                    ItemPickerDock(
                        owned = owned,
                        placements = placements,
                        tryOnImages = tryOnImages,
                        dockTab = dockTab,
                        onDockTab = { dockTab = it },
                        onAddItem = { item ->
                            val z = (placements.maxOfOrNull { it.zIndex } ?: 0) + 1
                            placements = placements + Placement(item.id, zIndex = z)
                            selectedId = item.id
                        },
                        onRemoveTryOn = { path -> tryOnImages = tryOnImages.filterNot { it == path } },
                        onAddTryOn = { tryOnPicker.launch(arrayOf("image/*")) }
                    )
                }
            }
        }
    ) { pad ->
        Column(
            modifier = Modifier
                .padding(pad)
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("搭配名称") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = MaterialTheme.shapes.large
            )

            // Canvas — takes the remaining vertical space (~55–65% of the screen).
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
                    val isFlat = image?.kind == ImageKind.FLAT
                    val selected = selectedId == p.itemId
                    val itemSizePx = with(density) { (150.dp * p.scale).roundToPx() }
                    val px = (p.x * canvasSize.width - itemSizePx / 2f).roundToInt()
                    val py = (p.y * canvasSize.height - itemSizePx / 2f).roundToInt()
                    val shape = RoundedCornerShape(12.dp)

                    Box(
                        modifier = Modifier
                            .offset { IntOffset(px, py) }
                            .size(150.dp * p.scale)
                            .zIndex(p.zIndex.toFloat())
                            .then(if (isFlat) Modifier else Modifier.clip(shape).background(ClosieColor.Surface))
                            .then(if (selected) Modifier.border(1.5.dp, ClosieColor.Fig.copy(alpha = 0.4f), shape) else Modifier)
                            .pointerInput(p.itemId) {
                                detectTransformGestures { _, pan, zoom, _ ->
                                    selectedId = p.itemId
                                    val w = canvasSize.width.coerceAtLeast(1)
                                    val h = canvasSize.height.coerceAtLeast(1)
                                    placements = placements.map {
                                        if (it.itemId == p.itemId) it.copy(
                                            x = (it.x + pan.x / w).coerceIn(0.02f, 0.98f),
                                            y = (it.y + pan.y / h).coerceIn(0.02f, 0.98f),
                                            scale = (it.scale * zoom).coerceIn(0.4f, 2.5f)
                                        ) else it
                                    }
                                }
                            }
                            .clickable { selectedId = p.itemId }
                    ) {
                        if (image?.localPath != null) {
                            AsyncImage(
                                File(image.localPath), item.name, Modifier.fillMaxSize(),
                                contentScale = if (isFlat) ContentScale.Fit else ContentScale.Crop
                            )
                        } else {
                            Box(Modifier.fillMaxSize().background(ClosieColor.Surface), contentAlignment = Alignment.Center) {
                                Text(item.name, Modifier.padding(4.dp), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                if (placements.isEmpty()) {
                    Text("从下方衣橱选择单品添加到画布", Modifier.align(Alignment.Center), color = ClosieColor.InkTertiary)
                }
            }
        }
    }

    if (showDiscardDialog) {
        AlertDialog(
            onDismissRequest = { showDiscardDialog = false },
            title = { Text("还有未保存的修改") },
            text = { Text("要保存为草稿，还是放弃这次修改？") },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    TextButton(onClick = { showDiscardDialog = false; saveAsDraft() }) { Text("保存草稿", color = ClosieColor.Fig) }
                    TextButton(onClick = { showDiscardDialog = false; discardCleanup(); back() }) { Text("放弃修改", color = ClosieColor.Error) }
                    TextButton(onClick = { showDiscardDialog = false }) { Text("继续编辑") }
                }
            }
        )
    }
}

@Composable
private fun SelectedItemDock(
    placement: Placement,
    onScale: (Float) -> Unit,
    onFront: () -> Unit,
    onBack: () -> Unit,
    onRemove: () -> Unit
) {
    Column(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("缩放", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(40.dp))
            Slider(
                value = placement.scale,
                onValueChange = onScale,
                valueRange = 0.4f..2.5f,
                modifier = Modifier.weight(1f)
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onFront, modifier = Modifier.weight(1f)) { Text("前移") }
            OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f)) { Text("后移") }
            OutlinedButton(onClick = onRemove, modifier = Modifier.weight(1f)) { Text("移除") }
        }
    }
}

@Composable
private fun ItemPickerDock(
    owned: List<com.qq.closie.data.model.ClothingItem>,
    placements: List<Placement>,
    tryOnImages: List<String>,
    dockTab: Int,
    onDockTab: (Int) -> Unit,
    onAddItem: (com.qq.closie.data.model.ClothingItem) -> Unit,
    onRemoveTryOn: (String) -> Unit,
    onAddTryOn: () -> Unit
) {
    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                "衣橱单品",
                style = MaterialTheme.typography.titleSmall,
                color = if (dockTab == 0) ClosieColor.Ink else ClosieColor.Stone,
                modifier = Modifier.clickable { onDockTab(0) }
            )
            Text(
                "试穿照片",
                style = MaterialTheme.typography.titleSmall,
                color = if (dockTab == 1) ClosieColor.Ink else ClosieColor.Stone,
                modifier = Modifier.clickable { onDockTab(1) }
            )
        }
        Spacer(Modifier.height(8.dp))
        if (dockTab == 0) {
            LazyRow(
                modifier = Modifier.padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(owned, key = { it.id }) { item ->
                    val added = placements.any { it.itemId == item.id }
                    val image = item.images.firstOrNull { it.kind == ImageKind.FLAT } ?: item.images.firstOrNull()
                    Column(
                        modifier = Modifier
                            .width(72.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable(enabled = !added) { onAddItem(item) }
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
                        Text(item.name, Modifier.padding(horizontal = 4.dp), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        } else {
            LazyRow(
                modifier = Modifier.padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(tryOnImages, key = { it }) { path ->
                    Box(Modifier.size(96.dp)) {
                        ClosieImageTile(model = File(path), contentDescription = "试穿照片", modifier = Modifier.fillMaxSize())
                        IconButton(
                            onClick = { onRemoveTryOn(path) },
                            modifier = Modifier.align(Alignment.TopEnd).size(24.dp)
                        ) { Icon(Icons.Default.Delete, contentDescription = "删除", tint = ClosieColor.Error) }
                    }
                }
                item {
                    Surface(
                        modifier = Modifier.size(96.dp).clickable(onClick = onAddTryOn),
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
