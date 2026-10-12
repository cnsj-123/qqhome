package com.qq.closie.ui.lifeos.finance

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.qq.closie.life.finance.*
import com.qq.closie.ui.lifeos.theme.LifeText

@Composable
internal fun FinanceCategoryPicker(snapshot: FinanceSnapshot, selected: String?, onSelect: (String) -> Unit, onClose: () -> Unit) {
    val tree = remember(snapshot.categories) { FinanceCategoryTree(snapshot.categories) }
    var query by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf(tree.path(selected).map { it.id }.toSet()) }
    val recent = remember(snapshot.entries, snapshot.categories) { snapshot.entries.filter { it.voidedAt == null }
        .sortedByDescending { it.updatedAt }.mapNotNull { tree.node(it.categoryId) }.distinctBy { it.id }.take(8) }
    val rows = remember(tree, query, expanded) {
        if (query.isNotBlank()) snapshot.categories.filter {
            it.name.contains(query.trim(), true) || tree.fullPath(it.id)?.contains(query.trim(), true) == true
        }.sortedBy { tree.fullPath(it.id) }.map { it to 0 }
        else buildList {
            val visited = mutableSetOf<String>()
            val stack = ArrayDeque<Pair<FinanceCategoryEntity, Int>>()
            tree.roots().asReversed().forEach { stack.addLast(it to 0) }
            while (stack.isNotEmpty()) {
                val (node, depth) = stack.removeLast()
                if (!visited.add(node.id)) continue
                add(node to depth)
                // Beyond three levels show a compact flat path, not an endless folder browser.
                if (node.id in expanded || depth >= 2) tree.children(node.id).asReversed().forEach { stack.addLast(it to depth + 1) }
            }
        }
    }
    FinanceReceipt("分类", onClose = onClose) {
        if (recent.isNotEmpty()) {
            Text("最近使用", style = LifeText.caption)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { items(recent, key = { it.id }) { row ->
                SuggestionChip({ onSelect(row.id); onClose() }, label = { Text(row.name) })
            } }
        }
        OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), label = { Text("搜索分类名或完整路径") }, singleLine = true)
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 400.dp)) {
            if (rows.isEmpty()) item { Text("没有匹配的分类", style = LifeText.caption) }
            items(rows, key = { it.first.id }) { (row, depth) ->
                val hasChildren = tree.children(row.id).isNotEmpty()
                Row(Modifier.fillMaxWidth().padding(start = (depth.coerceAtMost(2) * 12).dp)) {
                    TextButton({
                        if (query.isNotBlank() || !hasChildren || depth >= 2) { onSelect(row.id); onClose() }
                        else expanded = if (row.id in expanded) expanded - row.id else expanded + row.id
                    }, Modifier.weight(1f)) { Text((if (row.id == selected) "● " else "") +
                        (if (query.isNotBlank() || depth >= 2) tree.fullPath(row.id).orEmpty() else row.name) +
                        if (hasChildren && query.isBlank() && depth < 2) if (row.id in expanded) " ▾" else " ▸" else "") }
                    if (hasChildren && query.isBlank() && depth < 2) TextButton({ onSelect(row.id); onClose() }) { Text("选此类") }
                }
            }
        }
        Text("分类是消费内容；报销、代付和分账请在业务性质中选择。", style = LifeText.caption)
    }
}
