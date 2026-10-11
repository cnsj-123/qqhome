package com.qq.closie.ui.lifeos.finance

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.qq.closie.ui.lifeos.theme.*

/** Session-only presentation history; category/account identity remains owned by Finance. */
private object ChoiceHistory {
    val recent = mutableStateMapOf<String, List<String>>()
    fun select(label: String, id: String) { recent[label] = (listOf(id) + recent[label].orEmpty()).distinct().take(6) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FinanceChoiceSheet(title: String, selected: String?, choices: List<Pair<String, String>>,
    onSelect: (String) -> Unit, onClose: () -> Unit, onClear: (() -> Unit)? = null,
    common: List<String> = emptyList()) {
    var query by rememberSaveable { mutableStateOf("") }
    val colors = LocalLifeOsColors.current
    val byId = remember(choices) { choices.toMap() }
    val recent = ChoiceHistory.recent[title].orEmpty().mapNotNull { id -> byId[id]?.let { id to it } }
    val popular = remember(choices, common) { common.take(6).mapNotNull { id -> byId[id]?.let { id to it } } }
    val filtered = remember(choices, query) { choices.filter { it.second.contains(query.trim(), ignoreCase = true) }.sortedBy { it.second } }
    fun choose(id: String) { ChoiceHistory.select(title, id); onSelect(id); onClose() }
    ModalBottomSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = colors.card) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.85f).imePadding().padding(horizontal = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("选择$title", Modifier.weight(1f), style = LifeText.title)
                TextButton(onClose) { Text("关闭") }
            }
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true,
                label = { Text("搜索$title") }, leadingIcon = { Icon(Icons.Outlined.Search, null) }, shape = RoundedCornerShape(16.dp))
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(vertical = 12.dp)) {
                if (query.isBlank()) {
                    if (recent.isNotEmpty()) {
                        item(key = "recent") { Text("最近选择", style = LifeText.caption, color = colors.muted) }
                        items(recent, key = { "recent-${it.first}" }) { (id, name) -> ChoiceLine(name, id == selected) { choose(id) } }
                    }
                    if (popular.isNotEmpty()) {
                        item(key = "common") { Text("常用", style = LifeText.caption, color = colors.muted) }
                        items(popular, key = { "common-${it.first}" }) { (id, name) -> ChoiceLine(name, id == selected) { choose(id) } }
                    }
                }
                item(key = "all") { Text("${if (query.isBlank()) "全部" else "搜索结果"} · ${filtered.size}", style = LifeText.caption, color = colors.muted) }
                items(filtered, key = { "all-${it.first}" }) { (id, name) -> ChoiceLine(name, id == selected) { choose(id) } }
                if (filtered.isEmpty()) item { Text("没有匹配项，试试其他关键词。", Modifier.padding(vertical = 24.dp), style = LifeText.body) }
            }
            if (onClear != null) OutlinedButton({ onClear(); onClose() }, Modifier.fillMaxWidth().padding(bottom = 12.dp)) { Text("清除选择 · 全部$title") }
        }
    }
}

@Composable
private fun ChoiceLine(name: String, selected: Boolean, click: () -> Unit) {
    val colors = LocalLifeOsColors.current
    Row(Modifier.fillMaxWidth().clickable(role = Role.RadioButton, onClick = click).heightIn(min = 48.dp).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            if (name.contains(" / ")) Text(name.substringBeforeLast(" / "), style = LifeText.caption, color = colors.muted)
            Text(name.substringAfterLast(" / "), style = LifeText.body, color = colors.ink)
        }
        if (selected) Icon(Icons.Outlined.Check, "已选", tint = colors.accent)
    }
}
