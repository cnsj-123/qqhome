package com.xiaoming.closie.ui.closet

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items as lazyItems
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.FilterAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xiaoming.closie.data.model.ClothingItem
import com.xiaoming.closie.data.model.ImageKind
import com.xiaoming.closie.data.model.ItemStatus
import com.xiaoming.closie.data.repository.WardrobeRepository
import com.xiaoming.closie.ui.components.*
import com.xiaoming.closie.ui.theme.ClosieColor
import com.xiaoming.closie.ui.theme.rememberClosieDimensions
import java.io.File

private enum class SortOption(val label: String) {
    RECENT("最近编辑"),
    PURCHASE_DATE("最近购买"),
    WEAR_DESC("穿着最多"),
    PRICE_ASC("价格低到高"),
    PRICE_DESC("价格高到低")
}

private val categoryPresets = listOf(
    "上衣", "下装", "外套", "裙装", "连体", "鞋", "包", "配饰", "运动", "家居服", "内衣", "其他"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClosetScreen(
    repo: WardrobeRepository,
    open: (String) -> Unit,
    add: (ItemStatus) -> Unit
) {
    var query by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf("") }
    var sort by remember { mutableStateOf(SortOption.RECENT) }
    var onlyUnworn by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf(ItemStatus.OWNED) }
    var showFilterSheet by remember { mutableStateOf(false) }
    val dims = rememberClosieDimensions()

    val all by repo.items.collectAsState()
    val wears by repo.wearEvents.collectAsState()
    val wearCount = remember(wears) { wears.groupingBy { it.itemId }.eachCount() }

    val statusItems = all.filter { it.status == status }
    val userCategories = statusItems.map { it.category }.filter { it.isNotBlank() }.distinct()
    val categories = (categoryPresets + userCategories).distinct()

    val q = query.trim()
    val filtered = statusItems.filter { item ->
        val matchesQuery = q.isEmpty() || listOf(
            item.name, item.category, item.subcategory, item.brand, item.store, item.purchasePlatform
        ).any { it.contains(q, ignoreCase = true) }
        val matchesCategory = selectedCategory.isBlank() || item.category == selectedCategory
        val matchesUnworn = !onlyUnworn || (wearCount[item.id] ?: 0) == 0
        matchesQuery && matchesCategory && matchesUnworn
    }

    val visibleItems = when (sort) {
        SortOption.RECENT -> filtered.sortedByDescending { it.updatedAt }
        SortOption.PURCHASE_DATE -> filtered.sortedByDescending { it.purchaseDate }
        SortOption.WEAR_DESC -> filtered.sortedByDescending { wearCount[it.id] ?: 0 }
        SortOption.PRICE_ASC -> filtered.sortedWith(compareBy({ it.price ?: Double.MAX_VALUE }, { it.name }))
        SortOption.PRICE_DESC -> filtered.sortedWith(compareByDescending<ClothingItem> { it.price ?: -1.0 }.thenBy { it.name })
    }

    fun clearAll() {
        query = ""
        selectedCategory = ""
        onlyUnworn = false
        sort = SortOption.RECENT
    }

    Scaffold(
        containerColor = ClosieColor.Canvas,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("我的衣橱", style = MaterialTheme.typography.titleLarge, color = ClosieColor.Ink)
                        Text("${statusItems.size} 件", style = MaterialTheme.typography.bodySmall, color = ClosieColor.Graphite)
                    }
                },
                actions = {
                    IconButton(onClick = { add(status) }) {
                        Icon(Icons.Default.Add, contentDescription = "添加衣服", tint = ClosieColor.Fig)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = ClosieColor.Canvas)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(horizontal = dims.pageHorizontal, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                StatusTab("已拥有", status == ItemStatus.OWNED) {
                    status = ItemStatus.OWNED; selectedCategory = ""; onlyUnworn = false
                }
                StatusTab("试过 / 退货", status == ItemStatus.RETURNED) {
                    status = ItemStatus.RETURNED; selectedCategory = ""; onlyUnworn = false
                }
            }

            ClosieSearchBar(
                value = query,
                onValueChange = { query = it },
                placeholder = "搜索衣服、品牌、类别"
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                    item {
                        ClosieFilterChip(
                            selected = selectedCategory.isBlank(),
                            onClick = { selectedCategory = "" },
                            label = "全部"
                        )
                    }
                    lazyItems(categories) { c ->
                        ClosieFilterChip(
                            selected = selectedCategory == c,
                            onClick = { selectedCategory = c },
                            label = c
                        )
                    }
                }
                IconButton(onClick = { showFilterSheet = true }) {
                    Icon(Icons.Outlined.FilterAlt, contentDescription = "筛选", tint = ClosieColor.Graphite)
                }
            }

            if (visibleItems.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    if (statusItems.isEmpty()) {
                        if (status == ItemStatus.RETURNED) {
                            ClosieEmptyState(
                                title = "还没有试过 / 退货记录",
                                subtitle = "以后试过但没留下的衣服，也可以记在这里。",
                                actionLabel = "+ 添加衣服",
                                onAction = { add(status) }
                            )
                        } else {
                            ClosieEmptyState(
                                title = "衣橱还是空的",
                                subtitle = "添加第一件真正喜欢的衣服。",
                                actionLabel = "+ 添加衣服",
                                onAction = { add(status) }
                            )
                        }
                    } else {
                        ClosieEmptyState(
                            title = "没有符合当前条件的衣服",
                            subtitle = "试试清除筛选。",
                            actionLabel = "清除筛选",
                            onAction = ::clearAll
                        )
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                    horizontalArrangement = Arrangement.spacedBy(dims.gridGutter),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(visibleItems, key = { it.id }) { item ->
                        ClothingItemTile(
                            item = item,
                            wears = wearCount[item.id] ?: 0,
                            showWearCount = status == ItemStatus.OWNED,
                            aspectRatio = dims.gridAspectRatio
                        ) { open(item.id) }
                    }
                }
            }
        }
    }

    if (showFilterSheet) {
        ModalBottomSheet(
            onDismissRequest = { showFilterSheet = false },
            containerColor = ClosieColor.Surface,
            shape = MaterialTheme.shapes.extraLarge
        ) {
            FilterSheetContent(
                sort = sort,
                onSort = { sort = it },
                onlyUnworn = onlyUnworn,
                onOnlyUnworn = { onlyUnworn = it },
                showUnwornFilter = status == ItemStatus.OWNED,
                onClear = ::clearAll,
                onApply = { showFilterSheet = false }
            )
        }
    }
}

@Composable
private fun StatusTab(label: String, selected: Boolean, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .heightIn(min = 44.dp)
            .clickable(onClick = onClick)
            .padding(end = 24.dp, top = 2.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(label, style = MaterialTheme.typography.titleMedium, color = if (selected) ClosieColor.Ink else ClosieColor.Stone)
        Box(
            modifier = Modifier
                .height(2.dp)
                .width(if (selected) 22.dp else 0.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(ClosieColor.Fig)
        )
    }
}

@Composable
private fun ClothingItemTile(
    item: ClothingItem,
    wears: Int,
    showWearCount: Boolean,
    aspectRatio: Float,
    onClick: () -> Unit
) {
    val image = item.images.firstOrNull { it.kind == ImageKind.FLAT }
        ?: item.images.firstOrNull { it.kind == ImageKind.PRODUCT }
        ?: item.images.firstOrNull()
    Column(
        modifier = Modifier.clickable(onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        ClosieImageTile(
            model = image?.localPath?.let { File(it) },
            contentDescription = item.name,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(aspectRatio),
            contentScale = if (image?.kind == ImageKind.FLAT) ContentScale.Fit else ContentScale.Crop,
            placeholder = {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(item.name, color = ClosieColor.Stone, style = MaterialTheme.typography.bodySmall)
                }
            }
        )
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(item.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, color = ClosieColor.Ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val meta = listOfNotNull(item.brand.takeIf { it.isNotBlank() }, item.sizeLabel.takeIf { it.isNotBlank() })
                .joinToString(" · ")
            if (meta.isNotBlank()) {
                Text(meta, style = MaterialTheme.typography.bodySmall, color = ClosieColor.Graphite, maxLines = 1)
            }
            if (showWearCount) {
                Text(
                    if (wears > 0) "穿过 $wears 次" else "还没穿过",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (wears > 0) ClosieColor.Graphite else ClosieColor.Stone
                )
            }
        }
    }
}

@Composable
private fun FilterSheetContent(
    sort: SortOption,
    onSort: (SortOption) -> Unit,
    onlyUnworn: Boolean,
    onOnlyUnworn: (Boolean) -> Unit,
    showUnwornFilter: Boolean,
    onClear: () -> Unit,
    onApply: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(bottom = 32.dp, top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Text("筛选", style = MaterialTheme.typography.headlineSmall, color = ClosieColor.Ink)

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("状态", style = MaterialTheme.typography.titleMedium, color = ClosieColor.Ink)
            Text("在衣橱页顶部切换“已拥有 / 试过·退货”", style = MaterialTheme.typography.bodyMedium, color = ClosieColor.Graphite)
        }

        if (showUnwornFilter) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("只看未穿过", style = MaterialTheme.typography.titleMedium, color = ClosieColor.Ink)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.large)
                        .background(ClosieColor.SurfaceSoft)
                        .clickable { onOnlyUnworn(!onlyUnworn) }
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("只看未穿过的衣服", color = ClosieColor.Ink)
                    Switch(checked = onlyUnworn, onCheckedChange = onOnlyUnworn)
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("排序", style = MaterialTheme.typography.titleMedium, color = ClosieColor.Ink)
            SortOption.entries.forEach { option ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.medium)
                        .background(if (sort == option) ClosieColor.FigSoft else ClosieColor.Paper)
                        .clickable { onSort(option) }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(option.label, color = if (sort == option) ClosieColor.FigPressed else ClosieColor.Ink)
                    if (sort == option) Text("●", color = ClosieColor.Fig)
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(
                onClick = onClear,
                modifier = Modifier.weight(1f),
                shape = MaterialTheme.shapes.large
            ) { Text("清除") }
            Button(
                onClick = onApply,
                modifier = Modifier.weight(1f),
                shape = MaterialTheme.shapes.large,
                colors = ButtonDefaults.buttonColors(containerColor = ClosieColor.Fig, contentColor = ClosieColor.Paper)
            ) { Text("应用") }
        }
    }
}
