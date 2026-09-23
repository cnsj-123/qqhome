package com.xiaoming.closie.ui.home

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.xiaoming.closie.data.model.ClothingImage
import com.xiaoming.closie.data.model.ClothingItem
import com.xiaoming.closie.data.model.ImageKind
import com.xiaoming.closie.data.model.ItemStatus
import com.xiaoming.closie.data.model.Ootd
import com.xiaoming.closie.data.repository.WardrobeRepository
import com.xiaoming.closie.ui.components.ClosieImageTile
import com.xiaoming.closie.ui.components.ClosieSectionHeader
import com.xiaoming.closie.ui.theme.ClosieColor
import java.io.File
import java.time.LocalDate

@Composable
fun HomeScreen(
    repo: WardrobeRepository,
    onSettings: () -> Unit,
    onOpenCloset: () -> Unit,
    onOpenOotd: () -> Unit
) {
    val items by repo.items.collectAsState()
    val ootds by repo.ootds.collectAsState()
    val wears by repo.wearEvents.collectAsState()

    val owned = items.filter { it.status == ItemStatus.OWNED }
    val today = LocalDate.now().toString()
    val thisMonth = today.substring(0, 7)
    val ownedIds = owned.map { it.id }.toSet()
    val ownedWears = wears.filter { it.itemId in ownedIds }
    val monthWears = ownedWears.filter { it.date.startsWith(thisMonth) }
    val todayOotd = ootds.filter { it.date == today }

    val recentItemIds = ownedWears
        .sortedByDescending { it.date }
        .map { it.itemId }
        .distinct()
        .take(8)
    val recentItems = recentItemIds.mapNotNull { id -> owned.find { it.id == id } }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(28.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Closie", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.SemiBold)
                    Text("今天想穿什么？", style = MaterialTheme.typography.bodyLarge, color = ClosieColor.InkSecondary)
                }
                IconButton(onClick = onSettings, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Outlined.Settings, contentDescription = "设置", tint = ClosieColor.Ink)
                }
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("我的衣橱", style = MaterialTheme.typography.titleMedium, color = ClosieColor.InkSecondary)
                Text("${owned.size} 件", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "这个月穿过 ${monthWears.size} 次 · ${monthWears.map { it.itemId }.distinct().size} 件不同衣服",
                    style = MaterialTheme.typography.bodyMedium,
                    color = ClosieColor.InkSecondary
                )
            }
        }

        item {
            TodaySection(
                todayOotd = todayOotd,
                owned = owned,
                onRecordToday = onOpenOotd
            )
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ClosieSectionHeader(title = "最近穿过", actionLabel = "查看衣橱", onAction = onOpenCloset)
                if (recentItems.isEmpty()) {
                    Text("还没有穿着记录", style = MaterialTheme.typography.bodyMedium, color = ClosieColor.InkTertiary)
                } else {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(recentItems.size) { index ->
                            val item = recentItems[index]
                            val image = item.bestImage()
                            Column(
                                modifier = Modifier.width(84.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                ClosieImageTile(
                                    model = image?.localPath?.let { File(it) },
                                    contentDescription = item.name,
                                    modifier = Modifier.size(84.dp),
                                    contentScale = if (image?.kind == ImageKind.FLAT) ContentScale.Fit else ContentScale.Crop,
                                    placeholder = {
                                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                            Text(item.name.take(1), color = ClosieColor.InkTertiary)
                                        }
                                    }
                                )
                                Text(item.name, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TodaySection(
    todayOotd: List<Ootd>,
    owned: List<ClothingItem>,
    onRecordToday: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("今天", style = MaterialTheme.typography.titleMedium)
        if (todayOotd.isEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("还没有记录今天的穿搭", style = MaterialTheme.typography.bodyLarge, color = ClosieColor.InkSecondary)
                TextButton(onClick = onRecordToday) { Text("记录今天", color = ClosieColor.Rose) }
            }
        } else {
            todayOotd.forEach { ootd ->
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                    color = ClosieColor.Surface,
                    tonalElevation = 0.dp,
                    shadowElevation = 0.dp,
                    border = androidx.compose.foundation.BorderStroke(1.dp, ClosieColor.Hairline)
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (ootd.images.isNotEmpty()) {
                            ClosieImageTile(
                                model = File(ootd.images.first()),
                                contentDescription = "OOTD 照片",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(200.dp)
                            )
                        }
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(ootd.itemIds.size) { index ->
                                val item = owned.find { it.id == ootd.itemIds[index] }
                                val img = item?.bestImage()
                                ClosieImageTile(
                                    model = img?.localPath?.let { File(it) },
                                    contentDescription = item?.name ?: "单品",
                                    modifier = Modifier.size(56.dp),
                                    contentScale = if (img?.kind == ImageKind.FLAT) ContentScale.Fit else ContentScale.Crop,
                                    placeholder = {
                                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                            Text(item?.name?.take(1) ?: "?", color = ClosieColor.InkTertiary)
                                        }
                                    }
                                )
                            }
                        }
                        if (ootd.note.isNotBlank()) {
                            Text(ootd.note, style = MaterialTheme.typography.bodyMedium, color = ClosieColor.InkSecondary)
                        }
                    }
                }
            }
        }
    }
}

private fun ClothingItem.bestImage(): ClothingImage? {
    return images.firstOrNull { it.kind == ImageKind.FLAT }
        ?: images.firstOrNull { it.kind == ImageKind.PRODUCT }
        ?: images.firstOrNull()
}
