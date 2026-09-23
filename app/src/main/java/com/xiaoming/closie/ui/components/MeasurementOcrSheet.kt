package com.xiaoming.closie.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import com.xiaoming.closie.data.model.Measurement
import com.xiaoming.closie.data.ocr.ParsedMeasurement
import com.xiaoming.closie.ui.theme.ClosieColor

private data class MeasureRow(
    val name: String,
    val value: String,
    val unit: String,
    val enabled: Boolean
)

/**
 * Confirmation sheet shown after OCR-ing a size chart. Nothing is written until the user taps
 * 应用; each row can be edited, unchecked, or have its unit changed. Unmatched raw text is kept
 * below so the user can transcribe values manually.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeasurementOcrSheet(
    parsed: List<ParsedMeasurement>,
    rawText: String,
    onApply: (List<Measurement>) -> Unit,
    onDismiss: () -> Unit
) {
    var rows by remember(parsed) {
        mutableStateOf(parsed.map { MeasureRow(it.name, it.value, it.unit, true) })
    }
    val enabledCount = rows.count { it.enabled }

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
            Text(
                "识别到 ${parsed.size} 项",
                style = MaterialTheme.typography.titleLarge,
                color = ClosieColor.Ink
            )

            if (rows.isEmpty()) {
                Text("没有自动匹配到尺寸，可在下方原始文本中手动录入。", style = MaterialTheme.typography.bodyMedium, color = ClosieColor.Graphite)
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(rows, key = { it.name }) { row ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Checkbox(
                                checked = row.enabled,
                                onCheckedChange = { checked ->
                                    rows = rows.map { if (it.name == row.name) it.copy(enabled = checked) else it }
                                }
                            )
                            Text(
                                row.name,
                                style = MaterialTheme.typography.bodyLarge,
                                color = ClosieColor.Ink,
                                modifier = Modifier.width(64.dp)
                            )
                            OutlinedTextField(
                                value = row.value,
                                onValueChange = { v ->
                                    rows = rows.map { if (it.name == row.name) it.copy(value = v) else it }
                                },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                shape = RoundedCornerShape(10.dp),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                textStyle = MaterialTheme.typography.bodyLarge
                            )
                            Text(
                                row.unit,
                                style = MaterialTheme.typography.bodyMedium,
                                color = ClosieColor.Fig,
                                modifier = Modifier
                                    .clickable {
                                        val next = when (row.unit) {
                                            "cm" -> "mm"
                                            "mm" -> "inch"
                                            else -> "cm"
                                        }
                                        rows = rows.map { if (it.name == row.name) it.copy(unit = next) else it }
                                    }
                                    .padding(4.dp)
                            )
                        }
                    }
                }
            }

            if (rawText.isNotBlank()) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("未自动匹配", style = MaterialTheme.typography.titleSmall, color = ClosieColor.Ink)
                    Text(
                        rawText,
                        style = MaterialTheme.typography.bodySmall,
                        color = ClosieColor.Graphite,
                        modifier = Modifier.heightIn(max = 120.dp)
                    )
                }
            }

            Button(
                onClick = {
                    onApply(rows.filter { it.enabled }.map { Measurement(name = it.name, value = it.value, unit = it.unit) })
                },
                enabled = enabledCount > 0,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = ClosieColor.Fig, contentColor = ClosieColor.Paper)
            ) { Text("应用 $enabledCount 项") }
        }
    }
}
