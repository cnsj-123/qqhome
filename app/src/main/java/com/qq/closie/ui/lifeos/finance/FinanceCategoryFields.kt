package com.qq.closie.ui.lifeos.finance

import com.qq.closie.life.ui.theme.BundledText as Text

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.qq.closie.life.finance.*
import com.qq.closie.ui.lifeos.theme.LifeText

/** User icon keys are typed configuration; names supply a presentation default. */
internal object FinanceCategoryGlyph {
    private val defaults = listOf(
        setOf("三餐", "早餐", "午餐", "晚餐", "餐饮", "零食", "买菜") to Icons.Outlined.Restaurant,
        setOf("交通", "交通住宿", "旅行", "高铁", "公交") to Icons.Outlined.DirectionsBus,
        setOf("医疗", "健康", "药品") to Icons.Outlined.LocalHospital,
        setOf("学习", "书籍", "教育") to Icons.Outlined.MenuBook,
        setOf("日用品", "超市", "购物") to Icons.Outlined.ShoppingBag,
        setOf("衣服", "美妆", "美甲") to Icons.Outlined.Checkroom,
        setOf("园艺", "植物") to Icons.Outlined.LocalFlorist,
        setOf("宠物") to Icons.Outlined.Pets,
        setOf("人情", "礼物") to Icons.Outlined.CardGiftcard)
    fun forName(name: String): ImageVector = defaults.firstOrNull { name.trim() in it.first }?.second ?: Icons.Outlined.Category
    fun forCategory(s:FinanceSnapshot,id:String,name:String):ImageVector = when(s.v16?.categoryIconRows.orEmpty().singleOrNull {it.categoryId==id && it.voidedAt==null}?.iconKey) {
        "food"->Icons.Outlined.Restaurant;"transport"->Icons.Outlined.DirectionsBus;"home"->Icons.Outlined.Home
        "health"->Icons.Outlined.LocalHospital;"shopping"->Icons.Outlined.ShoppingBag;"gift"->Icons.Outlined.CardGiftcard
        "other"->Icons.Outlined.Category;else->forName(name)
    }
}

@Composable
internal fun FinanceCategoryFields(snapshot: FinanceSnapshot, selected: String?, onSelect: (String) -> Unit,
    busy: Boolean, onCreateChild: (String, String, (String) -> Unit) -> Unit) {
    val tree = remember(snapshot.categories) { FinanceCategoryTree(snapshot.categories) }
    var query by rememberSaveable { mutableStateOf("") }
    var parentId by rememberSaveable { mutableStateOf<String?>(null) }
    var creating by rememberSaveable { mutableStateOf(false) }
    var childName by rememberSaveable { mutableStateOf("") }
    val recent = remember(snapshot.entries, tree) { snapshot.entries.filter { it.voidedAt == null }
        .sortedByDescending { it.updatedAt }.mapNotNull { tree.node(it.categoryId) }.distinctBy { it.id }.take(8) }
    if (recent.isNotEmpty()) {
        Text("最近使用", style = LifeText.caption)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { items(recent, key = { it.id }) { category ->
            InputChip(selected == category.id, { onSelect(category.id) }, enabled = !busy, label = { Text(category.name) })
        } }
    }
    OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), label = { Text("搜索分类或路径") }, singleLine = true, enabled = !busy, textStyle = com.qq.closie.life.ui.theme.LifeType.inputFor(query))
    if (parentId != null && query.isBlank()) Row {
        TextButton({ parentId = tree.node(parentId)?.parentId; creating = false }, enabled = !busy) { Text("‹ 上一级") }
        TextButton({ parentId?.let(onSelect) }, enabled = !busy) { Text("使用 ${tree.node(parentId)?.name.orEmpty()}") }
    }
    val choices = if (query.isBlank()) parentId?.let(tree::children) ?: tree.roots()
        else snapshot.categories.filter { tree.fullPath(it.id)?.contains(query.trim(), true) == true }
    choices.chunked(3).forEach { row ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            row.forEach { category ->
                FilterChip(selected == category.id, {
                    if (query.isBlank() && tree.children(category.id).isNotEmpty()) parentId = category.id else onSelect(category.id)
                }, modifier = Modifier.weight(1f), enabled = !busy,
                    leadingIcon = { Icon(FinanceCategoryGlyph.forCategory(snapshot,category.id,category.name), null, Modifier.size(18.dp)) },
                    label = { Text(if (query.isBlank()) category.name else tree.fullPath(category.id).orEmpty(), maxLines = 2) })
            }
            repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
        }
    }
    if (choices.isEmpty()) Text("没有匹配的分类", style = LifeText.caption)
    if (parentId == null && tree.node(selected) != null) {
        TextButton({ parentId = selected; query = ""; creating = true }, enabled = !busy) {
            Text("给“${tree.node(selected)?.name}”新增子分类")
        }
    }
    parentId?.let { parent ->
        TextButton({ creating = !creating }, enabled = !busy) { Text(if (creating) "取消新增" else "＋ 新增子分类") }
        if (creating) {
            OutlinedTextField(childName, { childName = it }, label = { Text("子分类名称") }, singleLine = true, enabled = !busy, textStyle = com.qq.closie.life.ui.theme.LifeType.inputFor(childName))
            TextButton({ onCreateChild(parent, childName) { childName = ""; creating = false; onSelect(it) } },
                enabled = !busy && FinanceCategoryTree.singleName(childName) != null && tree.hasValidAncestry(parent)) { Text("新增并选择") }
        }
    }
    Text("分类记录消费内容；报销、代付、分账在业务性质中选择。", style = LifeText.caption)
}
