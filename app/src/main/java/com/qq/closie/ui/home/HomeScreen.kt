package com.qq.closie.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.qq.closie.data.model.ClothingImage
import com.qq.closie.data.model.ClothingItem
import com.qq.closie.data.model.ImageKind
import com.qq.closie.data.model.ItemStatus
import com.qq.closie.data.model.Ootd
import com.qq.closie.data.repository.WardrobeRepository
import com.qq.closie.ui.LocalWardrobeSnapshot
import com.qq.closie.ui.components.ClosieImageTile
import com.qq.closie.ui.components.ClosieSectionHeader
import com.qq.closie.ui.theme.ClosieColor
import com.qq.closie.ui.theme.rememberClosieDimensions
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private fun todayDateFormatter() = DateTimeFormatter.ofPattern("EEEE · MMM d")

@Composable
fun HomeScreen(
    repo: WardrobeRepository,
    onSettings: () -> Unit,
    onOpenCloset: () -> Unit,
    onOpenOotd: () -> Unit
) {
    // One shared generation. 首页 reads items + ootds + wear together (today's outfit is looked up
    // against today's wears and the owned items), so three separate flows could show a mix of two
    // generations. Collected once at the top of the navigation graph — see LocalWardrobeSnapshot.
    val wardrobe = LocalWardrobeSnapshot.current
    val items = wardrobe.items
    val ootds = wardrobe.ootds
    val wears = wardrobe.wearEvents
    val dims = rememberClosieDimensions()

    val owned = items.filter { it.status == ItemStatus.OWNED }
    val today = LocalDate.now()
    val thisMonth = today.toString().substring(0, 7)
    val ownedIds = owned.map { it.id }.toSet()
    val ownedWears = wears.filter { it.itemId in ownedIds }
    val monthWears = ownedWears.filter { it.date.startsWith(thisMonth) }
    val todayOotd = ootds.filter { it.date == today.toString() }

    val recentItems = ownedWears
        .sortedByDescending { it.date }
        .map { it.itemId }
        .distinct()
        .take(8)
        .mapNotNull { id -> owned.find { it.id == id } }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = dims.pageHorizontal, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(dims.sectionGap)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Closie", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold, color = ClosieColor.Ink)
                    Text("今天想穿什么？", style = MaterialTheme.typography.bodyLarge, color = ClosieColor.Graphite)
                }
                IconButton(onClick = onSettings, modifier = Modifier.size(44.dp)) {
                    Icon(Icons.Outlined.Settings, contentDescription = "设置", tint = ClosieColor.Ink)
                }
            }
        }

        item {
            TodaySection(todayOotd = todayOotd, owned = owned, onOpenOotd = onOpenOotd)
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ClosieSectionHeader(title = "最近穿过", actionLabel = "查看衣橱", onAction = onOpenCloset)
                if (recentItems.isEmpty()) {
                    Text("还没有穿着记录", style = MaterialTheme.typography.bodyMedium, color = ClosieColor.Stone)
                } else {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(recentItems, key = { it.id }) { item ->
                            val image = item.bestImage()
                            Column(
                                modifier = Modifier.width(76.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                ClosieImageTile(
                                    model = image?.localPath?.let { File(it) },
                                    contentDescription = item.name,
                                    modifier = Modifier.size(76.dp),
                                    contentScale = if (image?.kind == ImageKind.FLAT) ContentScale.Fit else ContentScale.Crop,
                                    placeholder = {
                                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                            Text(item.name.take(1), color = ClosieColor.Stone)
                                        }
                                    }
                                )
                                Text(item.name, style = MaterialTheme.typography.bodySmall, color = ClosieColor.Ink, maxLines = 1)
                            }
                        }
                    }
                }
            }
        }

        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenCloset)
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("衣橱", style = MaterialTheme.typography.titleMedium, color = ClosieColor.Ink)
                    Text(
                        "${owned.size} 件 · 本月穿过 ${monthWears.size} 次",
                        style = MaterialTheme.typography.bodyMedium,
                        color = ClosieColor.Graphite
                    )
                }
                Text("›", style = MaterialTheme.typography.titleMedium, color = ClosieColor.Stone)
            }
        }
    }
}

@Composable
private fun TodaySection(
    todayOotd: List<Ootd>,
    owned: List<ClothingItem>,
    onOpenOotd: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("今天", style = MaterialTheme.typography.titleMedium, color = ClosieColor.Ink)

        val ootd = todayOotd.firstOrNull()
        if (ootd == null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1.5f)
                    .background(ClosieColor.Mist, MaterialTheme.shapes.large),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("还没有记录今天的穿搭", style = MaterialTheme.typography.bodyLarge, color = ClosieColor.Graphite)
                    TextButton(onClick = onOpenOotd) { Text("记录今天", color = ClosieColor.Fig) }
                }
            }
        } else {
            val firstItemImage = ootd.itemIds.mapNotNull { id -> owned.find { it.id == id }?.bestImage() }.firstOrNull()
            val heroImagePath = ootd.images.firstOrNull() ?: firstItemImage?.localPath
            if (heroImagePath != null) {
                val heroScale = if (ootd.images.isEmpty() && firstItemImage?.kind == ImageKind.FLAT) ContentScale.Fit else ContentScale.Crop
                ClosieImageTile(
                    model = File(heroImagePath),
                    contentDescription = "今日 OOTD",
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(0.85f),
                    contentScale = heroScale
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(0.85f)
                        .background(ClosieColor.Mist, MaterialTheme.shapes.large),
                    contentAlignment = Alignment.Center
                ) {
                    Text("今天穿了什么？", style = MaterialTheme.typography.bodyMedium, color = ClosieColor.Stone)
                }
            }
            Text(
                LocalDate.now().format(todayDateFormatter()),
                style = MaterialTheme.typography.bodyMedium,
                color = ClosieColor.Graphite
            )
            val names = ootd.itemIds.mapNotNull { id -> owned.find { it.id == id }?.name }
            if (names.isNotEmpty()) {
                Text(
                    names.joinToString(" / "),
                    style = MaterialTheme.typography.bodyLarge,
                    color = ClosieColor.Ink,
                    maxLines = 2
                )
            }
            if (ootd.note.isNotBlank()) {
                Text(ootd.note, style = MaterialTheme.typography.bodyMedium, color = ClosieColor.Graphite)
            }
            if (todayOotd.size > 1) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onOpenOotd)
                        .heightIn(min = 44.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("今天还有 ${todayOotd.size - 1} 套穿搭", style = MaterialTheme.typography.bodyMedium, color = ClosieColor.Fig)
                    Text("›", style = MaterialTheme.typography.bodyMedium, color = ClosieColor.Stone)
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
