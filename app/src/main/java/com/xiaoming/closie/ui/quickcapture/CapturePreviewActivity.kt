package com.xiaoming.closie.ui.quickcapture

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.xiaoming.closie.ClosieApplication
import com.xiaoming.closie.MainActivity
import com.xiaoming.closie.data.ImageStore
import com.xiaoming.closie.data.PendingProduct
import com.xiaoming.closie.data.PendingProductDraft
import com.xiaoming.closie.data.model.ClothingImage
import com.xiaoming.closie.data.model.ClothingItem
import com.xiaoming.closie.data.model.ImageKind
import com.xiaoming.closie.ui.theme.ClosieColor
import com.xiaoming.closie.ui.theme.ClosieTheme
import java.io.File
import java.time.LocalDate

/**
 * Compact confirmation overlay shown after a quick capture. The OCR result is a draft: nothing is
 * written to the wardrobe until the user chooses to save. "加入衣橱" persists immediately;
 * "保存并继续编辑" only hands a transient draft to the editor, which creates the item on save.
 */
class CapturePreviewActivity : ComponentActivity() {

    companion object {
        const val EXTRA_IMAGE_PATH = "imagePath"
        const val EXTRA_OCR_TEXT = "ocrText"
    }

    private var tempScreenshotPath: String = ""
    private var ownsTempScreenshot = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tempScreenshotPath = intent.getStringExtra(EXTRA_IMAGE_PATH).orEmpty()
        val ocrText = intent.getStringExtra(EXTRA_OCR_TEXT).orEmpty()
        val repository = (application as ClosieApplication).wardrobeRepository

        setContent {
            ClosieTheme {
                Surface(color = ClosieColor.Canvas) {
                    CapturePreviewContent(
                        repository = repository,
                        imagePath = tempScreenshotPath,
                        ocrText = ocrText,
                        onDeleteScreenshot = ::deleteOwnedScreenshot,
                        onAddToWardrobe = ::finishAfterSave,
                        onContinueEdit = ::finishAfterContinueEdit
                    )
                }
            }
        }
    }

    /** Deletes the temporary screenshot exactly once, guarding against double deletes. */
    private fun deleteOwnedScreenshot() {
        if (ownsTempScreenshot && tempScreenshotPath.isNotBlank()) {
            runCatching { File(tempScreenshotPath).delete() }
            ownsTempScreenshot = false
        }
    }

    private fun finishAfterSave() {
        // The content already transferred/deleted the screenshot before finishing.
        finish()
    }

    private fun finishAfterContinueEdit() {
        // Ownership of the screenshot transfers to PendingProduct; the editor copies it into
        // private storage and deletes the cache file.
        ownsTempScreenshot = false
        startActivity(
            Intent(this, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_OPEN_ADD, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        )
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Swiping the preview away (e.g. from recents) must not leave the shopping-page screenshot
        // behind. Skip this on configuration changes to avoid deleting a still-needed image.
        if (!isChangingConfigurations) {
            deleteOwnedScreenshot()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CapturePreviewContent(
    repository: com.xiaoming.closie.data.repository.WardrobeRepository,
    imagePath: String,
    ocrText: String,
    onDeleteScreenshot: () -> Unit,
    onAddToWardrobe: () -> Unit,
    onContinueEdit: () -> Unit
) {
    val context = LocalContext.current

    var name by remember { mutableStateOf(CaptureOcrParser.parseName(ocrText)) }
    var price by remember {
        mutableStateOf(
            CaptureOcrParser.parsePrice(ocrText)?.let { if (it == it.toLong().toDouble()) it.toLong().toString() else it.toString() } ?: ""
        )
    }
    var platform by remember { mutableStateOf("") }
    var saveImage by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }

    // Leaving without saving (Back / dismiss) must not leak the temporary screenshot.
    BackHandler { onDeleteScreenshot(); onAddToWardrobe() }

    fun addToWardrobe() {
        if (saving) return
        saving = true
        var productImage: ClothingImage? = null
        if (saveImage && imagePath.isNotBlank()) {
            ImageStore.copyFromFile(context, File(imagePath))?.let { path ->
                productImage = ClothingImage(kind = ImageKind.PRODUCT, localPath = path)
            }
        }
        repository.createItem(
            ClothingItem(
                name = name.trim(),
                price = price.trim().toDoubleOrNull(),
                purchasePlatform = platform.trim(),
                purchaseDate = LocalDate.now().toString(),
                images = listOfNotNull(productImage)
            )
        )
        onDeleteScreenshot()
        onAddToWardrobe()
    }

    fun continueEdit() {
        PendingProduct.draft = PendingProductDraft(
            name = name.trim(),
            price = price.trim().toDoubleOrNull(),
            platform = platform.trim(),
            screenshotPath = imagePath.takeIf { it.isNotBlank() }
        )
        onContinueEdit()
    }

    Scaffold(
        containerColor = ClosieColor.Canvas,
        topBar = {
            TopAppBar(
                title = { Text("识别到商品") },
                navigationIcon = {
                    IconButton(onClick = { onDeleteScreenshot(); onAddToWardrobe() }) {
                        Text("‹", style = MaterialTheme.typography.headlineMedium)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = ClosieColor.Canvas)
            )
        }
    ) { pad ->
        Column(
            modifier = Modifier
                .padding(pad)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (imagePath.isNotBlank()) {
                AsyncImage(
                    model = File(imagePath),
                    contentDescription = "采集截图",
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp)
                        .background(ClosieColor.Mist, RoundedCornerShape(16.dp)),
                    contentScale = ContentScale.Fit
                )
            }
            if (ocrText.isBlank()) {
                Text(
                    "没有从当前页面识别到文字。页面可能为空、尚未加载完成，或限制了屏幕捕获。",
                    color = ClosieColor.Graphite,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("商品名") },
                singleLine = true,
                shape = RoundedCornerShape(12.dp)
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = price,
                    onValueChange = { price = it },
                    modifier = Modifier.weight(1f),
                    label = { Text("价格") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                )
                OutlinedTextField(
                    value = platform,
                    onValueChange = { platform = it },
                    modifier = Modifier.weight(1f),
                    label = { Text("平台") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp)
                )
            }
            if (imagePath.isNotBlank()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(checked = saveImage, onCheckedChange = { saveImage = it })
                    Text("保存为商品图片", style = MaterialTheme.typography.bodyMedium, color = ClosieColor.Ink)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = { continueEdit() },
                    enabled = name.isNotBlank() && !saving,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)
                ) { Text("保存并继续编辑") }
                Button(
                    onClick = { addToWardrobe() },
                    enabled = name.isNotBlank() && !saving,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = ClosieColor.Fig, contentColor = ClosieColor.Paper)
                ) { Text("加入衣橱") }
            }
        }
    }
}
