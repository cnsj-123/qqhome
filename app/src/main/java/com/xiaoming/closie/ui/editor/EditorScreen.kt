package com.xiaoming.closie.ui.editor

import android.app.DatePickerDialog
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.xiaoming.closie.data.ImageStore
import com.xiaoming.closie.data.ProductImporter
import com.xiaoming.closie.data.ProductPreview
import com.xiaoming.closie.data.model.*
import com.xiaoming.closie.data.repository.WardrobeRepository
import com.xiaoming.closie.ui.BackButton
import com.xiaoming.closie.ui.Rose
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(repo: WardrobeRepository, itemId: String?, initialStatus: String?, done: () -> Unit) {
    val context = LocalContext.current
    val allItems by repo.items.collectAsState()
    val original = allItems.firstOrNull { it.id == itemId }
    var item by remember(itemId) {
        mutableStateOf(original ?: ClothingItem(
            status = if (initialStatus == "RETURNED") ItemStatus.RETURNED else ItemStatus.OWNED,
            purchaseDate = LocalDate.now().toString()
        ))
    }
    LaunchedEffect(original?.updatedAt) { if (original != null) item = original }

    val pendingPaths = remember(itemId) { mutableStateListOf<String>() }
    val pendingAtDispose by rememberUpdatedState(pendingPaths.toList())
    DisposableEffect(itemId) {
        onDispose { pendingAtDispose.forEach { ImageStore.deletePrivatePath(context, it) } }
    }

    var currentKind by remember { mutableStateOf(ImageKind.FLAT) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { selected ->
            ImageStore.copyFromUri(context, selected)?.let { path ->
                pendingPaths += path
                item = item.copy(images = item.images + ClothingImage(kind = currentKind, localPath = path))
            }
        }
    }

    var importing by remember { mutableStateOf(false) }
    var importError by remember { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf<ProductPreview?>(null) }
    val scope = rememberCoroutineScope()

    fun applyPreview(p: ProductPreview) {
        var updated = item
        if (item.name.isBlank() && p.title.isNotBlank()) updated = updated.copy(name = p.title)
        if (item.price == null) p.price?.let { updated = updated.copy(price = it) }
        if (item.originalPrice == null) p.originalPrice?.let { updated = updated.copy(originalPrice = it) }
        if (item.brand.isBlank() && p.brand.isNotBlank()) updated = updated.copy(brand = p.brand)
        if (item.store.isBlank() && p.store.isNotBlank()) updated = updated.copy(store = p.store)
        if (item.purchasePlatform.isBlank() && p.platform.isNotBlank()) updated = updated.copy(purchasePlatform = p.platform)
        item = updated
        val imageUrl = p.imageUrl
        preview = null
        if (!imageUrl.isNullOrBlank()) {
            scope.launch {
                val path = withContext(Dispatchers.IO) { ImageStore.copyFromUrl(context, imageUrl) }
                if (path != null) {
                    pendingPaths += path
                    item = item.copy(images = item.images + ClothingImage(kind = ImageKind.PRODUCT, localPath = path))
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (original == null) "添加衣服" else "编辑衣服") },
                navigationIcon = { BackButton(done) }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = item.status == ItemStatus.OWNED, onClick = { item = item.copy(status = ItemStatus.OWNED) }, label = { Text("OWNED") })
                    FilterChip(selected = item.status == ItemStatus.RETURNED, onClick = { item = item.copy(status = ItemStatus.RETURNED) }, label = { Text("RETURNED") })
                }
                EditText("名称 *", item.name) { item = item.copy(name = it) }
                EditText("类别", item.category) { item = item.copy(category = it) }
                EditText("子类别", item.subcategory) { item = item.copy(subcategory = it) }
            }
            item {
                SectionImages(
                    images = item.images,
                    add = { kind -> currentKind = kind; picker.launch(arrayOf("image/*")) },
                    remove = { id -> item = item.copy(images = item.images.filterNot { it.id == id }) }
                )
            }
            item {
                Text("购买信息", style = MaterialTheme.typography.titleMedium)
                EditText("品牌", item.brand) { item = item.copy(brand = it) }
                EditText("购买店铺", item.store) { item = item.copy(store = it) }
                EditText("购买平台", item.purchasePlatform) { item = item.copy(purchasePlatform = it) }
                EditText("商品链接", item.productUrl) { item = item.copy(productUrl = it) }
                Button(
                    onClick = {
                        val url = item.productUrl.trim()
                        if (url.isEmpty()) { importError = "请先填写商品链接"; return@Button }
                        importing = true
                        importError = null
                        preview = null
                        scope.launch {
                            val result = ProductImporter.fetch(url)
                            result.onSuccess { p -> preview = p }
                                .onFailure { e -> importError = e.message ?: "解析失败，请检查链接或网络" }
                            importing = false
                        }
                    },
                    enabled = !importing,
                    modifier = Modifier.fillMaxWidth()
                ) { Text(if (importing) "解析中…" else "解析商品") }
                preview?.let { p ->
                    ProductPreviewCard(
                        preview = p,
                        onApply = { applyPreview(p) },
                        onDismiss = { preview = null }
                    )
                }
                importError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                EditText("购买价", item.price?.toString().orEmpty()) { item = item.copy(price = it.toDoubleOrNull()) }
                EditText("原价（可选）", item.originalPrice?.toString().orEmpty()) { item = item.copy(originalPrice = it.toDoubleOrNull()) }
                OutlinedButton(onClick = {
                    val date = runCatching { LocalDate.parse(item.purchaseDate) }.getOrDefault(LocalDate.now())
                    DatePickerDialog(context, { _, year, month, day ->
                        item = item.copy(purchaseDate = "%04d-%02d-%02d".format(year, month + 1, day))
                    }, date.year, date.monthValue - 1, date.dayOfMonth).show()
                }) { Text("购买日期：${item.purchaseDate}") }
            }
            item {
                EditText("尺码标签", item.sizeLabel) { item = item.copy(sizeLabel = it) }
                EditText("安全类别", item.safetyCategory) { item = item.copy(safetyCategory = it) }
                EditText("我的评价", item.comment) { item = item.copy(comment = it) }
                if (item.status == ItemStatus.RETURNED) EditText("退货原因", item.returnReason) { item = item.copy(returnReason = it) }
            }
            item {
                DynamicMaterials(item.materials) { item = item.copy(materials = it) }
                DynamicMeasures(item.measurements) { item = item.copy(measurements = it) }
            }
            item {
                Button(
                    enabled = item.name.isNotBlank(),
                    onClick = {
                        val retained = item.images.mapNotNull { it.localPath }.toSet()
                        pendingPaths.filterNot { it in retained }.forEach { ImageStore.deletePrivatePath(context, it) }
                        if (original == null) repo.createItem(item) else repo.updateItem(item)
                        pendingPaths.clear()
                        done()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Rose)
                ) { Text("保存") }
            }
        }
    }
}

@Composable
private fun EditText(label: String, value: String, set: (String) -> Unit) {
    OutlinedTextField(value = value, onValueChange = set, label = { Text(label) }, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun SectionImages(images: List<ClothingImage>, add: (ImageKind) -> Unit, remove: (String) -> Unit) {
    Text("图片", style = MaterialTheme.typography.titleMedium)
    ImageKind.entries.forEach { kind ->
        Text(kind.name, style = MaterialTheme.typography.labelLarge)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(images.filter { it.kind == kind }, key = { it.id }) { image ->
                Column(Modifier.width(112.dp)) {
                    image.localPath?.let { path ->
                        AsyncImage(
                            model = File(path),
                            contentDescription = kind.name,
                            modifier = Modifier.fillMaxWidth().height(112.dp),
                            contentScale = if (kind == ImageKind.FLAT) ContentScale.Fit else ContentScale.Crop
                        )
                    }
                    TextButton(onClick = { remove(image.id) }, modifier = Modifier.fillMaxWidth()) { Text("删除") }
                }
            }
            item { OutlinedButton(onClick = { add(kind) }) { Text("添加") } }
        }
    }
}

@Composable
private fun DynamicMaterials(values: List<MaterialPart>, set: (List<MaterialPart>) -> Unit) {
    Text("面料成分", style = MaterialTheme.typography.titleMedium)
    values.forEach { row ->
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(row.name, { value -> set(values.map { if (it.id == row.id) it.copy(name = value) else it }) }, label = { Text("成分") }, modifier = Modifier.weight(1f))
            OutlinedTextField(row.percentage, { value -> set(values.map { if (it.id == row.id) it.copy(percentage = value) else it }) }, label = { Text("百分比") }, modifier = Modifier.weight(1f))
            TextButton(onClick = { set(values.filterNot { it.id == row.id }) }) { Text("删除") }
        }
    }
    OutlinedButton(onClick = { set(values + MaterialPart()) }) { Text("+ 添加面料") }
}

@Composable
private fun DynamicMeasures(values: List<Measurement>, set: (List<Measurement>) -> Unit) {
    Text("具体尺寸", style = MaterialTheme.typography.titleMedium)
    values.forEach { row ->
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(row.name, { value -> set(values.map { if (it.id == row.id) it.copy(name = value) else it }) }, label = { Text("尺寸名") }, modifier = Modifier.weight(1f))
            OutlinedTextField(row.value, { value -> set(values.map { if (it.id == row.id) it.copy(value = value) else it }) }, label = { Text("数值") }, modifier = Modifier.weight(1f))
            TextButton(onClick = { set(values.filterNot { it.id == row.id }) }) { Text("删除") }
        }
    }
    OutlinedButton(onClick = { set(values + Measurement()) }) { Text("+ 添加尺寸") }
}

@Composable
private fun ProductPreviewCard(preview: ProductPreview, onApply: () -> Unit, onDismiss: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFF3E5E7))) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("识别结果", style = MaterialTheme.typography.titleMedium)
            if (preview.title.isNotBlank()) Text("名称：${preview.title}", style = MaterialTheme.typography.bodySmall)
            if (preview.price != null) Text("价格：¥${preview.price}", style = MaterialTheme.typography.bodySmall)
            if (preview.originalPrice != null) Text("原价：¥${preview.originalPrice}", style = MaterialTheme.typography.bodySmall)
            if (preview.brand.isNotBlank()) Text("品牌：${preview.brand}", style = MaterialTheme.typography.bodySmall)
            if (preview.store.isNotBlank()) Text("店铺：${preview.store}", style = MaterialTheme.typography.bodySmall)
            if (preview.platform.isNotBlank()) Text("平台：${preview.platform}", style = MaterialTheme.typography.bodySmall)
            preview.imageUrl?.let { url ->
                AsyncImage(
                    model = url,
                    contentDescription = "商品主图",
                    modifier = Modifier.fillMaxWidth().height(120.dp),
                    contentScale = ContentScale.Fit
                )
            }
            Text("仅填充当前为空的字段", color = Rose, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onApply, modifier = Modifier.weight(1f)) { Text("应用识别结果") }
                OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("放弃") }
            }
        }
    }
}
