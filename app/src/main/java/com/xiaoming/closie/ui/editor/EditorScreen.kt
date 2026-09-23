package com.xiaoming.closie.ui.editor

import android.app.DatePickerDialog
import android.content.ClipboardManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.xiaoming.closie.data.ImageStore
import com.xiaoming.closie.data.PendingImport
import com.xiaoming.closie.data.PendingProduct
import com.xiaoming.closie.data.ProductImporter
import com.xiaoming.closie.data.ProductLinkExtractor
import com.xiaoming.closie.data.ProductPreview
import com.xiaoming.closie.data.model.*
import com.xiaoming.closie.data.repository.WardrobeRepository
import com.xiaoming.closie.ui.components.ClosieCompactTopBar
import com.xiaoming.closie.ui.components.ClosieFilterChip
import com.xiaoming.closie.ui.components.ClosieImageTile
import com.xiaoming.closie.ui.components.EditorSection
import com.xiaoming.closie.ui.components.LinkImportSheet
import com.xiaoming.closie.ui.components.SearchableChoiceSheet
import com.xiaoming.closie.ui.components.SmartPickerField
import com.xiaoming.closie.ui.theme.ClosieColor
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(repo: WardrobeRepository, itemId: String?, initialStatus: String?, done: () -> Unit) {
    val context = LocalContext.current
    val allItems by repo.items.collectAsState()
    val original = allItems.firstOrNull { it.id == itemId }
    val initialItem = remember(itemId, initialStatus) {
        original ?: ClothingItem(
            status = if (initialStatus == "RETURNED") ItemStatus.RETURNED else ItemStatus.OWNED,
            category = "",
            purchaseDate = LocalDate.now().toString()
        )
    }
    var item by remember(itemId, initialStatus) { mutableStateOf(initialItem) }

    val initialPrice = initialItem.price?.let { priceString(it) } ?: ""
    val initialOriginalPrice = initialItem.originalPrice?.let { priceString(it) } ?: ""
    var priceField by remember(itemId, initialStatus) { mutableStateOf(initialPrice) }
    var originalPriceField by remember(itemId, initialStatus) { mutableStateOf(initialOriginalPrice) }

    LaunchedEffect(original?.updatedAt) {
        if (original != null) {
            item = original
            priceField = priceString(original.price)
            originalPriceField = priceString(original.originalPrice)
        }
    }

    val dirty = item != initialItem || priceField != initialPrice || originalPriceField != initialOriginalPrice
    var showDiscardDialog by remember { mutableStateOf(false) }

    fun requestBack() {
        if (dirty) showDiscardDialog = true else done()
    }

    BackHandler(enabled = dirty) { showDiscardDialog = true }

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
    var productImageStatus by remember { mutableStateOf(ProductImageStatus.IDLE) }
    val scope = rememberCoroutineScope()

    var showImportSheet by remember { mutableStateOf(false) }
    var importSheetText by remember { mutableStateOf("") }
    var clipboardSuggestion by remember { mutableStateOf<String?>(null) }

    // "分享至 Closie" deep link + quick-capture "保存并继续编辑" draft + clipboard suggestion.
    LaunchedEffect(Unit) {
        PendingImport.text?.let { t ->
            PendingImport.text = null
            importSheetText = t
            showImportSheet = true
        }
        PendingProduct.draft?.let { d ->
            PendingProduct.draft = null
            if (item.name.isBlank() && d.name.isNotBlank()) item = item.copy(name = d.name)
            if (item.price == null) d.price?.let { item = item.copy(price = it); priceField = priceString(it) }
            if (item.purchasePlatform.isBlank() && d.platform.isNotBlank()) item = item.copy(purchasePlatform = d.platform)
            d.screenshotPath?.let { path ->
                val file = File(path)
                ImageStore.copyFromFile(context, file)?.let { copied ->
                    pendingPaths += copied
                    item = item.copy(images = item.images + ClothingImage(kind = ImageKind.PRODUCT, localPath = copied))
                }
                runCatching { file.delete() }
            }
        }
        val clip = runCatching {
            val cm = context.getSystemService(ClipboardManager::class.java)
            cm?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
        }.getOrNull()
        if (!clip.isNullOrBlank() && ProductLinkExtractor.extractFirstHttpUrl(clip) != null) {
            clipboardSuggestion = clip
        }
    }

    val existing = remember(allItems) { collectExistingValues(allItems) }

    var activeSheet by remember { mutableStateOf<EditorSheet?>(null) }
    var editingMaterialId by remember { mutableStateOf<String?>(null) }
    var editingMeasurementId by remember { mutableStateOf<String?>(null) }
    var showAddImageSheet by remember { mutableStateOf(false) }

    var expandPurchase by remember { mutableStateOf(false) }
    var expandDetails by remember { mutableStateOf(false) }
    var expandRecords by remember { mutableStateOf(false) }
    var expandReturn by remember(itemId, initialStatus) { mutableStateOf(item.status == ItemStatus.RETURNED) }

    fun setStatus(status: ItemStatus) {
        item = item.copy(status = status)
        if (status == ItemStatus.RETURNED) expandReturn = true
    }

    fun applyPreview(p: ProductPreview) {
        var updated = item
        if (item.name.isBlank() && p.title.isNotBlank()) updated = updated.copy(name = p.title)
        if (item.price == null) p.price?.let { updated = updated.copy(price = it); priceField = priceString(it) }
        if (item.originalPrice == null) p.originalPrice?.let { updated = updated.copy(originalPrice = it); originalPriceField = priceString(it) }
        if (item.brand.isBlank() && p.brand.isNotBlank()) updated = updated.copy(brand = p.brand)
        if (item.store.isBlank() && p.store.isNotBlank()) updated = updated.copy(store = p.store)
        if (item.purchasePlatform.isBlank() && p.platform.isNotBlank()) updated = updated.copy(purchasePlatform = p.platform)
        if (item.productUrl.isBlank() && p.url.isNotBlank()) updated = updated.copy(productUrl = p.url)
        item = updated
        val imageUrl = p.imageUrl
        preview = null
        if (imageUrl.isNullOrBlank()) {
            productImageStatus = ProductImageStatus.IDLE
            return
        }
        if (updated.images.any { it.remoteUrl == imageUrl }) {
            productImageStatus = ProductImageStatus.DONE
            return
        }
        productImageStatus = ProductImageStatus.DOWNLOADING
        scope.launch {
            var downloadedPath: String? = null
            try {
                downloadedPath = withContext(Dispatchers.IO) { ImageStore.copyFromUrl(context, imageUrl) }
                ensureActive()
                if (downloadedPath != null) {
                    pendingPaths += downloadedPath
                    item = item.copy(images = item.images + ClothingImage(kind = ImageKind.PRODUCT, localPath = downloadedPath, remoteUrl = imageUrl))
                    productImageStatus = ProductImageStatus.DONE
                    downloadedPath = null
                } else {
                    productImageStatus = ProductImageStatus.FAILED
                }
            } finally {
                downloadedPath?.let { ImageStore.deletePrivatePath(context, it) }
            }
        }
    }

    val priceValid = isValidPrice(priceField)
    val originalPriceValid = isValidPrice(originalPriceField)
    val materialsValid = item.materials.all { isValidPercentage(it.percentage) }
    val canSave = item.name.isNotBlank() && priceValid && originalPriceValid && materialsValid && productImageStatus != ProductImageStatus.DOWNLOADING

    fun save() {
        val final = item.copy(
            price = priceField.trim().toDoubleOrNull(),
            originalPrice = originalPriceField.trim().toDoubleOrNull(),
            materials = item.materials.map { it.copy(percentage = normalizePercentage(it.percentage)) }
        )
        val retained = final.images.mapNotNull { it.localPath }.toSet()
        pendingPaths.filterNot { it in retained }.forEach { ImageStore.deletePrivatePath(context, it) }
        if (original == null) repo.createItem(final) else repo.updateItem(final)
        pendingPaths.clear()
        done()
    }

    Scaffold(
        containerColor = ClosieColor.Canvas,
        topBar = {
            ClosieCompactTopBar(
                title = if (original == null) "添加衣服" else "编辑衣服",
                onBack = { requestBack() }
            )
        },
        bottomBar = {
            Surface(color = ClosieColor.Surface, tonalElevation = 0.dp, shadowElevation = 0.dp) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .imePadding()
                        .navigationBarsPadding()
                        .padding(horizontal = 20.dp, vertical = 12.dp)
                ) {
                    Button(
                        onClick = { save() },
                        enabled = canSave,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = ClosieColor.Rose,
                            contentColor = ClosieColor.Surface
                        )
                    ) {
                        Text(if (productImageStatus == ProductImageStatus.DOWNLOADING) "图片下载中…" else "保存")
                    }
                }
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("快速添加", style = MaterialTheme.typography.bodyMedium, color = ClosieColor.InkSecondary)
                    TextButton(onClick = { importSheetText = ""; showImportSheet = true }) {
                        Text("从链接导入", color = ClosieColor.Rose)
                    }
                }
            }

            if (clipboardSuggestion != null) {
                val clip = clipboardSuggestion.orEmpty()
                val url = ProductLinkExtractor.extractFirstHttpUrl(clip)
                if (url != null) {
                    item {
                        ClipboardSuggestionBanner(
                            platform = ProductImporter.detectPlatform(url),
                            host = runCatching { java.net.URI(url).host.orEmpty() }.getOrDefault(""),
                            onImport = {
                                importSheetText = clip
                                showImportSheet = true
                                clipboardSuggestion = null
                            },
                            onDismiss = { clipboardSuggestion = null }
                        )
                    }
                }
            }

            item { StatusSegment(status = item.status, onSelect = ::setStatus) }

            item {
                Spacer(Modifier.height(8.dp))
                ImageSection(
                    images = item.images,
                    currentKind = currentKind,
                    onSelectKind = { currentKind = it },
                    onAdd = { showAddImageSheet = true },
                    onRemove = { id -> item = item.copy(images = item.images.filterNot { it.id == id }) }
                )
            }

            item {
                Spacer(Modifier.height(8.dp))
                EditorTextField(
                    value = item.name,
                    onValueChange = { item = item.copy(name = it) },
                    placeholder = "名称 *",
                    imeAction = ImeAction.Next
                )
            }

            item {
                InlineChoiceChips(
                    label = "类别",
                    options = listOf("上衣", "下装", "外套", "鞋"),
                    selected = item.category,
                    onSelect = { item = item.copy(category = it) },
                    onMore = { activeSheet = EditorSheet.CATEGORY }
                )
            }
            item {
                SmartPickerField(
                    label = "子类别",
                    value = item.subcategory,
                    placeholder = "选择子类别",
                    onClick = { activeSheet = EditorSheet.SUBCATEGORY }
                )
            }
            item {
                SmartPickerField(
                    label = "品牌",
                    value = item.brand,
                    placeholder = "选择品牌",
                    onClick = { activeSheet = EditorSheet.BRAND }
                )
            }
            item {
                if (item.category in setOf("上衣", "外套", "裙装", "连体", "运动", "家居服", "内衣")) {
                    InlineChoiceChips(
                        label = "尺码",
                        options = listOf("XS", "S", "M", "L", "XL"),
                        selected = item.sizeLabel,
                        onSelect = { item = item.copy(sizeLabel = it) },
                        onMore = { activeSheet = EditorSheet.SIZE }
                    )
                } else {
                    SmartPickerField(
                        label = "尺码",
                        value = item.sizeLabel,
                        placeholder = "选择尺码",
                        onClick = { activeSheet = EditorSheet.SIZE }
                    )
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    EditorTextField(
                        value = priceField,
                        onValueChange = { priceField = it },
                        placeholder = "购买价",
                        keyboardType = KeyboardType.Decimal,
                        imeAction = ImeAction.Next
                    )
                    if (!priceValid) {
                        Text("请输入有效价格", color = ClosieColor.Error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            item {
                Spacer(Modifier.height(12.dp))
                val purchaseSummary = listOfNotNull(
                    item.store.takeIf { it.isNotBlank() },
                    item.purchasePlatform.takeIf { it.isNotBlank() },
                    item.purchaseDate.takeIf { it.isNotBlank() }
                ).joinToString(" · ").ifBlank { null }
                EditorSection(
                    title = "购买信息",
                    expanded = expandPurchase,
                    onToggle = { expandPurchase = !expandPurchase },
                    summary = purchaseSummary
                ) {
                    EditorTextField(
                        value = originalPriceField,
                        onValueChange = { originalPriceField = it },
                        placeholder = "原价（可选）",
                        keyboardType = KeyboardType.Decimal,
                        imeAction = ImeAction.Next
                    )
                    if (!originalPriceValid) {
                        Text("请输入有效价格", color = ClosieColor.Error, style = MaterialTheme.typography.bodySmall)
                    }
                    SmartPickerField(
                        label = "店铺",
                        value = item.store,
                        placeholder = "选择店铺",
                        onClick = { activeSheet = EditorSheet.STORE }
                    )
                    SmartPickerField(
                        label = "购买平台",
                        value = item.purchasePlatform,
                        placeholder = "选择平台",
                        onClick = { activeSheet = EditorSheet.PLATFORM }
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .clickable {
                                val date = runCatching { LocalDate.parse(item.purchaseDate) }.getOrDefault(LocalDate.now())
                                DatePickerDialog(context, { _, year, month, day ->
                                    item = item.copy(purchaseDate = "%04d-%02d-%02d".format(year, month + 1, day))
                                }, date.year, date.monthValue - 1, date.dayOfMonth).show()
                            },
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("购买日期", style = MaterialTheme.typography.bodyLarge, color = ClosieColor.Ink)
                        Text(
                            item.purchaseDate.ifBlank { "选择日期" },
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (item.purchaseDate.isBlank()) ClosieColor.InkTertiary else ClosieColor.Ink
                        )
                    }
                    EditorTextField(
                        value = item.productUrl,
                        onValueChange = { raw ->
                            // Auto-extract a URL from a pasted share message, but never break
                            // normal character-by-character typing.
                            item = item.copy(productUrl = ProductLinkExtractor.extractFirstHttpUrl(raw) ?: raw)
                        },
                        placeholder = "商品链接",
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Done
                    )
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
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = ClosieColor.SurfaceSoft,
                            contentColor = ClosieColor.Ink
                        )
                    ) { Text(if (importing) "识别中…" else "从链接识别") }
                    preview?.let { p ->
                        ProductPreviewCard(
                            preview = p,
                            onApply = { applyPreview(p) },
                            onDismiss = { preview = null }
                        )
                    }
                    importError?.let { Text(it, color = ClosieColor.Error, style = MaterialTheme.typography.bodySmall) }
                    when (productImageStatus) {
                        ProductImageStatus.DOWNLOADING -> Text("正在下载商品图片…", style = MaterialTheme.typography.bodySmall, color = ClosieColor.InkSecondary)
                        ProductImageStatus.DONE -> Text("已识别", color = ClosieColor.Rose, style = MaterialTheme.typography.bodySmall)
                        ProductImageStatus.FAILED -> Text("商品信息已应用，但图片下载失败，可以手动添加", color = ClosieColor.Error, style = MaterialTheme.typography.bodySmall)
                        ProductImageStatus.IDLE -> {}
                    }
                }
            }

            item {
                Spacer(Modifier.height(12.dp))
                EditorSection(
                    title = "衣物详情",
                    expanded = expandDetails,
                    onToggle = { expandDetails = !expandDetails }
                ) {
                    SmartPickerField(
                        label = "安全类别",
                        value = item.safetyCategory,
                        placeholder = "选择安全类别",
                        onClick = { activeSheet = EditorSheet.SAFETY }
                    )
                    Text("材质", style = MaterialTheme.typography.titleMedium, color = ClosieColor.Ink)
                    item.materials.forEach { part ->
                        MaterialRow(
                            part = part,
                            onNameClick = { editingMaterialId = part.id; activeSheet = EditorSheet.MATERIAL },
                            onPercentage = { value ->
                                item = item.copy(materials = item.materials.map { if (it.id == part.id) it.copy(percentage = value) else it })
                            },
                            onRemove = { item = item.copy(materials = item.materials.filterNot { it.id == part.id }) }
                        )
                    }
                    TextButton(onClick = { item = item.copy(materials = item.materials + MaterialPart()) }) {
                        Text("＋ 添加材质", color = ClosieColor.Rose)
                    }

                    Text("详细尺寸", style = MaterialTheme.typography.titleMedium, color = ClosieColor.Ink)
                    item.measurements.forEach { m ->
                        MeasurementRow(
                            m = m,
                            onNameClick = { editingMeasurementId = m.id; activeSheet = EditorSheet.MEASUREMENT },
                            onValue = { value ->
                                item = item.copy(measurements = item.measurements.map { if (it.id == m.id) it.copy(value = value) else it })
                            },
                            onUnitClick = { editingMeasurementId = m.id; activeSheet = EditorSheet.UNIT },
                            onRemove = { item = item.copy(measurements = item.measurements.filterNot { it.id == m.id }) }
                        )
                    }
                    TextButton(onClick = { item = item.copy(measurements = item.measurements + Measurement()) }) {
                        Text("＋ 添加尺寸", color = ClosieColor.Rose)
                    }
                }
            }

            item {
                Spacer(Modifier.height(12.dp))
                val recordsSummary = when {
                    item.rating > 0 -> "★".repeat(item.rating)
                    item.comment.isNotBlank() -> item.comment
                    else -> null
                }
                EditorSection(
                    title = "我的记录",
                    expanded = expandRecords,
                    onToggle = { expandRecords = !expandRecords },
                    summary = recordsSummary
                ) {
                    Text("评分", style = MaterialTheme.typography.bodyMedium, color = ClosieColor.InkSecondary)
                    RatingRow(rating = item.rating, onRating = { item = item.copy(rating = it) })
                    EditorTextField(
                        value = item.comment,
                        onValueChange = { item = item.copy(comment = it) },
                        placeholder = "版型、舒适度、搭配感受…",
                        singleLine = false,
                        imeAction = ImeAction.Done
                    )
                }
            }

            if (item.status == ItemStatus.RETURNED) {
                item {
                    Spacer(Modifier.height(12.dp))
                    EditorSection(
                        title = "退货信息",
                        expanded = expandReturn,
                        onToggle = { expandReturn = !expandReturn },
                        summary = item.returnReason.takeIf { it.isNotBlank() }
                    ) {
                        SmartPickerField(
                            label = "退货原因",
                            value = item.returnReason,
                            placeholder = "选择退货原因",
                            onClick = { activeSheet = EditorSheet.RETURN_REASON }
                        )
                    }
                }
            }

            item { Spacer(Modifier.height(8.dp)) }
        }
    }

    activeSheet?.let { sheet ->
        val cfg = when (sheet) {
            EditorSheet.CATEGORY -> SheetConfig(
                title = "选择类别",
                current = item.category,
                suggestions = mergeOptions(existing.categories, FieldCatalog.categories),
                preferred = existing.categories,
                onSelect = { item = item.copy(category = it) },
                onClear = { item = item.copy(category = "") }
            )
            EditorSheet.SUBCATEGORY -> SheetConfig(
                title = "选择子类别",
                current = item.subcategory,
                suggestions = mergeOptions(existing.subcategories, FieldCatalog.subcategoriesFor(item.category)),
                preferred = existing.subcategories,
                onSelect = { item = item.copy(subcategory = it) },
                onClear = { item = item.copy(subcategory = "") }
            )
            EditorSheet.BRAND -> SheetConfig(
                title = "选择品牌",
                current = item.brand,
                suggestions = mergeOptions(existing.brands, FieldCatalog.brands),
                preferred = existing.brands,
                onSelect = { item = item.copy(brand = it) },
                onClear = { item = item.copy(brand = "") }
            )
            EditorSheet.STORE -> SheetConfig(
                title = "选择店铺",
                current = item.store,
                suggestions = mergeOptions(existing.stores, FieldCatalog.stores),
                preferred = existing.stores,
                onSelect = { item = item.copy(store = it) },
                onClear = { item = item.copy(store = "") }
            )
            EditorSheet.PLATFORM -> SheetConfig(
                title = "选择购买平台",
                current = item.purchasePlatform,
                suggestions = mergeOptions(existing.platforms, FieldCatalog.platforms),
                preferred = existing.platforms,
                onSelect = { item = item.copy(purchasePlatform = it) },
                onClear = { item = item.copy(purchasePlatform = "") }
            )
            EditorSheet.SIZE -> SheetConfig(
                title = "选择尺码",
                current = item.sizeLabel,
                suggestions = mergeOptions(existing.sizes, FieldCatalog.sizesFor(item.category)),
                preferred = existing.sizes,
                onSelect = { item = item.copy(sizeLabel = it) },
                onClear = { item = item.copy(sizeLabel = "") }
            )
            EditorSheet.SAFETY -> SheetConfig(
                title = "选择安全类别",
                current = item.safetyCategory,
                suggestions = mergeOptions(existing.safetyCategories, FieldCatalog.safetyCategories),
                preferred = emptyList(),
                onSelect = { item = item.copy(safetyCategory = it) },
                onClear = { item = item.copy(safetyCategory = "") }
            )
            EditorSheet.RETURN_REASON -> SheetConfig(
                title = "选择退货原因",
                current = item.returnReason,
                suggestions = mergeOptions(existing.returnReasons, FieldCatalog.returnReasons),
                preferred = existing.returnReasons,
                onSelect = { item = item.copy(returnReason = it) },
                onClear = { item = item.copy(returnReason = "") }
            )
            EditorSheet.MATERIAL -> {
                val part = item.materials.firstOrNull { it.id == editingMaterialId }
                SheetConfig(
                    title = "选择材质",
                    current = part?.name ?: "",
                    suggestions = mergeOptions(existing.materials, FieldCatalog.materials),
                    preferred = existing.materials,
                    onSelect = { name ->
                        item = item.copy(materials = item.materials.map { if (it.id == editingMaterialId) it.copy(name = name) else it })
                    },
                    onClear = {
                        item = item.copy(materials = item.materials.map { if (it.id == editingMaterialId) it.copy(name = "") else it })
                    }
                )
            }
            EditorSheet.MEASUREMENT -> {
                val m = item.measurements.firstOrNull { it.id == editingMeasurementId }
                SheetConfig(
                    title = "选择尺寸名",
                    current = m?.name ?: "",
                    suggestions = mergeOptions(existing.measurementNames, FieldCatalog.measurementNamesFor(item.category)),
                    preferred = existing.measurementNames,
                    onSelect = { name ->
                        item = item.copy(measurements = item.measurements.map { if (it.id == editingMeasurementId) it.copy(name = name) else it })
                    },
                    onClear = {
                        item = item.copy(measurements = item.measurements.map { if (it.id == editingMeasurementId) it.copy(name = "") else it })
                    }
                )
            }
            EditorSheet.UNIT -> {
                val m = item.measurements.firstOrNull { it.id == editingMeasurementId }
                SheetConfig(
                    title = "选择单位",
                    current = m?.unit ?: "",
                    suggestions = mergeOptions(existing.units, FieldCatalog.units),
                    preferred = existing.units,
                    onSelect = { unit ->
                        item = item.copy(measurements = item.measurements.map { if (it.id == editingMeasurementId) it.copy(unit = unit) else it })
                    },
                    onClear = {
                        item = item.copy(measurements = item.measurements.map { if (it.id == editingMeasurementId) it.copy(unit = "cm") else it })
                    }
                )
            }
        }
        SearchableChoiceSheet(
            title = cfg.title,
            currentValue = cfg.current,
            suggestions = cfg.suggestions,
            preferredSuggestions = cfg.preferred,
            allowCustom = true,
            onSelect = cfg.onSelect,
            onClear = cfg.onClear,
            onDismiss = { activeSheet = null }
        )
    }

    if (showImportSheet) {
        LinkImportSheet(
            initialText = importSheetText,
            onDismiss = { showImportSheet = false },
            onApply = { p ->
                showImportSheet = false
                applyPreview(p)
            }
        )
    }

    if (showAddImageSheet) {
        AddImageSheet(
            onPick = { kind ->
                currentKind = kind
                showAddImageSheet = false
                picker.launch(arrayOf("image/*"))
            },
            onDismiss = { showAddImageSheet = false }
        )
    }

    if (showDiscardDialog) {
        AlertDialog(
            onDismissRequest = { showDiscardDialog = false },
            title = { Text("还有未保存的修改") },
            text = { Text("确定离开吗？未保存的修改和刚添加的图片会被丢弃。") },
            confirmButton = {
                TextButton(onClick = {
                    pendingPaths.forEach { ImageStore.deletePrivatePath(context, it) }
                    pendingPaths.clear()
                    done()
                }) { Text("放弃修改") }
            },
            dismissButton = { TextButton(onClick = { showDiscardDialog = false }) { Text("继续编辑") } }
        )
    }
}

private enum class ProductImageStatus { IDLE, DOWNLOADING, DONE, FAILED }

private enum class EditorSheet { CATEGORY, SUBCATEGORY, BRAND, STORE, PLATFORM, SIZE, SAFETY, RETURN_REASON, MATERIAL, MEASUREMENT, UNIT }

private data class SheetConfig(
    val title: String,
    val current: String,
    val suggestions: List<String>,
    val preferred: List<String>,
    val onSelect: (String) -> Unit,
    val onClear: (() -> Unit)?
)

private fun priceString(d: Double?): String =
    if (d == null) "" else if (d == d.toLong().toDouble()) d.toLong().toString() else d.toString()

private fun normalizePercentage(raw: String): String {
    val t = raw.trim()
    if (t.isEmpty() || t.endsWith("%")) return t
    val num = t.toDoubleOrNull()
    return if (num != null && num in 0.0..100.0) "$t%" else t
}

private fun isValidPercentage(raw: String): Boolean {
    val t = raw.trim()
    if (t.isEmpty()) return true
    val num = t.removeSuffix("%").trim().toDoubleOrNull()
    return num != null && num in 0.0..100.0
}

private fun isValidPrice(raw: String): Boolean {
    val t = raw.trim()
    if (t.isEmpty()) return true
    val value = t.toDoubleOrNull() ?: return false
    return value.isFinite() && value >= 0.0
}

private fun kindLabel(kind: ImageKind): String = when (kind) {
    ImageKind.FLAT -> "平铺"
    ImageKind.ME -> "我的上身"
    ImageKind.MODEL -> "模特"
    ImageKind.PRODUCT -> "商品"
}

@Composable
private fun EditorTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Next,
    singleLine: Boolean = true
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        placeholder = { Text(placeholder, color = ClosieColor.InkTertiary) },
        singleLine = singleLine,
        minLines = if (singleLine) 1 else 3,
        shape = RoundedCornerShape(12.dp),
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = ClosieColor.Rose,
            unfocusedBorderColor = ClosieColor.Hairline,
            cursorColor = ClosieColor.Rose,
            focusedTextColor = ClosieColor.Ink,
            unfocusedTextColor = ClosieColor.Ink
        )
    )
}

@Composable
private fun StatusSegment(status: ItemStatus, onSelect: (ItemStatus) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(ClosieColor.SurfaceSoft)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        SegmentOption("已拥有", status == ItemStatus.OWNED, Modifier.weight(1f)) { onSelect(ItemStatus.OWNED) }
        SegmentOption("试过 / 退货", status == ItemStatus.RETURNED, Modifier.weight(1f)) { onSelect(ItemStatus.RETURNED) }
    }
}

@Composable
private fun SegmentOption(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val bg = if (selected) ClosieColor.Rose else Color.Transparent
    val content = if (selected) ClosieColor.Surface else ClosieColor.InkSecondary
    Box(
        modifier = modifier
            .heightIn(min = 40.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = content)
    }
}

@Composable
private fun InlineChoiceChips(
    label: String,
    options: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
    onMore: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = ClosieColor.Ink)
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            options.forEach { option ->
                InlineChip(label = option, selected = selected == option) { onSelect(option) }
            }
            if (selected.isNotBlank() && selected !in options) {
                InlineChip(label = selected, selected = true, onClick = onMore)
            }
            InlineChip(label = "更多", selected = false, onClick = onMore)
        }
    }
}

@Composable
private fun InlineChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .heightIn(min = 44.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) ClosieColor.Ink else ClosieColor.Mist)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = if (selected) ClosieColor.Paper else ClosieColor.Graphite)
    }
}

@Composable
private fun RatingRow(rating: Int, onRating: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        (1..5).forEach { star ->
            IconButton(onClick = { onRating(if (rating == star) 0 else star) }, modifier = Modifier.size(44.dp)) {
                Text(
                    if (star <= rating) "★" else "☆",
                    color = if (star <= rating) ClosieColor.Rose else ClosieColor.InkTertiary,
                    fontSize = 20.sp
                )
            }
        }
        if (rating > 0) Text("$rating 分", style = MaterialTheme.typography.bodyMedium, color = ClosieColor.InkSecondary)
    }
}

@Composable
private fun ImageSection(
    images: List<ClothingImage>,
    currentKind: ImageKind,
    onSelectKind: (ImageKind) -> Unit,
    onAdd: () -> Unit,
    onRemove: (String) -> Unit
) {
    val mainImage = images.firstOrNull { it.kind == ImageKind.FLAT }
        ?: images.firstOrNull { it.kind == ImageKind.PRODUCT }
        ?: images.firstOrNull()

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ClosieImageTile(
            model = mainImage?.localPath?.let { File(it) },
            contentDescription = "主图",
            modifier = Modifier.fillMaxWidth().height(220.dp),
            contentScale = if (mainImage?.kind == ImageKind.FLAT) ContentScale.Fit else ContentScale.Crop,
            placeholder = {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("添加一张图片", style = MaterialTheme.typography.bodyMedium, color = ClosieColor.InkTertiary)
                }
            }
        )

        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ImageKind.entries.forEach { kind ->
                ClosieFilterChip(
                    selected = currentKind == kind,
                    onClick = { onSelectKind(kind) },
                    label = kindLabel(kind)
                )
            }
        }

        if (images.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(images, key = { it.id }) { img ->
                    Box {
                        AsyncImage(
                            model = img.localPath?.let { File(it) },
                            contentDescription = kindLabel(img.kind),
                            modifier = Modifier
                                .size(72.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(ClosieColor.SurfaceSoft),
                            contentScale = if (img.kind == ImageKind.FLAT) ContentScale.Fit else ContentScale.Crop
                        )
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .size(44.dp)
                                .clickable { onRemove(img.id) },
                            contentAlignment = Alignment.TopEnd
                        ) {
                            Box(
                                modifier = Modifier
                                    .padding(2.dp)
                                    .size(22.dp)
                                    .clip(RoundedCornerShape(999.dp))
                                    .background(ClosieColor.Ink.copy(alpha = 0.55f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "删除图片",
                                    tint = ClosieColor.Surface,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        TextButton(onClick = onAdd) { Text("＋ 添加图片", color = ClosieColor.Rose) }
    }
}

@Composable
private fun MaterialRow(
    part: MaterialPart,
    onNameClick: () -> Unit,
    onPercentage: (String) -> Unit,
    onRemove: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 44.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(ClosieColor.SurfaceSoft)
                    .clickable(onClick = onNameClick)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                Text(
                    if (part.name.isBlank()) "选择材质" else part.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (part.name.isBlank()) ClosieColor.InkTertiary else ClosieColor.Ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            OutlinedTextField(
                value = part.percentage,
                onValueChange = onPercentage,
                modifier = Modifier.width(92.dp),
                placeholder = { Text("占比", fontSize = 13.sp) },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = ClosieColor.Rose,
                    unfocusedBorderColor = ClosieColor.Hairline
                )
            )
            IconButton(onClick = onRemove, modifier = Modifier.size(44.dp)) {
                Icon(Icons.Default.Close, contentDescription = "删除材质", tint = ClosieColor.InkTertiary)
            }
        }
        if (!isValidPercentage(part.percentage)) {
            Text("请输入 0–100", color = ClosieColor.Error, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun MeasurementRow(
    m: Measurement,
    onNameClick: () -> Unit,
    onValue: (String) -> Unit,
    onUnitClick: () -> Unit,
    onRemove: () -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 44.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(ClosieColor.SurfaceSoft)
                .clickable(onClick = onNameClick)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            Text(
                if (m.name.isBlank()) "选择尺寸名" else m.name,
                style = MaterialTheme.typography.bodyMedium,
                color = if (m.name.isBlank()) ClosieColor.InkTertiary else ClosieColor.Ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        OutlinedTextField(
            value = m.value,
            onValueChange = onValue,
            modifier = Modifier.width(64.dp),
            placeholder = { Text("数值", fontSize = 13.sp) },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = ClosieColor.Rose,
                unfocusedBorderColor = ClosieColor.Hairline
            )
        )
        Box(
            modifier = Modifier
                .width(56.dp)
                .heightIn(min = 44.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(ClosieColor.SurfaceSoft)
                .clickable(onClick = onUnitClick),
            contentAlignment = Alignment.Center
        ) {
            Text(m.unit.ifBlank { "cm" }, style = MaterialTheme.typography.bodyMedium, color = ClosieColor.Ink, maxLines = 1)
        }
        IconButton(onClick = onRemove, modifier = Modifier.size(44.dp)) {
            Icon(Icons.Default.Close, contentDescription = "删除尺寸", tint = ClosieColor.InkTertiary)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddImageSheet(onPick: (ImageKind) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = ClosieColor.Surface) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 32.dp)) {
            Text("添加图片", style = MaterialTheme.typography.titleLarge, color = ClosieColor.Ink)
            Spacer(Modifier.height(8.dp))
            listOf(
                ImageKind.FLAT to "平铺图",
                ImageKind.ME to "我的上身图",
                ImageKind.MODEL to "模特图",
                ImageKind.PRODUCT to "商品图"
            ).forEach { (kind, label) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPick(kind) }
                        .heightIn(min = 48.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(label, style = MaterialTheme.typography.bodyLarge, color = ClosieColor.Ink)
                }
            }
        }
    }
}

@Composable
private fun ClipboardSuggestionBanner(
    platform: String,
    host: String,
    onImport: () -> Unit,
    onDismiss: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(ClosieColor.Mist)
            .padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text("检测到商品链接", style = MaterialTheme.typography.bodyMedium, color = ClosieColor.Ink)
            Text(
                listOfNotNull(platform.takeIf { it.isNotBlank() }, host.takeIf { it.isNotBlank() })
                    .joinToString(" · ").ifBlank { "剪贴板" },
                style = MaterialTheme.typography.bodySmall,
                color = ClosieColor.InkSecondary
            )
        }
        TextButton(onClick = onImport) { Text("从剪贴板导入", color = ClosieColor.Rose) }
        IconButton(onClick = onDismiss, modifier = Modifier.size(40.dp)) {
            Icon(Icons.Default.Close, contentDescription = "关闭", tint = ClosieColor.InkTertiary)
        }
    }
}

@Composable
private fun ProductPreviewCard(preview: ProductPreview, onApply: () -> Unit, onDismiss: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(ClosieColor.RoseSoft)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("已识别", style = MaterialTheme.typography.titleMedium, color = ClosieColor.Ink)
        if (preview.title.isNotBlank()) Text(preview.title, style = MaterialTheme.typography.bodyLarge, color = ClosieColor.Ink)
        val meta = listOfNotNull(
            preview.price?.let { "¥${priceString(it)}" },
            preview.brand.takeIf { it.isNotBlank() }
        ).joinToString(" · ")
        if (meta.isNotBlank()) Text(meta, style = MaterialTheme.typography.bodyMedium, color = ClosieColor.InkSecondary)
        preview.imageUrl?.let { url ->
            AsyncImage(
                model = url,
                contentDescription = "商品主图",
                modifier = Modifier.fillMaxWidth().height(120.dp),
                contentScale = ContentScale.Fit
            )
        }
        Text("仅填充当前为空的字段", color = ClosieColor.InkSecondary, style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = onApply,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = ClosieColor.Rose, contentColor = ClosieColor.Surface)
            ) { Text("应用信息") }
            OutlinedButton(
                onClick = onDismiss,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp)
            ) { Text("放弃") }
        }
    }
}
