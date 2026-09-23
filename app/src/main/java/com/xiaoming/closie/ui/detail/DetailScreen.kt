package com.xiaoming.closie.ui.detail

import android.app.DatePickerDialog
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xiaoming.closie.data.model.*
import com.xiaoming.closie.data.repository.WardrobeRepository
import com.xiaoming.closie.ui.components.ClosieBackButton
import com.xiaoming.closie.ui.components.ClosieImageTile
import com.xiaoming.closie.ui.components.priceText
import com.xiaoming.closie.ui.theme.ClosieColor
import com.xiaoming.closie.ui.theme.rememberClosieDimensions
import java.io.File
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DetailScreen(repo: WardrobeRepository, id: String, edit: (String) -> Unit, back: () -> Unit) {
    val all by repo.items.collectAsState()
    val ws by repo.wearEvents.collectAsState()
    val xs by repo.washEvents.collectAsState()
    val v = all.firstOrNull { it.id == id }
    val context = LocalContext.current
    val dims = rememberClosieDimensions()
    var deleting by remember { mutableStateOf(false) }
    var showAllWears by remember { mutableStateOf(false) }
    var showAllWash by remember { mutableStateOf(false) }
    if (v == null) { back(); return }

    val itemWears = ws.filter { it.itemId == id }.sortedByDescending { it.date }
    val itemWash = xs.filter { it.itemId == id }.sortedByDescending { it.date }
    val wears = itemWears.size
    val mainImage = v.images.firstOrNull { it.kind == ImageKind.FLAT }
        ?: v.images.firstOrNull { it.kind == ImageKind.PRODUCT }
        ?: v.images.firstOrNull()
    val otherImages = v.images.filterNot { it.id == mainImage?.id }

    val hasAbout = v.materials.isNotEmpty() || v.measurements.isNotEmpty() || v.safetyCategory.isNotBlank()
    val hasPurchase = v.store.isNotBlank() || v.purchasePlatform.isNotBlank() ||
        v.price != null || v.originalPrice != null || v.purchaseDate.isNotBlank() || v.productUrl.isNotBlank()
    val hasRecords = v.rating > 0 || v.comment.isNotBlank()

    Scaffold(
        containerColor = ClosieColor.Canvas,
        topBar = {
            TopAppBar(
                title = { Text("", maxLines = 1) },
                navigationIcon = { ClosieBackButton(onClick = back) },
                actions = {
                    IconButton(onClick = { edit(id) }) {
                        Icon(Icons.Outlined.Edit, contentDescription = "编辑", tint = ClosieColor.Ink)
                    }
                    IconButton(onClick = { deleting = true }) {
                        Icon(Icons.Default.Delete, contentDescription = "删除", tint = ClosieColor.Error)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = ClosieColor.Canvas)
            )
        }
    ) { pad ->
        LazyColumn(
            modifier = Modifier
                .padding(pad)
                .padding(horizontal = dims.pageHorizontal, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                ClosieImageTile(
                    model = mainImage?.localPath?.let { File(it) },
                    contentDescription = v.name,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(0.85f),
                    contentScale = if (mainImage?.kind == ImageKind.FLAT) ContentScale.Fit else ContentScale.Crop,
                    placeholder = {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(v.name, color = ClosieColor.Stone)
                        }
                    }
                )
            }

            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (v.brand.isNotBlank()) {
                        Text(v.brand, style = MaterialTheme.typography.labelLarge, color = ClosieColor.Graphite)
                    }
                    Text(v.name, style = MaterialTheme.typography.headlineLarge, color = ClosieColor.Ink, fontWeight = FontWeight.SemiBold)
                    val cat = listOfNotNull(
                        v.category.takeIf { it.isNotBlank() && it != "未分类" },
                        v.subcategory.takeIf { it.isNotBlank() }
                    ).joinToString(" · ")
                    if (cat.isNotBlank()) Text(cat, style = MaterialTheme.typography.bodyMedium, color = ClosieColor.Graphite)
                    if (v.sizeLabel.isNotBlank()) Text(v.sizeLabel, style = MaterialTheme.typography.bodyMedium, color = ClosieColor.Graphite)
                    if (v.price != null) {
                        Text(priceText(v.price), style = MaterialTheme.typography.headlineMedium, color = ClosieColor.Ink, fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            item {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (v.status == ItemStatus.OWNED) {
                        DetailActionChip("今天穿了") { repo.addWear(id, LocalDate.now().toString()) }
                        DetailActionChip("添加穿着") {
                            val d = LocalDate.now()
                            DatePickerDialog(context, { _, y, m, day ->
                                repo.addWear(id, "%04d-%02d-%02d".format(y, m + 1, day), WearSource.MANUAL)
                            }, d.year, d.monthValue - 1, d.dayOfMonth).show()
                        }
                        DetailActionChip("洗过") { repo.addWash(id, LocalDate.now().toString()) }
                        DetailActionChip("补录洗涤") {
                            val d = LocalDate.now()
                            DatePickerDialog(context, { _, y, m, day ->
                                repo.addWash(id, "%04d-%02d-%02d".format(y, m + 1, day))
                            }, d.year, d.monthValue - 1, d.dayOfMonth).show()
                        }
                    }
                    DetailActionChip("编辑") { edit(id) }
                }
            }

            if (hasAbout) {
                item {
                    DetailSection("关于这件衣服") {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            v.materials.forEach { m ->
                                Text(
                                    listOfNotNull(m.name.takeIf { it.isNotBlank() }, m.percentage.takeIf { it.isNotBlank() }).joinToString(" "),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = ClosieColor.Ink
                                )
                            }
                            if (v.measurements.isNotEmpty()) {
                                Text(
                                    v.measurements.mapNotNull { m ->
                                        val name = m.name.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                                        "$name ${m.value}${m.unit}"
                                    }.joinToString(" · "),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = ClosieColor.Ink
                                )
                            }
                            if (v.safetyCategory.isNotBlank()) {
                                Text("安全类别 · ${v.safetyCategory}", style = MaterialTheme.typography.bodyMedium, color = ClosieColor.Graphite)
                            }
                        }
                    }
                }
            }

            if (hasPurchase) {
                item {
                    DetailSection("购买信息") {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (v.store.isNotBlank()) InfoLine("店铺", v.store)
                            if (v.purchasePlatform.isNotBlank()) InfoLine("平台", v.purchasePlatform)
                            v.price?.let { InfoLine("购买价", priceText(it)) }
                            v.originalPrice?.let { InfoLine("原价", priceText(it)) }
                            if (v.purchaseDate.isNotBlank()) InfoLine("购买日期", v.purchaseDate)
                            if (v.productUrl.isNotBlank()) {
                                Text(
                                    "打开商品链接",
                                    color = ClosieColor.Fig,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.clickable {
                                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(v.productUrl))) }
                                    }
                                )
                            }
                        }
                    }
                }
            }

            if (v.status == ItemStatus.OWNED) {
                item {
                    DetailSection("使用记录") {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("穿着 $wears 次 · 洗涤 ${itemWash.size} 次", style = MaterialTheme.typography.bodyLarge, color = ClosieColor.Ink)
                            if (v.price != null && wears > 0) {
                                Text("单次穿着成本 ¥" + "%.2f".format(v.price / wears), style = MaterialTheme.typography.bodyMedium, color = ClosieColor.Graphite)
                            }
                            val lastWear = itemWears.firstOrNull()?.date
                            val lastWash = itemWash.firstOrNull()?.date
                            if (lastWear != null) Text("最近穿着：$lastWear", style = MaterialTheme.typography.bodyMedium, color = ClosieColor.Graphite)
                            if (lastWash != null) Text("最近洗涤：$lastWash", style = MaterialTheme.typography.bodyMedium, color = ClosieColor.Graphite)
                            Spacer(Modifier.height(4.dp))
                            WearHistorySection(
                                wears = itemWears,
                                showAll = showAllWears,
                                onToggle = { showAllWears = !showAllWears },
                                onDelete = { repo.deleteWearEvent(it) }
                            )
                            WashHistorySection(
                                washes = itemWash,
                                showAll = showAllWash,
                                onToggle = { showAllWash = !showAllWash },
                                onDelete = { repo.deleteWashEvent(it) }
                            )
                        }
                    }
                }
            }

            if (hasRecords) {
                item {
                    DetailSection("我的记录") {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (v.rating > 0) {
                                Text("${"★".repeat(v.rating)}${"☆".repeat(5 - v.rating)}", style = MaterialTheme.typography.bodyLarge, color = ClosieColor.Fig)
                            }
                            if (v.comment.isNotBlank()) Text(v.comment, style = MaterialTheme.typography.bodyMedium, color = ClosieColor.Ink)
                        }
                    }
                }
            }

            if (v.status == ItemStatus.RETURNED && v.returnReason.isNotBlank()) {
                item {
                    DetailSection("退货信息") {
                        Text(v.returnReason, style = MaterialTheme.typography.bodyMedium, color = ClosieColor.Ink)
                    }
                }
            }

            if (otherImages.isNotEmpty()) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("更多图片", style = MaterialTheme.typography.titleMedium, color = ClosieColor.Ink)
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            items(otherImages) { img ->
                                ClosieImageTile(
                                    model = img.localPath?.let { File(it) },
                                    contentDescription = v.name,
                                    modifier = Modifier.size(104.dp),
                                    contentScale = if (img.kind == ImageKind.FLAT) ContentScale.Fit else ContentScale.Crop
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (deleting) {
        AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text("删除这件衣服？") },
            text = { Text("这会同时移除穿着、洗涤等使用记录。") },
            confirmButton = { TextButton(onClick = { repo.deleteItem(id); back() }) { Text("删除", color = ClosieColor.Error) } },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text("取消") } }
        )
    }
}

@Composable
private fun DetailSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        HorizontalDivider(color = ClosieColor.Hairline, modifier = Modifier.padding(bottom = 14.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, color = ClosieColor.Ink)
        Spacer(Modifier.height(8.dp))
        content()
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = ClosieColor.Graphite)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = ClosieColor.Ink,
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.End,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun DetailActionChip(label: String, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .heightIn(min = 44.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(999.dp),
        color = ClosieColor.Mist,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
            style = MaterialTheme.typography.labelLarge,
            color = ClosieColor.Ink
        )
    }
}

@Composable
private fun WearHistorySection(
    wears: List<WearEvent>,
    showAll: Boolean,
    onToggle: () -> Unit,
    onDelete: (String) -> Unit
) {
    if (wears.isEmpty()) return
    val visible = if (showAll) wears else wears.take(8)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("穿着记录", style = MaterialTheme.typography.titleSmall, color = ClosieColor.Ink)
        visible.forEach { w ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(w.date, style = MaterialTheme.typography.bodyMedium, color = ClosieColor.Ink)
                    val label = if (w.source == WearSource.OOTD) "来自 OOTD" else "手动记录"
                    Text(" · $label", style = MaterialTheme.typography.bodySmall, color = ClosieColor.Graphite)
                }
                if (w.source == WearSource.MANUAL) {
                    TextButton(onClick = { onDelete(w.id) }) { Text("删除", color = ClosieColor.Error) }
                }
            }
        }
        if (wears.size > 8) {
            TextButton(onClick = onToggle) {
                Text(if (showAll) "收起" else "查看全部（${wears.size}）", color = ClosieColor.Fig)
            }
        }
    }
}

@Composable
private fun WashHistorySection(
    washes: List<WashEvent>,
    showAll: Boolean,
    onToggle: () -> Unit,
    onDelete: (String) -> Unit
) {
    if (washes.isEmpty()) return
    val visible = if (showAll) washes else washes.take(8)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("洗涤记录", style = MaterialTheme.typography.titleSmall, color = ClosieColor.Ink)
        visible.forEach { w ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(w.date, style = MaterialTheme.typography.bodyMedium, color = ClosieColor.Ink)
                TextButton(onClick = { onDelete(w.id) }) { Text("删除", color = ClosieColor.Error) }
            }
        }
        if (washes.size > 8) {
            TextButton(onClick = onToggle) {
                Text(if (showAll) "收起" else "查看全部（${washes.size}）", color = ClosieColor.Fig)
            }
        }
    }
}
