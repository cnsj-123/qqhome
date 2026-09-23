package com.xiaoming.closie.ui.detail

import android.app.DatePickerDialog
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.xiaoming.closie.data.model.*
import com.xiaoming.closie.data.repository.WardrobeRepository
import com.xiaoming.closie.ui.components.ClosieBackButton
import com.xiaoming.closie.ui.components.ClosieCollapsibleSection
import com.xiaoming.closie.ui.components.ClosieImageTile
import com.xiaoming.closie.ui.components.ClosieInfoRow
import com.xiaoming.closie.ui.components.priceText
import com.xiaoming.closie.ui.theme.ClosieColor
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
    var deleting by remember { mutableStateOf(false) }
    var showAllWears by remember { mutableStateOf(false) }
    var showAllWash by remember { mutableStateOf(false) }
    var expandPurchase by remember { mutableStateOf(true) }
    var expandDetails by remember { mutableStateOf(false) }
    var expandRecords by remember { mutableStateOf(false) }
    var expandReturn by remember { mutableStateOf(false) }
    if (v == null) { back(); return }

    val itemWears = ws.filter { it.itemId == id }.sortedByDescending { it.date }
    val itemWash = xs.filter { it.itemId == id }.sortedByDescending { it.date }
    val wears = itemWears.size
    val mainImage = v.images.firstOrNull { it.kind == ImageKind.FLAT }
        ?: v.images.firstOrNull { it.kind == ImageKind.PRODUCT }
        ?: v.images.firstOrNull()

    Scaffold(
        containerColor = ClosieColor.Canvas,
        topBar = {
            TopAppBar(
                title = { Text(v.name, maxLines = 1) },
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
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            item {
                ClosieImageTile(
                    model = mainImage?.localPath?.let { File(it) },
                    contentDescription = v.name,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(0.95f),
                    contentScale = if (mainImage?.kind == ImageKind.FLAT) ContentScale.Fit else ContentScale.Crop,
                    placeholder = {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(v.name, color = ClosieColor.InkTertiary)
                        }
                    }
                )
            }

            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(v.name, style = MaterialTheme.typography.headlineSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (v.brand.isNotBlank()) Text(v.brand, style = MaterialTheme.typography.bodyLarge, color = ClosieColor.InkSecondary)
                        Text("${v.category}${v.subcategory.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""}", style = MaterialTheme.typography.bodyLarge, color = ClosieColor.InkSecondary)
                    }
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

            item {
                ClosieCollapsibleSection(title = "购买信息", expanded = expandPurchase, onToggle = { expandPurchase = !expandPurchase }) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        ClosieInfoRow("店铺", v.store)
                        ClosieInfoRow("平台", v.purchasePlatform)
                        ClosieInfoRow("购买价", v.price?.let { priceText(it) })
                        ClosieInfoRow("原价", v.originalPrice?.let { priceText(it) })
                        ClosieInfoRow("购买日期", v.purchaseDate)
                        if (v.productUrl.isNotBlank()) {
                            Text(
                                "打开商品链接",
                                color = ClosieColor.Rose,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.clickable {
                                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(v.productUrl))) }
                                }
                            )
                        }
                    }
                }
            }

            item {
                ClosieCollapsibleSection(title = "衣物详情", expanded = expandDetails, onToggle = { expandDetails = !expandDetails }) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        ClosieInfoRow("尺码", v.sizeLabel)
                        ClosieInfoRow("安全类别", v.safetyCategory)
                        if (v.materials.isNotEmpty()) {
                            Text("材质", style = MaterialTheme.typography.bodyMedium, color = ClosieColor.InkSecondary)
                            v.materials.forEach { Text("${it.name} ${it.percentage}", style = MaterialTheme.typography.bodyMedium) }
                        }
                        if (v.measurements.isNotEmpty()) {
                            Text("尺寸", style = MaterialTheme.typography.bodyMedium, color = ClosieColor.InkSecondary)
                            v.measurements.forEach { Text("${it.name} ${it.value}${it.unit}", style = MaterialTheme.typography.bodyMedium) }
                        }
                    }
                }
            }

            item {
                ClosieCollapsibleSection(title = "我的记录", expanded = expandRecords, onToggle = { expandRecords = !expandRecords }) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (v.rating > 0) Text("${"★".repeat(v.rating)}${"☆".repeat(5 - v.rating)}", style = MaterialTheme.typography.bodyLarge, color = ClosieColor.Rose)
                        if (v.comment.isNotBlank()) Text(v.comment, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            if (v.status == ItemStatus.RETURNED && v.returnReason.isNotBlank()) {
                item {
                    ClosieCollapsibleSection(title = "退货信息", expanded = expandReturn, onToggle = { expandReturn = !expandReturn }) {
                        Text(v.returnReason, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            if (v.status == ItemStatus.OWNED) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("使用记录", style = MaterialTheme.typography.titleMedium)
                        Text("穿着 $wears 次 · 洗涤 ${itemWash.size} 次", style = MaterialTheme.typography.bodyLarge)
                        if (v.price != null && wears > 0) {
                            Text("单次穿着成本 ¥" + "%.2f".format(v.price / wears), style = MaterialTheme.typography.bodyMedium, color = ClosieColor.InkSecondary)
                        }
                        val lastWear = itemWears.firstOrNull()?.date
                        val lastWash = itemWash.firstOrNull()?.date
                        if (lastWear != null) Text("最近穿着：$lastWear", style = MaterialTheme.typography.bodyMedium, color = ClosieColor.InkSecondary)
                        if (lastWash != null) Text("最近洗涤：$lastWash", style = MaterialTheme.typography.bodyMedium, color = ClosieColor.InkSecondary)
                    }
                }

                item {
                    WearHistorySection(
                        wears = itemWears,
                        showAll = showAllWears,
                        onToggle = { showAllWears = !showAllWears },
                        onDelete = { repo.deleteWearEvent(it) }
                    )
                }

                item {
                    WashHistorySection(
                        washes = itemWash,
                        showAll = showAllWash,
                        onToggle = { showAllWash = !showAllWash },
                        onDelete = { repo.deleteWashEvent(it) }
                    )
                }
            }

            val otherImages = v.images.filterNot { it.id == mainImage?.id }
            if (otherImages.isNotEmpty()) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("更多图片", style = MaterialTheme.typography.titleMedium)
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(otherImages) { img ->
                                ClosieImageTile(
                                    model = img.localPath?.let { File(it) },
                                    contentDescription = v.name,
                                    modifier = Modifier.size(120.dp),
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
private fun DetailActionChip(label: String, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(999.dp),
        color = ClosieColor.Surface,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        border = androidx.compose.foundation.BorderStroke(1.dp, ClosieColor.Hairline)
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
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
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("穿着记录", style = MaterialTheme.typography.titleMedium)
        visible.forEach { w ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(w.date, style = MaterialTheme.typography.bodyMedium)
                    val label = if (w.source == WearSource.OOTD) "来自 OOTD" else "手动记录"
                    Text(" · $label", style = MaterialTheme.typography.bodySmall, color = ClosieColor.InkSecondary)
                }
                if (w.source == WearSource.MANUAL) {
                    TextButton(onClick = { onDelete(w.id) }) { Text("删除", color = ClosieColor.Error) }
                }
            }
        }
        if (wears.size > 8) {
            TextButton(onClick = onToggle) {
                Text(if (showAll) "收起" else "查看全部（${wears.size}）", color = ClosieColor.Rose)
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
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("洗涤记录", style = MaterialTheme.typography.titleMedium)
        visible.forEach { w ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(w.date, style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = { onDelete(w.id) }) { Text("删除", color = ClosieColor.Error) }
            }
        }
        if (washes.size > 8) {
            TextButton(onClick = onToggle) {
                Text(if (showAll) "收起" else "查看全部（${washes.size}）", color = ClosieColor.Rose)
            }
        }
    }
}
