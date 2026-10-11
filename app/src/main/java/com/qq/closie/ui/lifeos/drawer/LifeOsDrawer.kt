package com.qq.closie.ui.lifeos.drawer

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qq.closie.ui.lifeos.theme.*

private val defaultFirst = listOf("capture", "finance", "calendar", "closet", "inbox", "plans")
internal fun orderedModules(ids: List<String>): List<LifeModule> {
    val all = LifeModules.groups.flatMap { it.modules }.associateBy { it.id }
    return ((ids.ifEmpty { defaultFirst }) + all.keys).distinct().mapNotNull(all::get)
}

@Composable
fun LifeOsDrawer(onOpenModule: (LifeModule) -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier,
    moduleOrder: List<String> = emptyList(),
    onSaveOrder: (List<String>, () -> Unit, (String) -> Unit) -> Unit = { _, done, _ -> done() }) {
    val colors = LocalLifeOsColors.current
    var arranging by rememberSaveable { mutableStateOf(false) }
    val modules = remember(moduleOrder) { orderedModules(moduleOrder) }
    val first = remember(modules) { modules.take(6).chunked(2) }
    val rest = remember(modules) { modules.drop(6).chunked(2) }
    LazyColumn(modifier.fillMaxHeight().testTag("life-drawer"),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)) {
        item(key = "brand", contentType = "header") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("生活的抽屉", style = LifeText.journal, color = colors.ink)
                    Text("把日子放回自己的位置", style = LifeText.caption, color = colors.muted)
                }
                IconButton(onClose) { Icon(Icons.Outlined.Close, "收起生活的抽屉", Modifier.size(20.dp), colors.inkSecondary) }
            }
            Spacer(Modifier.height(16.dp))
            Surface(onClick = { onOpenModule(LifeModules.find("search")) }, shape = RoundedCornerShape(16.dp),
                color = colors.card.copy(alpha = .82f), border = BorderStroke(1.dp, colors.line)) {
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Search, null, Modifier.size(18.dp), colors.muted)
                    Text("找一件东西、一段经历", Modifier.padding(start = 10.dp), style = LifeText.caption, color = colors.inkSecondary)
                }
            }
            Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("手边常用", Modifier.weight(1f), style = LifeText.caption, color = colors.muted)
                TextButton({ arranging = true }) { Text("整理", style = LifeText.caption) }
            }
        }
        items(first, key = { "first-${it.first().id}" }, contentType = { "pair" }) { pair ->
            ModulePair(pair, true, onOpenModule) { arranging = true }
        }
        item(key = "all", contentType = "header") {
            Text("生活的一格一格", Modifier.padding(top = 20.dp, bottom = 8.dp), style = LifeText.caption, color = colors.muted)
        }
        items(rest, key = { "rest-${it.first().id}" }, contentType = { "pair" }) { pair ->
            ModulePair(pair, false, onOpenModule) { arranging = true }
        }
        item(key = "hint") {
            Text("长按整理顺序 · 轻轻左滑回到手账", Modifier.padding(top = 22.dp, bottom = 8.dp), style = LifeText.caption, color = colors.muted)
        }
    }
    if (arranging) ModuleOrderSheet(modules, onSaveOrder) { arranging = false }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ModulePair(pair: List<LifeModule>, quick: Boolean, open: (LifeModule) -> Unit, arrange: () -> Unit) {
    val colors = LocalLifeOsColors.current
    Row(Modifier.fillMaxWidth().padding(bottom = if (quick) 6.dp else 2.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        pair.forEach { module ->
            Surface(Modifier.weight(1f), color = if (quick) colors.card.copy(alpha = .62f) else androidx.compose.ui.graphics.Color.Transparent,
                shape = RoundedCornerShape(13.dp)) {
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("drawer-${module.id}")
                    .combinedClickable(role = Role.Button, onClick = { open(module) }, onLongClick = arrange,
                        onLongClickLabel = "整理模块顺序").padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(module.icon(), null, Modifier.size(19.dp), colors.accentDeep)
                    Text(module.label, Modifier.padding(start = 8.dp), style = LifeText.body.copy(fontSize = 13.sp, lineHeight = 19.sp),
                        color = colors.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        if (pair.size == 1) Spacer(Modifier.weight(1f))
    }
}
