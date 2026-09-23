package com.xiaoming.closie.ui.closet

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items as lazyItems
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
import androidx.compose.ui.unit.dp
import com.xiaoming.closie.data.model.ClothingItem
import com.xiaoming.closie.data.model.ImageKind
import com.xiaoming.closie.data.model.ItemStatus
import com.xiaoming.closie.data.repository.WardrobeRepository
import com.xiaoming.closie.ui.components.*
import com.xiaoming.closie.ui.theme.ClosieColor
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
                        Text("我的衣橱", style = MaterialTheme.typography.titleLarge)
                        Text("${statusItems.size} 件", style = MaterialTheme.typography.bodySmall, color = ClosieColor.InkSecondary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = ClosieColor.Canvas)
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { add(status) },
                containerColor = ClosieColor.Rose,
                contentColor = ClosieColor.Surface,
                shape = MaterialTheme.shapes.extraLarge
            ) { Icon(Icons.Default.Add, contentDescription = "添加衣服") }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Status segment
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.large)
                    .background(ClosieColor.Surface)
                    .border(1.dp, ClosieColor.Hairline, MaterialTheme.shapes.large)
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                SegmentButton(
                    label = "已拥有",
                    selected = status == ItemStatus.OWNED,
                    onClick = { status = ItemStatus.OWNED; selectedCategory = ""; onlyUnworn = false },
                    modifier = Modifier.weight(1f)
                )
                SegmentButton(
                    label = "试过 / 退货",
                    selected = status == ItemStatus.RETURNED,
                    onClick = { status = ItemStatus.RETURNED; selectedCategory = ""; onlyUnworn = false },
                    modifier = Modifier.weight(1f)
                )
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
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                    Icon(Icons.Outlined.FilterAlt, contentDescription = "筛选", tint = ClosieColor.InkSecondary)
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
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(visibleItems, key = { it.id }) { item ->
                        ClothingItemTile(item, wearCount[item.id] ?: 0, status == ItemStatus.OWNED) { open(item.id) }
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
private fun SegmentButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val bg = if (selected) ClosieColor.Rose else ClosieColor.Surface
    val content = if (selected) ClosieColor.Surface else ClosieColor.InkSecondary
    Box(
        modifier = modifier
            .heightIn(min = 44.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(bg)
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = content)
    }
}

@Composable
private fun ClothingItemTile(
    item: ClothingItem,
    wears: Int,
    showWearCount: Boolean,
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
                .aspectRatio(0.9f),
            contentScale = if (image?.kind == ImageKind.FLAT) ContentScale.Fit else ContentScale.Crop,
            placeholder = {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(item.name, color = ClosieColor.InkTertiary, style = MaterialTheme.typography.bodySmall)
                }
            }
        )
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(item.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1)
            val meta = listOfNotNull(item.brand.takeIf { it.isNotBlank() }, item.sizeLabel.takeIf { it.isNotBlank() })
                .joinToString(" · ")
            if (meta.isNotBlank()) {
                Text(meta, style = MaterialTheme.typography.bodySmall, color = ClosieColor.InkSecondary, maxLines = 1)
            }
            if (showWearCount) {
                Text(
                    if (wears > 0) "穿过 $wears 次" else "还没穿过",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (wears > 0) ClosieColor.InkSecondary else ClosieColor.InkTertiary
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
        Text("筛选", style = MaterialTheme.typography.headlineSmall)

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("状态", style = MaterialTheme.typography.titleMedium)
            Text("在衣橱页顶部切换“已拥有 / 试过·退货”", style = MaterialTheme.typography.bodyMedium, color = ClosieColor.InkSecondary)
        }

        if (showUnwornFilter) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("只看未穿过", style = MaterialTheme.typography.titleMedium)
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
                    Text("只看未穿过的衣服")
                    Switch(checked = onlyUnworn, onCheckedChange = onOnlyUnworn)
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("排序", style = MaterialTheme.typography.titleMedium)
            SortOption.entries.forEach { option ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.medium)
                        .background(if (sort == option) ClosieColor.RoseSoft else ClosieColor.Surface)
                        .clickable { onSort(option) }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(option.label, color = if (sort == option) ClosieColor.RosePressed else ClosieColor.Ink)
                    if (sort == option) Text("●", color = ClosieColor.Rose)
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
                colors = ButtonDefaults.buttonColors(containerColor = ClosieColor.Rose, contentColor = ClosieColor.Surface)
            ) { Text("应用") }
        }
    }
}
