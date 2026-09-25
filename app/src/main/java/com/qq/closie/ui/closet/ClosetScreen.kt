package com.qq.closie.ui.closet

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.qq.closie.data.model.ClothingItem
import com.qq.closie.data.model.ImageKind
import com.qq.closie.data.model.ItemStatus
import com.qq.closie.data.repository.WardrobeRepository
import com.qq.closie.ui.components.ClosieEmptyState
import com.qq.closie.ui.components.ClosieImageTile
import com.qq.closie.ui.components.ClosieSearchBar
import com.qq.closie.ui.theme.ClosieColor
import com.qq.closie.ui.theme.rememberClosieDimensions
import java.io.File
import kotlin.math.roundToInt

enum class SortField(val label: String) {
    RECENT_EDIT("最近编辑"),
    PURCHASE_DATE("购买日期"),
    WEAR_COUNT("穿着次数"),
    WASH_COUNT("洗涤次数"),
    PRICE("价格"),
    COST_PER_WEAR("单次穿着成本"),
    NAME("名称")
}

private val categoryPresets = listOf(
    "上衣", "下装", "外套", "裙装", "连体", "鞋", "包", "配饰", "运动", "家居服", "内衣", "其他"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClosetScreen(
    repo: WardrobeRepository,
    open: (String) -> Unit,
    add: (ItemStatus) -> Unit,
    /**
     * Non-null only when the closet was entered *from* Life OS (生活 → 衣橱).
     *
     * The closet is a full product with its own bottom bar and its own visual language, so when it
     * opens inside Life OS the user has no way of knowing that the system back gesture is the way
     * home — and on a screen that looks this different, "press back" is not something people try.
     * A visible return control is the fix; `null` is what keeps a standalone Closie exactly as it
     * shipped, with no Life OS chrome bolted onto it.
     */
    onReturnToLifeOs: (() -> Unit)? = null,
    /** Text of the return control, e.g. "Life OS" — rendered as "‹ Life OS". */
    returnLabel: String = "Life OS"
) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf("") }
    var sortField by remember { mutableStateOf(runCatching { SortField.valueOf(ClosetPrefs.sortField(context)) }.getOrDefault(SortField.RECENT_EDIT)) }
    var ascending by remember { mutableStateOf(ClosetPrefs.ascending(context)) }
    var gridColumns by remember { mutableStateOf(ClosetPrefs.gridColumns(context).coerceIn(1, 3)) }
    var onlyUnworn by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf(ItemStatus.OWNED) }
    var showCategorySheet by remember { mutableStateOf(false) }
    var showSortSheet by remember { mutableStateOf(false) }
    var showDisplaySheet by remember { mutableStateOf(false) }
    val dims = rememberClosieDimensions()

    val all by repo.items.collectAsState()
    val wears by repo.wearEvents.collectAsState()
    val washes by repo.washEvents.collectAsState()
    val wearCount = remember(wears) { wears.groupingBy { it.itemId }.eachCount() }
    val washCount = remember(washes) { washes.groupingBy { it.itemId }.eachCount() }

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

    val visibleItems = sortItems(filtered, sortField, ascending, wearCount, washCount)

    fun clearAll() {
        query = ""
        selectedCategory = ""
        onlyUnworn = false
        sortField = SortField.RECENT_EDIT
        ascending = false
        // Persist the reset so re-entering the closet cannot resurrect the just-cleared sort.
        ClosetPrefs.saveSortField(context, SortField.RECENT_EDIT)
        ClosetPrefs.saveAscending(context, false)
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
            // "‹ Life OS" — rendered as the first thing inside the *content* column, directly under
            // the TopAppBar, rather than as a navigationIcon on the bar itself. Two reasons:
            //
            //  1. The bar's leading slot already belongs to the title block, and putting an icon
            //     there would push "我的衣橱" right and change a shipped layout for every user.
            //  2. As a content row it disappears cleanly when [onReturnToLifeOs] is null — the
            //     standalone closet gets a byte-identical rendering to v0.2.0, which is what lets
            //     this change be additive rather than a redesign.
            if (onReturnToLifeOs != null) {
                ReturnToLifeOsRow(label = returnLabel, onClick = onReturnToLifeOs)
            }

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
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilterButton(
                    label = selectedCategory.ifBlank { "全部类别" },
                    active = selectedCategory.isNotBlank(),
                    onClick = { showCategorySheet = true }
                )
                FilterButton(label = "排序", active = sortField != SortField.RECENT_EDIT || ascending, onClick = { showSortSheet = true })
                FilterButton(label = "显示", active = onlyUnworn || gridColumns != 2, onClick = { showDisplaySheet = true })
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
                    columns = GridCells.Fixed(gridColumns),
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

    if (showCategorySheet) {
        ModalBottomSheet(
            onDismissRequest = { showCategorySheet = false },
            containerColor = ClosieColor.Surface,
            shape = MaterialTheme.shapes.extraLarge
        ) {
            CategorySheetContent(
                categories = categories,
                selected = selectedCategory,
                onSelect = { c -> selectedCategory = c; showCategorySheet = false }
            )
        }
    }

    if (showSortSheet) {
        ModalBottomSheet(
            onDismissRequest = { showSortSheet = false },
            containerColor = ClosieColor.Surface,
            shape = MaterialTheme.shapes.extraLarge
        ) {
            SortSheetContent(
                field = sortField,
                ascending = ascending,
                onField = { sortField = it; ClosetPrefs.saveSortField(context, it) },
                onAscending = { ascending = it; ClosetPrefs.saveAscending(context, it) }
            )
        }
    }

    if (showDisplaySheet) {
        ModalBottomSheet(
            onDismissRequest = { showDisplaySheet = false },
            containerColor = ClosieColor.Surface,
            shape = MaterialTheme.shapes.extraLarge
        ) {
            DisplaySheetContent(
                columns = gridColumns,
                onColumns = { gridColumns = it; ClosetPrefs.saveGridColumns(context, it) },
                onlyUnworn = onlyUnworn,
                onOnlyUnworn = { onlyUnworn = it },
                showUnworn = status == ItemStatus.OWNED
            )
        }
    }
}

private fun sortItems(
    list: List<ClothingItem>,
    field: SortField,
    ascending: Boolean,
    wearCount: Map<String, Int>,
    washCount: Map<String, Int>
): List<ClothingItem> {
    val direction = { cmp: Comparator<ClothingItem> -> if (ascending) cmp else cmp.reversed() }

    // Items whose sort key is undefined (null price, no cost-per-wear) always go last in both
    // directions — a descending sort must never surface nulls on top.
    fun <K : Comparable<K>> byKey(key: (ClothingItem) -> K?): List<ClothingItem> {
        val (valid, rest) = list.partition { key(it) != null }
        val cmp = compareBy<ClothingItem> { key(it)!! }
        return valid.sortedWith(direction(cmp)) + rest.sortedBy { it.name }
    }

    return when (field) {
        SortField.RECENT_EDIT -> list.sortedWith(direction(compareBy { it.updatedAt }))
        SortField.PURCHASE_DATE -> list.sortedWith(direction(compareBy { it.purchaseDate }))
        SortField.WEAR_COUNT -> list.sortedWith(direction(compareBy { wearCount[it.id] ?: 0 }))
        SortField.WASH_COUNT -> list.sortedWith(direction(compareBy { washCount[it.id] ?: 0 }))
        SortField.PRICE -> byKey { it.price }
        SortField.NAME -> list.sortedWith(direction(compareBy { it.name.lowercase() }))
        SortField.COST_PER_WEAR -> byKey { item ->
            val wears = wearCount[item.id] ?: 0
            item.price?.takeIf { wears > 0 }?.let { p -> p / wears }
        }
    }
}

@Composable
private fun FilterButton(label: String, active: Boolean, onClick: () -> Unit) {
    val bg = if (active) ClosieColor.Ink else ClosieColor.Mist
    val content = if (active) ClosieColor.Paper else ClosieColor.Graphite
    Row(
        modifier = Modifier
            .heightIn(min = 44.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = content, maxLines = 1)
        Text("▾", style = MaterialTheme.typography.labelMedium, color = content)
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
private fun CategorySheetContent(
    categories: List<String>,
    selected: String,
    onSelect: (String) -> Unit
) {
    val all = listOf("") + categories
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(bottom = 32.dp, top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("选择类别", style = MaterialTheme.typography.headlineSmall, color = ClosieColor.Ink)
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(all, key = { it.ifBlank { "__all__" } }) { category ->
                val label = category.ifBlank { "全部" }
                val isSelected = category == selected
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (isSelected) ClosieColor.Ink else ClosieColor.Mist)
                        .clickable { onSelect(category) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (isSelected) ClosieColor.Paper else ClosieColor.Graphite,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

@Composable
private fun SortSheetContent(
    field: SortField,
    ascending: Boolean,
    onField: (SortField) -> Unit,
    onAscending: (Boolean) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(bottom = 32.dp, top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("排序", style = MaterialTheme.typography.headlineSmall, color = ClosieColor.Ink)

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("排序依据", style = MaterialTheme.typography.titleMedium, color = ClosieColor.Ink)
            SortField.entries.forEach { option ->
                val selected = option == field
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (selected) ClosieColor.FigSoft else Color.Transparent)
                        .clickable { onField(option) }
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(option.label, color = if (selected) ClosieColor.FigPressed else ClosieColor.Ink)
                    if (selected) Text("●", color = ClosieColor.Fig)
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("顺序", style = MaterialTheme.typography.titleMedium, color = ClosieColor.Ink)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OrderChip("↑ 升序", ascending, Modifier.weight(1f)) { onAscending(true) }
                OrderChip("↓ 降序", !ascending, Modifier.weight(1f)) { onAscending(false) }
            }
        }
    }
}

@Composable
private fun OrderChip(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .height(44.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) ClosieColor.Ink else ClosieColor.Mist)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = if (selected) ClosieColor.Paper else ClosieColor.Graphite)
    }
}

@Composable
private fun DisplaySheetContent(
    columns: Int,
    onColumns: (Int) -> Unit,
    onlyUnworn: Boolean,
    onOnlyUnworn: (Boolean) -> Unit,
    showUnworn: Boolean
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(bottom = 32.dp, top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Text("显示", style = MaterialTheme.typography.headlineSmall, color = ClosieColor.Ink)

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("显示大小", style = MaterialTheme.typography.titleMedium, color = ClosieColor.Ink)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("小", style = MaterialTheme.typography.bodyMedium, color = ClosieColor.InkSecondary)
                Slider(
                    value = (3 - columns).toFloat(),
                    onValueChange = { v -> onColumns(3 - v.roundToInt().coerceIn(0, 2)) },
                    valueRange = 0f..2f,
                    steps = 1,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                )
                Text("大", style = MaterialTheme.typography.bodyMedium, color = ClosieColor.InkSecondary)
            }
        }

        if (showUnworn) {
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
}

/**
 * The "‹ Life OS" return control.
 *
 * A chevron plus a word, on a 48dp-tall touch target, in the closet's own quiet ink — deliberately
 * *not* a filled button. The closet is a different product with a different palette, and dropping a
 * Life OS-styled pill into its header would look like a leftover. A plain text-and-chevron affordance
 * reads as navigation in either design language, which matters because it has to sit inside Closie's
 * chrome while referring to Life OS.
 *
 * `clickable` is applied before `padding` so the entire row is the target and the ripple covers the
 * full strip; reversing the order would shrink the hit area down to the two glyphs.
 */
@Composable
private fun ReturnToLifeOsRow(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .clickable(onClick = onClick)
            .heightIn(min = 48.dp)
            .semantics { contentDescription = "返回 $label" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowLeft,
            contentDescription = null,
            tint = ClosieColor.Graphite,
            modifier = Modifier.size(20.dp)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = ClosieColor.Graphite
        )
    }
}
