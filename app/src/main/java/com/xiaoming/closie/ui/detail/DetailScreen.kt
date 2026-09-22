package com.xiaoming.closie.ui.detail

import android.app.DatePickerDialog
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.xiaoming.closie.data.model.*
import com.xiaoming.closie.data.repository.WardrobeRepository
import com.xiaoming.closie.ui.*
import java.io.File
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class)
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
    if (v == null) { back(); return }

    val itemWears = ws.filter { it.itemId == id }.sortedByDescending { it.date }
    val itemWash = xs.filter { it.itemId == id }.sortedByDescending { it.date }
    val wears = itemWears.size

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(v.name) },
                navigationIcon = { BackButton(back) },
                actions = {
                    TextButton(onClick = { edit(id) }) { Text("编辑") }
                    TextButton(onClick = { deleting = true }) { Text("删除") }
                }
            )
        }
    ) { pad ->
        LazyColumn(
            Modifier.padding(pad).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                v.images.firstOrNull { it.kind == ImageKind.FLAT }?.localPath?.let {
                    AsyncImage(File(it), v.name, Modifier.fillMaxWidth().height(300.dp), contentScale = ContentScale.Fit)
                }
            }
            item {
                Section("图片") {
                    ImageKind.entries.filter { it != ImageKind.FLAT }.forEach { k ->
                        v.images.filter { it.kind == k }.forEach {
                            it.localPath?.let { p ->
                                AsyncImage(File(p), k.name, Modifier.fillMaxWidth().height(180.dp), contentScale = ContentScale.Crop)
                            }
                        }
                    }
                }
            }
            item {
                Section("购买信息") {
                    Text("类别：${v.category} ${v.subcategory}")
                    Text("品牌：${v.brand} · 店铺：${v.store} · 平台：${v.purchasePlatform}")
                    Text("购买价：${v.price ?: "未填写"} · 原价：${v.originalPrice ?: "未填写"}")
                    Text("购买日期：${v.purchaseDate} · 尺码：${v.sizeLabel}")
                    Text("安全类别：${v.safetyCategory}")
                    Text("评价：${v.comment}")
                    Text("评分：${ratingText(v.rating)}")
                    if (v.productUrl.isNotBlank()) {
                        Text("商品链接：${v.productUrl}")
                        OutlinedButton(onClick = {
                            runCatching {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(v.productUrl)))
                            }
                        }) { Text("打开商品链接") }
                    }
                    if (v.status == ItemStatus.RETURNED) Text("退货原因：${v.returnReason}")
                }
            }
            item {
                Section("面料与尺寸") {
                    v.materials.forEach { Text("${it.name} ${it.percentage}") }
                    v.measurements.forEach { Text("${it.name} ${it.value}${it.unit}") }
                }
            }
            if (v.status == ItemStatus.OWNED) {
                item {
                    Section("使用记录") {
                        Text("穿着 $wears 次 · 洗涤 ${itemWash.size} 次")
                        Text("单次穿着成本：" + costPerWear(v.price, wears))
                        val lastWear = itemWears.firstOrNull()?.date
                        val lastWash = itemWash.firstOrNull()?.date
                        if (lastWear != null) Text("最近穿着：$lastWear")
                        if (lastWash != null) Text("最近洗涤：$lastWash")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { repo.addWear(id, LocalDate.now().toString()) }) { Text("今天穿了 +1") }
                            OutlinedButton(onClick = { repo.addWash(id, LocalDate.now().toString()) }) { Text("洗过 +1") }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = {
                                val d = LocalDate.now()
                                DatePickerDialog(context, { _, y, m, day ->
                                    repo.addWear(id, "%04d-%02d-%02d".format(y, m + 1, day), WearSource.MANUAL)
                                }, d.year, d.monthValue - 1, d.dayOfMonth).show()
                            }) { Text("添加穿着记录") }
                            OutlinedButton(onClick = {
                                val d = LocalDate.now()
                                DatePickerDialog(context, { _, y, m, day ->
                                    repo.addWash(id, "%04d-%02d-%02d".format(y, m + 1, day))
                                }, d.year, d.monthValue - 1, d.dayOfMonth).show()
                            }) { Text("添加洗涤记录") }
                        }
                    }
                }
                item {
                    Section("穿着记录") {
                        if (itemWears.isEmpty()) {
                            Text("还没有穿着记录", color = Rose)
                        } else {
                            val visible = if (showAllWears) itemWears else itemWears.take(15)
                            visible.forEach { w ->
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Text(w.date, Modifier.weight(1f))
                                    Text(
                                        if (w.source == WearSource.OOTD) "来自 OOTD" else "手动记录",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (w.source == WearSource.OOTD) Rose else MaterialTheme.colorScheme.onSurface
                                    )
                                    if (w.source == WearSource.MANUAL) {
                                        TextButton(onClick = { repo.deleteWearEvent(w.id) }) { Text("删除", color = MaterialTheme.colorScheme.error) }
                                    }
                                }
                            }
                            if (itemWears.size > 15) {
                                TextButton(onClick = { showAllWears = !showAllWears }) { Text(if (showAllWears) "收起" else "查看全部（${itemWears.size}）") }
                            }
                        }
                    }
                }
                item {
                    Section("洗涤记录") {
                        if (itemWash.isEmpty()) {
                            Text("还没有洗涤记录", color = Rose)
                        } else {
                            val visible = if (showAllWash) itemWash else itemWash.take(15)
                            visible.forEach { w ->
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Text(w.date, Modifier.weight(1f))
                                    TextButton(onClick = { repo.deleteWashEvent(w.id) }) { Text("删除", color = MaterialTheme.colorScheme.error) }
                                }
                            }
                            if (itemWash.size > 15) {
                                TextButton(onClick = { showAllWash = !showAllWash }) { Text(if (showAllWash) "收起" else "查看全部（${itemWash.size}）") }
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
            confirmButton = { TextButton(onClick = { repo.deleteItem(id); back() }) { Text("删除") } },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text("取消") } }
        )
    }
}

private fun costPerWear(price: Double?, wears: Int): String = when {
    price == null -> "未填写价格"
    wears == 0 -> "尚未穿着"
    else -> "¥" + "%.2f".format(price / wears)
}

private fun ratingText(r: Int): String = if (r <= 0) "未评分" else "★".repeat(r) + "☆".repeat(5 - r)
