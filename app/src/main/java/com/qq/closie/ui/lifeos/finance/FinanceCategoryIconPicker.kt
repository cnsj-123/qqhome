package com.qq.closie.ui.lifeos.finance

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.qq.closie.life.ui.theme.BundledText as Text
import com.qq.closie.ui.lifeos.theme.LifeText

@Composable
internal fun FinanceCategoryIconPicker(selected: String, enabled: Boolean, recent: List<String>, onSelect: (String) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    Text("分类图标", style = LifeText.caption)
    Row {
        Icon(FinanceCategoryVectors.get(selected), null, Modifier.size(20.dp))
        Text("  " + (FinanceCategoryVectors.labels[selected] ?: "其他"), style = LifeText.body)
    }
    OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), enabled = enabled,
        label = { Text("搜索图标") }, singleLine = true)
    if (query.isBlank() && recent.isNotEmpty()) {
        Text("最近使用", style = LifeText.caption)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            recent.distinct().take(4).filter { it in FinanceCategoryVectors.labels }.forEach { key ->
                FilterChip(selected == key, { onSelect(key) }, enabled = enabled,
                    label = { Icon(FinanceCategoryVectors.get(key), FinanceCategoryVectors.labels[key], Modifier.size(20.dp)) })
            }
        }
    }
    FinanceCategoryVectors.labels.entries.filter { query.isBlank() || it.value.contains(query.trim()) || it.key.contains(query.trim(), true) }
        .chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { (key, label) ->
                    FilterChip(selected == key, { onSelect(key) }, enabled = enabled, modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                        leadingIcon = { Icon(FinanceCategoryVectors.get(key), null, Modifier.size(20.dp)) },
                        label = { Text(label) })
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
}
