package com.qq.closie.ui.lifeos.finance

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.qq.closie.ui.lifeos.components.LifeSwipeIntent
import com.qq.closie.ui.lifeos.components.SuspendFunctionPageSwipe
import java.time.YearMonth

/** Shell gets Initial first. Calendar then consumes horizontal intent before Pager's Main pass.
 * Vertical intent remains untouched for the enclosing nested scroll / week collapse. */
@Composable
internal fun Modifier.calendarMonthSwipe(onMonth: (Int) -> Unit): Modifier {
    val change by rememberUpdatedState(onMonth)
    val edge = with(LocalDensity.current) { LifeSwipeIntent.systemBackEdgeDp.dp.toPx() }
    val threshold = with(LocalDensity.current) { 40.dp.toPx() }
    var rootX by remember { mutableFloatStateOf(0f) }
    return onGloballyPositioned { rootX = it.positionInRoot().x }.pointerInput(edge, threshold) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            if (down.position.x + rootX <= edge || down.position.x >= size.width - edge) return@awaitEachGesture
            var horizontal = false
            var distance = 0f
            while (true) {
                val next = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id }
                    ?: return@awaitEachGesture
                if (next.isConsumed) return@awaitEachGesture
                val delta = next.position - down.position
                if (!horizontal) when (LifeSwipeIntent.resolve(delta.x, delta.y, viewConfiguration.touchSlop)) {
                    LifeSwipeIntent.Direction.VERTICAL -> return@awaitEachGesture
                    LifeSwipeIntent.Direction.LEFT, LifeSwipeIntent.Direction.RIGHT -> horizontal = true
                    null -> Unit
                }
                if (horizontal) { distance = delta.x; next.consume() }
                if (!next.pressed) {
                    if (horizontal && kotlin.math.abs(distance) >= threshold) change(if (distance < 0f) 1 else -1)
                    break
                }
            }
        }
    }
}

@Composable
internal fun FinanceMonthPicker(current: YearMonth, onSelect: (YearMonth) -> Unit, onClose: () -> Unit) {
    SuspendFunctionPageSwipe()
    var year by remember { mutableIntStateOf(current.year) }
    var chooseYear by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onClose, title = { Text("选择年月") }, text = {
        Column {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton({ year-- }, enabled = year > 1) { Text("上一年") }
                TextButton({ chooseYear = !chooseYear }) { Text("$year 年 ▾") }
                TextButton({ year++ }, enabled = year < 9998) { Text("下一年") }
            }
            if (chooseYear) {
                val years = (minOf(1900, current.year)..maxOf(YearMonth.now().year + 10, current.year)).toList()
                val position = years.indexOf(year).coerceAtLeast(0)
                val list = androidx.compose.foundation.lazy.rememberLazyListState(position)
                LazyColumn(Modifier.height(220.dp), state = list) {
                    items(years) { value -> TextButton({ year = value; chooseYear = false }, Modifier.fillMaxWidth()) { Text("$value 年") } }
                }
            } else (1..12).chunked(3).forEach { months ->
                Row(Modifier.fillMaxWidth()) { months.forEach { month ->
                    TextButton({ onSelect(YearMonth.of(year, month)) }, Modifier.weight(1f)) { Text("$month 月") }
                } }
            }
        }
    }, confirmButton = { TextButton({ onSelect(YearMonth.now()) }) { Text("本月") } },
        dismissButton = { TextButton(onClose) { Text("取消") } })
}
