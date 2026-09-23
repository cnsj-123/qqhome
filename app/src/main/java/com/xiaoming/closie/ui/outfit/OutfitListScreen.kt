package com.xiaoming.closie.ui.outfit

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.xiaoming.closie.data.ImageStore
import com.xiaoming.closie.data.draft.DraftImageCleanup
import com.xiaoming.closie.data.draft.DraftStore
import com.xiaoming.closie.data.model.ClothingItem
import com.xiaoming.closie.data.model.ImageKind
import com.xiaoming.closie.data.model.Outfit
import com.xiaoming.closie.data.repository.WardrobeRepository
import com.xiaoming.closie.ui.components.ClosieCompactTopBar
import com.xiaoming.closie.ui.components.ClosieEmptyState
import com.xiaoming.closie.ui.components.ClosieImageTile
import com.xiaoming.closie.ui.theme.ClosieColor
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OutfitListScreen(
    repo: WardrobeRepository,
    open: (String) -> Unit,
    openDraft: (String) -> Unit,
    create: () -> Unit,
    back: () -> Unit
) {
    val context = LocalContext.current
    val outfits by repo.outfits.collectAsState()
    val items by repo.items.collectAsState()
    val draftStore = remember { DraftStore(context) }
    var pendingDelete by remember { mutableStateOf<Outfit?>(null) }
    var drafts by remember { mutableStateOf(draftStore.listOutfitDrafts()) }
    var showDrafts by remember { mutableStateOf(false) }

    fun refreshDrafts() { drafts = draftStore.listOutfitDrafts() }

    fun cleanupDraftImages(removed: Collection<String>) {
        val live = repo.items.value.flatMap { it.images }.mapNotNull { it.localPath } +
            repo.ootds.value.flatMap { it.images } +
            repo.outfits.value.flatMap { it.tryOnImages }
        val remaining = draftStore.listOotdDrafts().flatMap { it.images } +
            draftStore.listOutfitDrafts().flatMap { it.tryOnImages }
        DraftImageCleanup.cleanupOrphans(removed, live, remaining) { ImageStore.deletePrivatePath(context, it) }
    }

    Scaffold(
        containerColor = ClosieColor.Canvas,
        topBar = { ClosieCompactTopBar(title = "搭配", onBack = back) },
        floatingActionButton = {
            FloatingActionButton(
                onClick = create,
                containerColor = ClosieColor.Fig,
                contentColor = ClosieColor.Surface,
                shape = MaterialTheme.shapes.extraLarge
            ) { Icon(Icons.Default.Add, contentDescription = "新建搭配") }
        }
    ) { pad ->
        if (outfits.isEmpty() && drafts.isEmpty()) {
            Box(Modifier.padding(pad).fillMaxSize(), contentAlignment = Alignment.Center) {
                ClosieEmptyState(
                    title = "还没有搭配",
                    subtitle = "点右下角 + 开始你的第一套搭配。",
                    actionLabel = "新建搭配",
                    onAction = create
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.padding(pad).padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                if (drafts.isNotEmpty()) {
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showDrafts = true }
                                .heightIn(min = 48.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("草稿", style = MaterialTheme.typography.titleMedium, color = ClosieColor.Ink)
                            Text("草稿 ${drafts.size}", style = MaterialTheme.typography.bodyMedium, color = ClosieColor.Fig)
                        }
                    }
                }
                items(outfits, key = { it.id }) { outfit ->
                    OutfitCard(
                        outfit = outfit,
                        items = items,
                        onOpen = { open(outfit.id) },
                        onDelete = { pendingDelete = outfit }
                    )
                }
            }
        }
    }

    if (showDrafts) {
        ModalBottomSheet(onDismissRequest = { showDrafts = false }, containerColor = ClosieColor.Surface) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 32.dp)) {
                Text("搭配草稿", style = MaterialTheme.typography.titleLarge, color = ClosieColor.Ink)
                Spacer(Modifier.height(8.dp))
                if (drafts.isEmpty()) {
                    Text("还没有草稿", style = MaterialTheme.typography.bodyMedium, color = ClosieColor.InkTertiary)
                } else {
                    drafts.forEach { d ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { showDrafts = false; openDraft(d.id) }
                                .heightIn(min = 48.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                d.name.ifBlank { "未命名搭配" } + " · ${d.itemIds.size} 件",
                                style = MaterialTheme.typography.bodyLarge,
                                color = ClosieColor.Ink,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = {
                                val removed = draftStore.deleteOutfitDraft(d.id)
                                removed?.tryOnImages?.let { cleanupDraftImages(it) }
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

    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除这套搭配？") },
            text = { Text("删除后无法恢复。") },
            confirmButton = { TextButton(onClick = { repo.deleteOutfit(target.id); pendingDelete = null }) { Text("删除", color = ClosieColor.Error) } },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("取消") } }
        )
    }
}

@Composable
private fun OutfitCard(outfit: Outfit, items: List<ClothingItem>, onOpen: () -> Unit, onDelete: () -> Unit) {
    val itemSet = items.filter { it.id in outfit.itemIds }
    val cover = outfit.tryOnImages.firstOrNull()?.let { File(it) }
        ?: itemSet.firstOrNull()?.let { it.images.firstOrNull { img -> img.kind == ImageKind.FLAT } ?: it.images.firstOrNull() }?.localPath?.let { File(it) }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen),
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
                    contentDescription = outfit.name,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1.1f)
                )
            }
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(outfit.name.ifBlank { "未命名搭配" }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("${outfit.itemIds.size} 件单品", style = MaterialTheme.typography.bodyMedium, color = ClosieColor.InkSecondary)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(itemSet) { itt ->
                            val thumb = itt.images.firstOrNull { it.kind == ImageKind.FLAT } ?: itt.images.firstOrNull()
                            ClosieImageTile(
                                model = thumb?.localPath?.let { File(it) },
                                contentDescription = itt.name,
                                modifier = Modifier.size(48.dp),
                                contentScale = if (thumb?.kind == ImageKind.FLAT) ContentScale.Fit else ContentScale.Crop,
                                placeholder = {
                                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                        Text(itt.name.take(1), color = ClosieColor.InkTertiary, style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            )
                        }
                    }
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = "删除", tint = ClosieColor.Error)
                }
            }
        }
    }
}
