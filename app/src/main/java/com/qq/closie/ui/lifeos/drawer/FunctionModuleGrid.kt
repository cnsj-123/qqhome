package com.qq.closie.ui.lifeos.drawer

import com.qq.closie.life.ui.theme.BundledText as Text

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qq.closie.life.ui.theme.LifeType
import com.qq.closie.ui.lifeos.theme.*
import kotlinx.coroutines.delay

/** Stable module keys; one lifted overlay. No effects on the individual scrolling cells. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun LifeOsDrawer(onOpenModule: (LifeModule) -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier,
    active: Boolean = true,
    moduleOrder: List<String> = emptyList(),
    onSaveOrder: (List<String>, () -> Unit, (String) -> Unit) -> Unit = { _, done, _ -> done() },
    interactive: Boolean = true) {
    val colors = LocalLifeOsColors.current
    val acceptsInput = interactive && active
    val grid = rememberLazyGridState()
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    var modules by remember { mutableStateOf(orderedModules(moduleOrder)) }
    var baseline by remember { mutableStateOf(modules) }
    var lifted by remember { mutableStateOf<LifeModule?>(null) }
    var pointer by remember { mutableStateOf(Offset.Zero) }
    var grab by remember { mutableStateOf(Offset.Zero) }
    var cellWidth by remember { mutableIntStateOf(0) }
    var lastTarget by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var undoOrder by remember { mutableStateOf<List<LifeModule>?>(null) }
    val save by rememberUpdatedState(onSaveOrder)
    LaunchedEffect(moduleOrder) { if (lifted == null && !saving) modules = orderedModules(moduleOrder) }
    val policy = com.qq.closie.ui.lifeos.components.LocalFunctionPagePolicy.current
    fun cancel(): Boolean {
        policy?.cancelReorder = null
        if (lifted == null || saving) return false
        modules = baseline; lifted = null; message = "已取消移动"
        return true
    }
    // The function page stays composed while hidden; hidden reorders must not own Back.
    LaunchedEffect(active) { if (!active) cancel() }
    DisposableEffect(active, lifted?.id, saving, policy) {
        val cancellation: (() -> Boolean)? = if (active && lifted != null && !saving) ({ cancel() }) else null
        policy?.cancelReorder = cancellation
        onDispose { if (policy?.cancelReorder === cancellation) policy?.cancelReorder = null }
    }
    fun persist(previous: List<LifeModule>, restoreDefault: Boolean = false, rememberUndo: Boolean = true) {
        policy?.cancelReorder = null
        if (!restoreDefault && modules == previous) return
        saving = true
        save(if (restoreDefault) emptyList() else modules.map { it.id }, {
            undoOrder = previous.takeIf { rememberUndo }
            saving = false; message = if (restoreDefault) "已恢复默认顺序" else "顺序已保存"
        }, {
            modules = previous; saving = false; message = it
        })
    }
    fun targetAtPointer(): String? = grid.layoutInfo.visibleItemsInfo.firstOrNull { item ->
            modules.any { it.id == item.key } &&
                pointer.x >= item.offset.x && pointer.x < item.offset.x + item.size.width &&
                pointer.y >= item.offset.y - grid.layoutInfo.viewportStartOffset && pointer.y < item.offset.y - grid.layoutInfo.viewportStartOffset + item.size.height
        }?.key as? String
    fun moveAtPointer() {
        val id = lifted?.id ?: return
        val target = targetAtPointer() ?: return
        if (target == id) { lastTarget = null; return }
        if (target == lastTarget) return
        val from = modules.indexOfFirst { it.id == id }; val to = modules.indexOfFirst { it.id == target }
        if (from >= 0 && to >= 0 && from != to) {
            lastTarget = target
            modules = modules.toMutableList().apply { add(to, removeAt(from)) }
        }
    }
    val edge = with(density) { 56.dp.toPx() }
    LaunchedEffect(lifted?.id) {
        while (lifted != null) {
            val end = grid.layoutInfo.viewportEndOffset - grid.layoutInfo.viewportStartOffset
            val speed = when {
                pointer.y < edge -> -((edge - pointer.y) / edge).coerceIn(0f, 1f)
                pointer.y > end - edge -> ((pointer.y - end + edge) / edge).coerceIn(0f, 1f)
                else -> 0f
            }
            if (speed != 0f) { grid.scrollBy(speed * with(density) { 10.dp.toPx() }); moveAtPointer() }
            delay(16)
        }
    }
    // The shell handles cancellation when hosted there; standalone drawers retain Back.
    BackHandler(enabled = active && lifted != null && !saving && policy == null) { cancel() }
    Box(modifier.fillMaxHeight()) {
        LazyVerticalGrid(GridCells.Fixed(2), state = grid, userScrollEnabled = acceptsInput,
            modifier = Modifier.fillMaxSize().testTag("life-drawer")
            .pointerInput(active, saving) {
                if (!acceptsInput || saving) return@pointerInput
                detectDragGesturesAfterLongPress(onDragStart = { point ->
                    val item = grid.layoutInfo.visibleItemsInfo.firstOrNull {
                        point.x >= it.offset.x && point.x < it.offset.x + it.size.width &&
                            point.y >= it.offset.y - grid.layoutInfo.viewportStartOffset && point.y < it.offset.y - grid.layoutInfo.viewportStartOffset + it.size.height
                    }
                    val module = modules.firstOrNull { it.id == item?.key }
                    if (module != null && item != null) {
                        baseline = modules; lifted = module; pointer = point; lastTarget = null
                        grab = point - Offset(item.offset.x.toFloat(), (item.offset.y - grid.layoutInfo.viewportStartOffset).toFloat())
                        cellWidth = item.size.width; message = "松手放置"
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    }
                }, onDrag = { change, amount ->
                    if (lifted != null) { change.consume(); pointer += amount; moveAtPointer() }
                }, onDragEnd = {
                    policy?.cancelReorder = null
                    if (lifted != null) {
                        if (targetAtPointer() == null) cancel()
                        else { lifted = null; persist(baseline) }
                    }
                },
                    onDragCancel = { if (lifted != null) cancel() })
            }, contentPadding = PaddingValues(start = 32.dp, end = 32.dp, top = 33.dp, bottom = 14.dp)) {
            item(key = "brand", span = { GridItemSpan(maxLineSpan) }, contentType = "header") {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("生活的抽屉", style = LifeType.JournalMeta.copy(fontSize = 24.sp, lineHeight = 27.sp, letterSpacing = 4.sp), color = colors.ink)
                            Text("把日子放回自己的位置", Modifier.padding(start = 18.dp),
                                style = LifeType.DateHero.copy(fontSize = 12.sp, lineHeight = 19.sp), color = colors.inkSecondary)
                        }
                        // The final draft has no visible X; retain its 48dp accessible close target.
                        Box(Modifier.size(48.dp).then(if (interactive) Modifier.semantics { contentDescription = "收起生活的抽屉" }
                            .clickable(enabled = acceptsInput && !saving, onClick = onClose) else Modifier))
                    }
                    Box(Modifier.fillMaxWidth().padding(top = 7.dp).height(48.dp)
                        .clickable(enabled = acceptsInput) { onOpenModule(LifeModules.find("search")) }, contentAlignment = Alignment.Center) {
                        Row(Modifier.fillMaxWidth().height(32.dp).background(colors.card.copy(alpha = .65f), RoundedCornerShape(12.dp))
                            .padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Search, null, Modifier.size(18.dp), colors.accentDeep)
                            Text("找一件东西、一段经历", Modifier.padding(start = 22.dp),
                                style = LifeType.JournalMeta.copy(fontSize = 12.sp, lineHeight = 14.sp, letterSpacing = 2.sp), color = colors.ink)
                        }
                    }
                    Row(Modifier.padding(top = 12.dp, bottom = 8.dp)) {
                        Text("手边常用", Modifier.weight(1f), style = LifeType.JournalMeta.copy(fontSize = 14.sp, lineHeight = 19.sp, letterSpacing = 2.sp), color = colors.accentDeep)
                    }
                }
            }
            modules.forEachIndexed { index, module ->
                if (index == 6) item(key = "all", span = { GridItemSpan(maxLineSpan) }, contentType = "header") {
                    Column(Modifier.padding(top = 11.dp, bottom = 10.dp)) {
                        HorizontalDivider(color = colors.line)
                        Text("生活的一格一格", Modifier.padding(top = 13.dp),
                            style = LifeType.JournalMeta.copy(fontSize = 14.sp, lineHeight = 19.sp, letterSpacing = 2.sp), color = colors.accentDeep)
                    }
                }
                item(key = module.id, contentType = "module") {
                    ModuleRow(module, Modifier.animateItem().graphicsLayer { alpha = if (lifted?.id == module.id) 0f else 1f }
                        .semantics {
                            customActions = listOf(-1 to "向前移动", 1 to "向后移动").map { (delta, label) ->
                                CustomAccessibilityAction(label) {
                                    val at = modules.indexOfFirst { it.id == module.id }; val to = at + delta
                                    if (acceptsInput && !saving && lifted == null && to in modules.indices) {
                                        val before = modules; modules = modules.toMutableList().apply { add(to, removeAt(at)) }; persist(before); true
                                    } else false
                                }
                            }
                        }.clickable(enabled = acceptsInput && lifted == null && !saving) { onOpenModule(module) })
                }
            }
            item(key = "hint", span = { GridItemSpan(maxLineSpan) }) {
                Box(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 10.dp)) {
                    Column {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(message ?: "左滑收起", Modifier.weight(1f),
                                style = LifeType.DateHero.copy(fontSize = 12.sp, lineHeight = 19.sp, letterSpacing = 1.sp), color = colors.inkSecondary)
                        TextButton({
                            val original = undoOrder ?: return@TextButton
                            val before = modules
                            modules = original
                            persist(before, rememberUndo = false)
                        }, enabled = acceptsInput && !saving && lifted == null && undoOrder != null) {
                            Text("撤销", style = LifeType.JournalMeta.copy(fontSize = 12.sp, lineHeight = 19.sp, letterSpacing = 2.sp))
                        }
                    }
                    TextButton(onClick = {
                        val previous = modules
                        modules = orderedModules(emptyList())
                        persist(previous, restoreDefault = true)
                    }, enabled = acceptsInput && !saving && lifted == null, modifier = Modifier.testTag("drawer-restore-order")) {
                        Text("恢复默认顺序", style = LifeType.JournalMeta.copy(fontSize = 12.sp, lineHeight = 19.sp, letterSpacing = 2.sp))
                    }
                    }
                    Image(painterResource(com.qq.closie.R.drawable.lifeos_drawer_cat), null,
                        Modifier.align(Alignment.BottomEnd).size(62.dp))
                }
            }
        }
        lifted?.let { module ->
            ModuleRow(module, Modifier.width(with(density) { cellWidth.toDp() }).graphicsLayer {
                translationX = pointer.x - grab.x; translationY = pointer.y - grab.y
                scaleX = 1.035f; scaleY = 1.035f; shadowElevation = 8.dp.toPx()
                shape = RoundedCornerShape(10.dp); clip = true
            }.background(colors.card.copy(alpha = .9f)))
        }
    }
}

@Composable
private fun ModuleRow(module: LifeModule, modifier: Modifier) {
    val colors = LocalLifeOsColors.current
    Row(modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("drawer-${module.id}").padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(module.icon(), null, Modifier.size(20.dp), colors.accentDeep)
        Text(module.label, Modifier.padding(start = 7.dp), style = LifeType.DateHero.copy(fontSize = 14.sp, lineHeight = 18.sp, letterSpacing = 1.sp),
            color = colors.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}
