package com.xiaoming.closie.ui.detail

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.*
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
    if (v == null) { back(); return }

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
                    val wears = ws.count { it.itemId == id }
                    Section("使用记录") {
                        Text("穿着 $wears 次 · 洗涤 ${xs.count { it.itemId == id }} 次")
                        Text("单次穿着成本：" + costPerWear(v.price, wears))
                        Button(onClick = { repo.addWear(id, LocalDate.now().toString()) }) { Text("今天穿了 +1") }
                        OutlinedButton(onClick = { repo.addWash(id, LocalDate.now().toString()) }) { Text("洗过 +1") }
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
