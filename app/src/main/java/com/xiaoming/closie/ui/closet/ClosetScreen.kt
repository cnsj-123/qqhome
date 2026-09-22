package com.xiaoming.closie.ui.closet

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items as listItems
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.xiaoming.closie.data.model.*
import com.xiaoming.closie.data.repository.WardrobeRepository
import com.xiaoming.closie.ui.*
import java.io.File

private enum class SortOption(val label: String) {
    RECENT("最近编辑"), NAME("名称"), PURCHASE_DATE("最近购买"),
    PRICE_ASC("价格低→高"), PRICE_DESC("价格高→低"),
    WEAR_DESC("穿着多→少"), WEAR_ASC("穿着少→多")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClosetScreen(
    repo: WardrobeRepository,
    status: ItemStatus,
    open: (String) -> Unit,
    add: () -> Unit,
    back: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("") }
    var sort by remember { mutableStateOf(SortOption.RECENT) }
    var onlyUnworn by remember { mutableStateOf(false) }
    var sortMenu by remember { mutableStateOf(false) }
    val all by repo.items.collectAsState()
    val wears by repo.wearEvents.collectAsState()

    val statusItems = all.filter { it.status == status }
    val categories = statusItems.map { it.category }.filter { it.isNotBlank() }.distinct()
    val wearCount = remember(wears) { wears.groupingBy { it.itemId }.eachCount() }

    val q = query.trim()
    val filtered = statusItems.filter { item ->
        val matchesQuery = q.isEmpty() || listOf(
            item.name, item.category, item.subcategory, item.brand, item.store, item.purchasePlatform
        ).any { it.contains(q, ignoreCase = true) }
        val matchesCategory = category.isBlank() || item.category == category
        val matchesUnworn = !onlyUnworn || (wearCount[item.id] ?: 0) == 0
        matchesQuery && matchesCategory && matchesUnworn
    }

    val visibleItems = when (sort) {
        SortOption.RECENT -> filtered.sortedByDescending { it.updatedAt }
        SortOption.NAME -> filtered.sortedBy { it.name }
        SortOption.PURCHASE_DATE -> filtered.sortedByDescending { it.purchaseDate }
        SortOption.PRICE_ASC -> filtered.sortedWith(compareBy({ it.price ?: Double.MAX_VALUE }, { it.name }))
        SortOption.PRICE_DESC -> filtered.sortedWith(compareByDescending<ClothingItem> { it.price ?: -1.0 }.thenBy { it.name })
        SortOption.WEAR_DESC -> filtered.sortedByDescending { wearCount[it.id] ?: 0 }
        SortOption.WEAR_ASC -> filtered.sortedBy { wearCount[it.id] ?: 0 }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (status == ItemStatus.OWNED) "我的衣橱" else "试过 / 退货") },
                navigationIcon = { BackButton(back) }
            )
        },
        floatingActionButton = { FloatingActionButton(onClick = add, containerColor = Rose) { Text("+") } }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().padding(12.dp)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("搜索名称 / 类别 / 品牌 / 店铺 / 平台") },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box {
                    OutlinedButton(onClick = { sortMenu = true }) { Text("排序：${sort.label}") }
                    DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                        SortOption.entries.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option.label) },
                                onClick = { sort = option; sortMenu = false }
                            )
                        }
                    }
                }
                if (status == ItemStatus.OWNED) {
                    FilterChip(
                        selected = onlyUnworn,
                        onClick = { onlyUnworn = !onlyUnworn },
                        label = { Text("只看未穿过") }
                    )
                }
            }
            Spacer(Modifier.height(8.dp))

            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    FilterChip(selected = category.isBlank(), onClick = { category = "" }, label = { Text("全部") })
                }
                listItems(categories) { c ->
                    FilterChip(selected = category == c, onClick = { category = c }, label = { Text(c) })
                }
            }
            Spacer(Modifier.height(8.dp))

            if (visibleItems.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (statusItems.isEmpty()) {
                            Text(
                                if (status == ItemStatus.OWNED) "还没有衣服，点 + 添加第一件吧" else "还没有试过 / 退货记录",
                                color = Rose
                            )
                        } else {
                            Text("没有符合当前搜索或筛选条件的衣服", color = Rose)
                            OutlinedButton(onClick = {
                                query = ""
                                category = ""
                                onlyUnworn = false
                                sort = SortOption.RECENT
                            }) { Text("清除筛选") }
                        }
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(visibleItems, key = { it.id }) { item ->
                        val image = item.images.firstOrNull { it.kind == ImageKind.FLAT } ?: item.images.firstOrNull()
                        Card(modifier = Modifier.clickable { open(item.id) }) {
                            Column {
                                if (image?.localPath != null) {
                                    AsyncImage(
                                        model = File(image.localPath),
                                        contentDescription = item.name,
                                        modifier = Modifier.fillMaxWidth().height(150.dp),
                                        contentScale = if (image.kind == ImageKind.FLAT) ContentScale.Fit else ContentScale.Crop
                                    )
                                } else {
                                    Box(
                                        Modifier.fillMaxWidth().height(150.dp).background(Color(0xFFF3E5E7)),
                                        contentAlignment = Alignment.Center
                                    ) { Text(item.name, color = Rose, style = MaterialTheme.typography.bodySmall) }
                                }
                                Text(item.name, Modifier.padding(horizontal = 10.dp, vertical = 6.dp), maxLines = 1)
                                Text(item.category, Modifier.padding(horizontal = 10.dp), style = MaterialTheme.typography.bodySmall, color = Rose)
                                if (item.brand.isNotBlank()) {
                                    Text(item.brand, Modifier.padding(horizontal = 10.dp), style = MaterialTheme.typography.bodySmall)
                                }
                                item.price?.let { Text(priceText(it), Modifier.padding(horizontal = 10.dp), style = MaterialTheme.typography.bodySmall) }
                                if (status == ItemStatus.OWNED) {
                                    val wc = wearCount[item.id] ?: 0
                                    Text(
                                        if (wc > 0) "穿过 $wc 次" else "还没穿过",
                                        Modifier.padding(10.dp),
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun priceText(p: Double): String =
    "¥" + if (p == p.toLong().toDouble()) p.toLong().toString() else p.toString()
