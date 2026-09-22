package com.xiaoming.closie.ui.outfit

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.xiaoming.closie.data.model.ImageKind
import com.xiaoming.closie.data.model.Outfit
import com.xiaoming.closie.data.repository.WardrobeRepository
import com.xiaoming.closie.ui.BackButton
import com.xiaoming.closie.ui.Rose
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OutfitListScreen(repo: WardrobeRepository, open: (String) -> Unit, create: () -> Unit, back: () -> Unit) {
    val outfits by repo.outfits.collectAsState()
    val items by repo.items.collectAsState()
    var pendingDelete by remember { mutableStateOf<Outfit?>(null) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("搭配室") }, navigationIcon = { BackButton(back) }) },
        floatingActionButton = { FloatingActionButton(onClick = create, containerColor = Rose) { Text("+") } }
    ) { pad ->
        if (outfits.isEmpty()) {
            Column(
                Modifier.padding(pad).fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("还没有搭配", style = MaterialTheme.typography.titleMedium)
                Text("点右下角 + 开始你的第一套搭配", color = Rose)
            }
        } else {
            LazyColumn(Modifier.padding(pad).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(outfits, key = { it.id }) { outfit ->
                    val itemSet = items.filter { it.id in outfit.itemIds }
                    Card(Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f).clickable { open(outfit.id) }) {
                                Text(outfit.name.ifBlank { "未命名搭配" }, style = MaterialTheme.typography.titleMedium)
                                Text("${outfit.itemIds.size} 件单品", color = Rose, style = MaterialTheme.typography.bodySmall)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    itemSet.take(5).forEach { itt ->
                                        val thumb = itt.images.firstOrNull { it.kind == ImageKind.FLAT } ?: itt.images.firstOrNull()
                                        if (thumb?.localPath != null) {
                                            AsyncImage(
                                                File(thumb.localPath),
                                                itt.name,
                                                Modifier.size(56.dp),
                                                contentScale = if (thumb.kind == ImageKind.FLAT) ContentScale.Fit else ContentScale.Crop
                                            )
                                        } else {
                                            Box(Modifier.size(56.dp).background(Color(0xFFF3E5E7)), contentAlignment = Alignment.Center) {
                                                Text(itt.name.take(1), color = Rose, style = MaterialTheme.typography.bodySmall)
                                            }
                                        }
                                    }
                                }
                            }
                            TextButton(onClick = { pendingDelete = outfit }) { Text("删除", color = Rose) }
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
            confirmButton = { TextButton(onClick = { repo.deleteOutfit(target.id); pendingDelete = null }) { Text("删除") } },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("取消") } }
        )
    }
}
