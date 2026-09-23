package com.xiaoming.closie.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.xiaoming.closie.ui.theme.ClosieColor

/**
 * The generic smart-entry choice sheet. Searchable, scrollable, with existing values first,
 * optional presets, custom values and clear.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SearchableChoiceSheet(
    title: String,
    currentValue: String,
    suggestions: List<String>,
    preferredSuggestions: List<String> = emptyList(),
    allowCustom: Boolean = true,
    onSelect: (String) -> Unit,
    onClear: (() -> Unit)? = null,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var query by remember { mutableStateOf("") }

    val full = remember(suggestions, currentValue) {
        val merged = dedupeCaseInsensitive(suggestions)
        if (currentValue.isNotBlank() && merged.none { it.equals(currentValue, ignoreCase = true) }) {
            listOf(currentValue) + merged
        } else {
            merged
        }
    }
    val preferred = remember(preferredSuggestions) { dedupeCaseInsensitive(preferredSuggestions) }

    val trimmed = query.trim()
    val filtered = full.filter { it.contains(trimmed, ignoreCase = true) }
    val filteredPreferred = preferred.filter { it.contains(trimmed, ignoreCase = true) }
    val exactMatch = full.any { it.equals(trimmed, ignoreCase = true) }
    val showCustom = allowCustom && trimmed.isNotEmpty() && !exactMatch

    fun select(value: String) {
        onSelect(value)
        onDismiss()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = ClosieColor.Surface
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(title, style = MaterialTheme.typography.titleLarge, color = ClosieColor.Ink)
                if (onClear != null && currentValue.isNotBlank()) {
                    TextButton(onClick = { onClear(); onDismiss() }) {
                        Text("清除", color = ClosieColor.InkTertiary)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("搜索$title", color = ClosieColor.InkTertiary) },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = ClosieColor.Rose,
                    unfocusedBorderColor = ClosieColor.Hairline
                ),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(
                    onDone = {
                        when {
                            exactMatch -> full.first { it.equals(trimmed, ignoreCase = true) }.let { select(it) }
                            showCustom -> select(trimmed)
                        }
                    }
                )
            )
            Spacer(Modifier.height(12.dp))

            if (trimmed.isEmpty() && filteredPreferred.isNotEmpty()) {
                Text("最近 / 已使用", style = MaterialTheme.typography.bodyMedium, color = ClosieColor.InkSecondary)
                Spacer(Modifier.height(8.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    filteredPreferred.forEach { option ->
                        ClosieFilterChip(
                            selected = option.equals(currentValue, ignoreCase = true),
                            onClick = { select(option) },
                            label = option
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
            }

            Text(
                if (trimmed.isEmpty()) "全部" else "搜索结果",
                style = MaterialTheme.typography.bodyMedium,
                color = ClosieColor.InkSecondary
            )
            Spacer(Modifier.height(4.dp))

            if (filtered.isEmpty() && !showCustom) {
                Box(Modifier.fillMaxWidth().padding(vertical = 32.dp), contentAlignment = Alignment.Center) {
                    Text("没有匹配的选项", style = MaterialTheme.typography.bodyMedium, color = ClosieColor.InkTertiary)
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                    items(filtered, key = { it }) { option ->
                        OptionRow(
                            label = option,
                            selected = option.equals(currentValue, ignoreCase = true),
                            onClick = { select(option) }
                        )
                    }
                    if (showCustom) {
                        item(key = "__custom__") {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { select(trimmed) }
                                    .heightIn(min = 48.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "＋ 使用“$trimmed”",
                                    color = ClosieColor.Rose,
                                    style = MaterialTheme.typography.bodyLarge
                                )
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun OptionRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = 48.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (selected) ClosieColor.RosePressed else ClosieColor.Ink
        )
        if (selected) Text("✓", style = MaterialTheme.typography.bodyLarge, color = ClosieColor.Rose)
    }
}

private fun dedupeCaseInsensitive(values: List<String>): List<String> {
    val result = mutableListOf<String>()
    val seen = HashSet<String>()
    values.forEach { if (it.isNotBlank() && seen.add(it.lowercase())) result.add(it) }
    return result
}
