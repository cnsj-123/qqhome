package com.qq.closie.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.qq.closie.data.ProductImporter
import com.qq.closie.data.ProductLinkExtractor
import com.qq.closie.data.ProductPreview
import com.qq.closie.ui.theme.ClosieColor
import kotlinx.coroutines.launch

/**
 * Standalone "从商品链接导入" sheet. Paste any share message (Taobao / JD / brand site), tap
 * 识别商品, review the best-effort preview, then apply. Network fetching only starts when the user
 * explicitly taps the button.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LinkImportSheet(
    initialText: String = "",
    onDismiss: () -> Unit,
    onApply: (ProductPreview) -> Unit
) {
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf(initialText) }
    var importing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf<ProductPreview?>(null) }

    val url = remember(text) { ProductLinkExtractor.extractFirstHttpUrl(text) }
    val platform = remember(url) { url?.let { ProductImporter.detectPlatform(it) }.orEmpty() }

    fun recognize() {
        if (text.isBlank() || importing) return
        importing = true
        error = null
        preview = null
        scope.launch {
            ProductImporter.import(text)
                .onSuccess { preview = it }
                .onFailure { error = it.message ?: "解析失败，请检查链接或网络" }
            importing = false
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = ClosieColor.Surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("从商品链接导入", style = MaterialTheme.typography.titleLarge, color = ClosieColor.Ink)

            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("粘贴淘宝 / 京东 / 品牌官网分享内容", color = ClosieColor.InkTertiary) },
                minLines = 2,
                maxLines = 4,
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = ClosieColor.Fig,
                    unfocusedBorderColor = ClosieColor.Hairline
                )
            )

            if (url != null) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        "检测到：${platform.ifBlank { "商品链接" }}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = ClosieColor.Graphite
                    )
                    Text(url, style = MaterialTheme.typography.bodySmall, color = ClosieColor.Stone, maxLines = 1)
                }
            }

            Button(
                onClick = ::recognize,
                enabled = !importing && text.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = ClosieColor.Fig, contentColor = ClosieColor.Paper)
            ) { Text(if (importing) "识别中…" else "识别商品") }

            error?.let { Text(it, color = ClosieColor.Error, style = MaterialTheme.typography.bodySmall) }

            preview?.let { p ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(ClosieColor.FigSoft, RoundedCornerShape(16.dp))
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("识别结果", style = MaterialTheme.typography.titleMedium, color = ClosieColor.Ink)
                    if (p.title.isNotBlank()) Text(p.title, style = MaterialTheme.typography.bodyLarge, color = ClosieColor.Ink)
                    val meta = listOfNotNull(
                        p.price?.let { "¥${priceText(it)}" },
                        p.brand.takeIf { it.isNotBlank() },
                        p.platform.takeIf { it.isNotBlank() }
                    ).joinToString(" · ")
                    if (meta.isNotBlank()) Text(meta, style = MaterialTheme.typography.bodyMedium, color = ClosieColor.Graphite)
                    p.imageUrl?.let { image ->
                        AsyncImage(
                            model = image,
                            contentDescription = "商品主图",
                            modifier = Modifier.fillMaxWidth().height(120.dp),
                            contentScale = ContentScale.Fit
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { onApply(p) },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = ClosieColor.Fig, contentColor = ClosieColor.Paper)
                        ) { Text("应用信息") }
                        OutlinedButton(
                            onClick = { preview = null; error = null },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp)
                        ) { Text("重新识别") }
                    }
                }
            }
        }
    }
}
