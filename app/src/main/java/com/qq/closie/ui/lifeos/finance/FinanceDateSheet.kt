package com.qq.closie.ui.lifeos.finance

import com.qq.closie.life.ui.theme.BundledText as Text

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.qq.closie.ui.lifeos.theme.*
import java.time.LocalDate
import java.time.YearMonth

@Composable
internal fun FinanceDateSheet(error: String?, onApply: (String, String) -> Boolean, onClose: () -> Unit,
    initial: LocalDate = LocalDate.now(), transactionDays: Set<LocalDate> = emptySet(),
    singleDayOnly: Boolean = false, initialEnd: LocalDate = initial) {
    var monthText by rememberSaveable { mutableStateOf(YearMonth.from(initial).toString()) }
    var firstText by rememberSaveable { mutableStateOf(initial.toString()) }
    var lastText by rememberSaveable { mutableStateOf(if (singleDayOnly) initial.toString() else initialEnd.toString()) }
    var rangeMode by rememberSaveable { mutableStateOf(!singleDayOnly && initial != initialEnd) }
    var selectingEnd by rememberSaveable { mutableStateOf(false) }
    val month = YearMonth.parse(monthText)
    val first = LocalDate.parse(firstText)
    val last = LocalDate.parse(lastText)
    val colors = LocalLifeOsColors.current
    FinanceReceipt(if (singleDayOnly) "选择发生日期" else "按日期查看", error = error, onClose = onClose, actions = {
        Button({ if (onApply(firstText, lastText)) onClose() }, Modifier.fillMaxWidth()) {
            Text(if (singleDayOnly) "使用 $first" else if (first == last) "查看 $first" else "查看 $first — $last")
        }
    }) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!singleDayOnly) {
                FilterChip(!rangeMode, { rangeMode = false; selectingEnd = false; lastText = firstText }, label = { Text("单日") })
                FilterChip(rangeMode, { rangeMode = true; selectingEnd = false }, label = { Text("日期范围") })
            }
            TextButton({ monthText = YearMonth.now().toString(); firstText = LocalDate.now().toString(); lastText = firstText; selectingEnd = false }) { Text("今天") }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton({ monthText = month.minusMonths(1).toString() }) { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, "上个月") }
            Text("${month.year} 年 ${month.monthValue} 月", Modifier.weight(1f), style = LifeText.title)
            IconButton({ monthText = month.plusMonths(1).toString() }) { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, "下个月") }
        }
        Row { listOf("一", "二", "三", "四", "五", "六", "日").forEach { Text(it, Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center, style = LifeText.caption) } }
        val offset = month.atDay(1).dayOfWeek.value - 1
        val cells = ((month.lengthOfMonth() + offset + 6) / 7) * 7
        (0 until cells).chunked(7).forEach { week ->
            Row {
                week.forEach { index ->
                    val day = index - offset + 1
                    if (day !in 1..month.lengthOfMonth()) Spacer(Modifier.weight(1f).height(44.dp))
                    else {
                        val date = month.atDay(day)
                        val selected = date >= first && date <= last
                        Box(Modifier.weight(1f).height(44.dp).background(if (selected) colors.paperTertiary else colors.card, CircleShape)
                            .semantics { contentDescription = "$date${if (date in transactionDays) "，有流水" else ""}${if (selected) "，已选" else ""}" }
                            .clickable {
                                if (!rangeMode || !selectingEnd) { firstText = date.toString(); lastText = firstText; selectingEnd = rangeMode }
                                else { firstText = minOf(first, date).toString(); lastText = maxOf(first, date).toString(); selectingEnd = false }
                            }, contentAlignment = Alignment.Center) {
                            Text(day.toString(), style = LifeText.body)
                            if (date in transactionDays) Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 3.dp).size(3.dp).background(colors.accent, CircleShape))
                        }
                    }
                }
            }
        }
        if (!singleDayOnly) TextButton({ firstText = month.atDay(1).toString(); lastText = month.atEndOfMonth().toString(); rangeMode = true; selectingEnd = false }) { Text("选择整个月") }
        if (rangeMode) Text(if (selectingEnd) "再点一个日期作为结束日" else "点选开始日，再点结束日", style = LifeText.caption, color = colors.muted)
    }
}
