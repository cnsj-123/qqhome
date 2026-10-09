package com.xiaoming.closie.ui.lifeos.calendar

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.xiaoming.closie.ui.lifeos.components.*
import com.xiaoming.closie.ui.lifeos.theme.*
import java.time.LocalDate
import java.time.YearMonth

@Composable
fun LifeCalendarScreen(
    initialDate: LocalDate,
    onBack: () -> Unit,
    onOpenHomeDate: (LocalDate) -> Unit,
    state: CalendarUiState = CalendarUiState(YearMonth.from(initialDate)),
    onMonthChanged: (YearMonth) -> Unit = {}
) {
    val colors = LocalLifeOsColors.current
    var monthText by rememberSaveable { mutableStateOf(state.month.toString()) }
    var selectedText by rememberSaveable { mutableStateOf<String?>(null) }
    val month = YearMonth.parse(monthText)
    LifePage("生活日历", onBack) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { monthText = MonthGrid.previous(month).toString(); onMonthChanged(MonthGrid.previous(month)) }) {
                Icon(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, "上一个月")
            }
            Text("${month.year} 年 ${month.monthValue} 月", Modifier.weight(1f), style = LifeText.title)
            IconButton(onClick = { monthText = MonthGrid.next(month).toString(); onMonthChanged(MonthGrid.next(month)) }) {
                Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, "下一个月")
            }
        }
        Surface(color = colors.card, shape = RoundedCornerShape(20.dp), border = BorderStroke(1.dp, colors.line)) {
            Column(Modifier.padding(8.dp)) {
                Row(Modifier.fillMaxWidth()) {
                    listOf("日", "一", "二", "三", "四", "五", "六").forEach {
                        Box(Modifier.weight(1f).heightIn(min = 36.dp), contentAlignment = Alignment.Center) { Text(it, style = LifeText.caption, color = colors.muted) }
                    }
                }
                MonthGrid.cells(month).chunked(7).forEach { week ->
                    Row(Modifier.fillMaxWidth()) {
                        week.forEach { date ->
                            if (date == null) Spacer(Modifier.weight(1f).height(48.dp))
                            else {
                                val day = state.days[date]
                                TextButton(onClick = { selectedText = date.toString() }, Modifier.weight(1f).heightIn(min = 48.dp),
                                    contentPadding = PaddingValues(2.dp)) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(date.dayOfMonth.toString(), style = LifeText.caption, color = if (date == LocalDate.now()) colors.accentDeep else colors.ink)
                                        if (day?.photos?.isNotEmpty() == true) LifeMediaImage(day.photos.first(), Modifier.height(24.dp).fillMaxWidth())
                                        if (day?.intents?.isNotEmpty() == true) Text("预计", style = LifeText.caption)
                                        if (day?.events?.isNotEmpty() == true) Text("经历", style = LifeText.caption)
                                        if (day?.timedContent?.isNotEmpty() == true) Text("内容", style = LifeText.caption)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        if (!state.hasContentIn(month)) {
            LifeEmptyState("照片、经历和预计安排，会按真实日期留在这里。")
        }
    }
    selectedText?.let { text ->
        val date = LocalDate.parse(text)
        val day = state.days[date] ?: CalendarDayUi(date)
        val config = LocalConfiguration.current
        Dialog(onDismissRequest = { selectedText = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(Modifier.width((config.screenWidthDp * .90f).dp).heightIn(max = (config.screenHeightDp * .76f).dp),
                shape = RoundedCornerShape(18.dp), color = colors.card) {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(text, Modifier.weight(1f), style = LifeText.title)
                        IconButton(onClick = { selectedText = null }) { Icon(Icons.Outlined.Close, "关闭日期浮层") }
                    }
                    day.events.forEach { Text("经历 · ${it.title}", style = LifeText.body) }
                    day.intents.forEach { Text("预计 · ${it.title}", style = LifeText.body) }
                    day.timedContent.forEach { Text("${it.kindLabel} · ${it.title}", style = LifeText.body) }
                    day.photos.forEach { LifeMediaImage(it, Modifier.fillMaxWidth().height(160.dp)) }
                    if (!day.hasContent) {
                        LifeEmptyState("这一天还没有留下记录。")
                    }
                    TextButton(onClick = { selectedText = null; onOpenHomeDate(date) }) { Text("翻到这一天的首页") }
                }
            }
        }
    }
}
