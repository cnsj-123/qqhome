package com.xiaoming.closie.ui.detail

import android.app.DatePickerDialog
import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.background
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xiaoming.closie.data.model.*
import com.xiaoming.closie.data.repository.WardrobeRepository
import com.xiaoming.closie.ui.components.ClosieCompactTopBar
import com.xiaoming.closie.ui.components.ClosieImageTile
import com.xiaoming.closie.ui.components.priceText
import com.xiaoming.closie.ui.theme.ClosieColor
import com.xiaoming.closie.ui.theme.rememberClosieDimensions
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(repo: WardrobeRepository, id: String, edit: (String) -> Unit, back: () -> Unit) {
    val all by repo.items.collectAsState()
    val ws by repo.wearEvents.collectAsState()
    val xs by repo.washEvents.collectAsState()
    val v = all.firstOrNull { it.id == id }
    val context = LocalContext.current
    val dims = rememberClosieDimensions()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var deleting by remember { mutableStateOf(false) }
    var showMore by remember { mutableStateOf(false) }
    var showAllWears by remember { mutableStateOf(false) }
    var showAllWash by remember { mutableStateOf(false) }
    if (v == null) { back(); return }

    val itemWears = ws.filter { it.itemId == id }.sortedByDescending { it.date }
    val itemWash = xs.filter { it.itemId == id }.sortedByDescending { it.date }
    val wears = itemWears.size
    val washes = itemWash.size
    val costPerWear = v.price?.takeIf { wears > 0 }?.let { it / wears }
    val mainImage = v.images.firstOrNull { it.kind == ImageKind.FLAT }
        ?: v.images.firstOrNull { it.kind == ImageKind.PRODUCT }
        ?: v.images.firstOrNull()
    val otherImages = v.images.filterNot { it.id == mainImage?.id }

    val hasAbout = v.materials.isNotEmpty() || v.measurements.isNotEmpty() || v.safetyCategory.isNotBlank()
    val hasPurchase = v.store.isNotBlank() || v.purchasePlatform.isNotBlank() ||
        v.price != null || v.originalPrice != null || v.purchaseDate.isNotBlank() || v.productUrl.isNotBlank()
    val hasRecords = v.rating > 0 || v.comment.isNotBlank()

    fun copyLink() {
        val cm = context.getSystemService(ClipboardManager::class.java)
        cm?.setPrimaryClip(ClipData.newPlainText("商品链接", v.productUrl))
        scope.launch { snackbarHostState.showSnackbar("商品链接已复制") }
    }

    fun pickWearDate() {
        val d = LocalDate.now()
        DatePickerDialog(context, { _, y, m, day ->
            repo.addWear(id, "%04d-%02d-%02d".format(y, m + 1, day), WearSource.MANUAL)
        }, d.year, d.monthValue - 1, d.dayOfMonth).show()
    }

    fun pickWashDate() {
        val d = LocalDate.now()
        DatePickerDialog(context, { _, y, m, day ->
            repo.addWash(id, "%04d-%02d-%02d".format(y, m + 1, day))
        }, d.year, d.monthValue - 1, d.dayOfMonth).show()
    }

    Scaffold(
        containerColor = ClosieColor.Canvas,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            ClosieCompactTopBar(
                title = "",
                onBack = back,
                actions = {
                    IconButton(onClick = { edit(id) }) {
                        Icon(Icons.Outlined.Edit, contentDescription = "编辑", tint = ClosieColor.Ink)
                    }
                    IconButton(onClick = { deleting = true }) {
                        Icon(Icons.Default.Delete, contentDescription = "删除", tint = ClosieColor.Error)
                    }
                }
            )
        }
    ) { pad ->
        LazyColumn(
            modifier = Modifier
                .padding(pad)
                .padding(horizontal = dims.pageHorizontal, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
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
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val cat = listOfNotNull(
                            v.category.takeIf { it.isNotBlank() && it != "未分类" },
                            v.subcategory.takeIf { it.isNotBlank() }
                        ).joinToString(" · ")
                        if (cat.isNotBlank()) {
                            Text(cat, style = MaterialTheme.typography.bodyMedium, color = ClosieColor.Graphite)
                        }
                        Spacer(Modifier.weight(1f))
                        if (v.sizeLabel.isNotBlank()) {
                            Text(v.sizeLabel, style = MaterialTheme.typography.bodyMedium, color = ClosieColor.Graphite)
                        }
                    }
                    if (v.price != null) {
                        Text(priceText(v.price), style = MaterialTheme.typography.headlineMedium, color = ClosieColor.Ink, fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            if (v.status == ItemStatus.OWNED) {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DetailActionButton("穿了", Modifier.weight(1f)) { repo.addWear(id, LocalDate.now().toString()) }
                        DetailActionButton("洗了", Modifier.weight(1f)) { repo.addWash(id, LocalDate.now().toString()) }
                        DetailActionButton("更多", Modifier.weight(1f)) { showMore = true }
                    }
                }
            }

            if (hasAbout) {
                item {
                    DetailSection("衣物详情") {
                        if (v.materials.isNotEmpty()) {
                            Text("材质", style = MaterialTheme.typography.titleSmall, color = ClosieColor.Ink)
                            v.materials.forEach { m ->
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(m.name.takeIf { it.isNotBlank() } ?: "材质", style = MaterialTheme.typography.bodyMedium, color = ClosieColor.Ink)
                                    Text(m.percentage, style = MaterialTheme.typography.bodyMedium, color = ClosieColor.Graphite)
                                }
                            }
                        }
                        if (v.measurements.isNotEmpty()) {
                            Text("详细尺寸", style = MaterialTheme.typography.titleSmall, color = ClosieColor.Ink)
                            MeasurementGrid(v.measurements)
                        }
                        if (v.safetyCategory.isNotBlank()) {
                            Text("安全类别 · ${v.safetyCategory}", style = MaterialTheme.typography.bodyMedium, color = ClosieColor.Graphite)
                        }
                    }
                }
            }

            if (hasPurchase) {
                item {
                    DetailSection("购买信息") {
                        if (v.store.isNotBlank()) InfoLine("店铺", v.store)
                        if (v.purchasePlatform.isNotBlank()) InfoLine("平台", v.purchasePlatform)
                        v.price?.let { InfoLine("价格", priceText(it)) }
                        v.originalPrice?.let { InfoLine("原价", priceText(it)) }
                        if (v.purchaseDate.isNotBlank()) InfoLine("日期", v.purchaseDate)
                        if (v.productUrl.isNotBlank()) {
                            Spacer(Modifier.height(4.dp))
                            OutlinedButton(
                                onClick = ::copyLink,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp)
                            ) { Text("复制商品链接") }
                        }
                    }
                }
            }

            if (v.status == ItemStatus.OWNED) {
                item {
                    DetailSection("使用记录") {
                        UsageStats(wears = wears, washes = washes, costPerWear = costPerWear)
                        val lastWear = itemWears.firstOrNull()?.date
                        val lastWash = itemWash.firstOrNull()?.date
                        if (lastWear != null) Text("最近穿着：$lastWear", style = MaterialTheme.typography.bodySmall, color = ClosieColor.Graphite)
                        if (lastWash != null) Text("最近洗涤：$lastWash", style = MaterialTheme.typography.bodySmall, color = ClosieColor.Graphite)
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

            if (hasRecords) {
                item {
                    DetailSection("我的记录") {
                        if (v.rating > 0) {
                            Text("${"★".repeat(v.rating)}${"☆".repeat(5 - v.rating)}", style = MaterialTheme.typography.bodyLarge, color = ClosieColor.Fig)
                        }
                        if (v.comment.isNotBlank()) Text(v.comment, style = MaterialTheme.typography.bodyMedium, color = ClosieColor.Ink)
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

    if (showMore) {
        ModalBottomSheet(onDismissRequest = { showMore = false }, containerColor = ClosieColor.Surface) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 32.dp)) {
                Text("更多操作", style = MaterialTheme.typography.titleLarge, color = ClosieColor.Ink)
                Spacer(Modifier.height(8.dp))
                SheetAction("补录穿着") { showMore = false; pickWearDate() }
                SheetAction("补录洗涤") { showMore = false; pickWashDate() }
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
private fun SheetAction(label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = ClosieColor.Ink)
    }
}

@Composable
private fun DetailActionButton(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .height(44.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(ClosieColor.Mist)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = ClosieColor.Ink)
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
private fun MeasurementGrid(measurements: List<Measurement>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        measurements.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { m ->
                    InfoCell(m.name, "${m.value} ${m.unit.ifBlank { "cm" }}", Modifier.weight(1f))
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun InfoCell(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(ClosieColor.SurfaceSoft)
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = ClosieColor.InkSecondary)
        Text(value, style = MaterialTheme.typography.bodyLarge, color = ClosieColor.Ink)
    }
}

@Composable
private fun UsageStats(wears: Int, washes: Int, costPerWear: Double?) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        StatCell(wears.toString(), "穿着", Modifier.weight(1f))
        StatCell(washes.toString(), "洗涤", Modifier.weight(1f))
        StatCell(if (costPerWear != null) priceText(costPerWear) else "—", "单次成本", Modifier.weight(1f))
    }
}

@Composable
private fun StatCell(value: String, label: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(ClosieColor.SurfaceSoft)
            .padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(value, style = MaterialTheme.typography.titleLarge, color = ClosieColor.Ink, fontWeight = FontWeight.SemiBold)
        Text(label, style = MaterialTheme.typography.bodySmall, color = ClosieColor.Graphite)
    }
}

@Composable
private fun InfoLine(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
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
