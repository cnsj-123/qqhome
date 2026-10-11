package com.qq.closie.ui.lifeos.drawer

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.DragHandle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.qq.closie.ui.lifeos.theme.*
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ModuleOrderSheet(initial: List<LifeModule>,
    save: (List<String>, () -> Unit, (String) -> Unit) -> Unit, close: () -> Unit) {
    val rows = remember { initial.toMutableStateList() }
    val list = rememberLazyListState()
    val density = LocalDensity.current.density
    var dragging by remember { mutableStateOf<String?>(null) }
    var pointer by remember { mutableStateOf(Offset.Zero) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val colors = LocalLifeOsColors.current
    fun move(id: String, target: Int) {
        val from = rows.indexOfFirst { it.id == id }
        if (from >= 0 && target in rows.indices && from != target) rows.add(target, rows.removeAt(from))
    }
    fun hit() {
        val id = dragging ?: return
        list.layoutInfo.visibleItemsInfo.firstOrNull { pointer.y >= it.offset && pointer.y < it.offset + it.size }
            ?.let { move(id, it.index) }
    }
    LaunchedEffect(dragging) {
        while (dragging != null) {
            val info = list.layoutInfo
            val delta = when {
                pointer.y < info.viewportStartOffset + 64f * density -> -14f * density
                pointer.y > info.viewportEndOffset - 64f * density -> 14f * density
                else -> 0f
            }
            if (delta != 0f) { list.scroll { scrollBy(delta) }; hit() }
            delay(24)
        }
    }
    ModalBottomSheet(onDismissRequest = { if (!busy) close() }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true,
        confirmValueChange = { !busy || it != SheetValue.Hidden })) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.9f).padding(horizontal = 16.dp)) {
            Text("整理生活的抽屉", style = LifeText.title)
            Text("长按拖动；前六项会放在手边常用。也可以用箭头移动。", style = LifeText.caption, color = colors.muted)
            LazyColumn(Modifier.weight(1f).pointerInput(busy) {
                if (!busy) detectDragGesturesAfterLongPress(onDragStart = { position ->
                    pointer = position
                    dragging = list.layoutInfo.visibleItemsInfo.firstOrNull { position.y >= it.offset && position.y < it.offset + it.size }?.key as? String
                }, onDragEnd = { dragging = null }, onDragCancel = { dragging = null }) { change, delta ->
                    change.consume(); pointer += delta; hit()
                }
            }, state = list) {
                itemsIndexed(rows, key = { _, module -> module.id }) { index, module ->
                    Surface(color = if (dragging == module.id) colors.paperTertiary else colors.card) {
                        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.DragHandle, null, tint = colors.muted)
                            Text(module.label, Modifier.weight(1f).padding(start = 12.dp), style = LifeText.body)
                            IconButton({ move(module.id, index - 1) }, enabled = index > 0 && !busy) { Icon(Icons.Outlined.ArrowUpward, "上移${module.label}", Modifier.size(18.dp)) }
                            IconButton({ move(module.id, index + 1) }, enabled = index < rows.lastIndex && !busy) { Icon(Icons.Outlined.ArrowDownward, "下移${module.label}", Modifier.size(18.dp)) }
                        }
                    }
                }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton({ rows.clear(); rows.addAll(orderedModules(emptyList())) }, enabled = !busy) { Text("默认顺序") }
                Button({ busy = true; save(rows.map { it.id }, close, { error = it; busy = false }) }, Modifier.weight(1f), enabled = !busy) { Text(if (busy) "保存中…" else "保存顺序") }
            }
        }
    }
}
