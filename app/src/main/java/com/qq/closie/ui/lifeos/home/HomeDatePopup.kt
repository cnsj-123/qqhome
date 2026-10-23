package com.qq.closie.ui.lifeos.home

import com.qq.closie.life.ui.theme.BundledText as Text
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.qq.closie.life.ui.theme.LifeType
import com.qq.closie.ui.lifeos.calendar.CalendarDayUi
import com.qq.closie.ui.lifeos.components.*
import com.qq.closie.ui.lifeos.theme.*
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth
import kotlin.math.abs

/** A Home projection over the same purpose grants. Choosing a date never writes a business record. */
@Composable
internal fun HomeDatePopup(
    selected: LocalDate, days: Map<LocalDate, CalendarDayUi>,
    onSelect: (LocalDate) -> Unit, onDismiss: () -> Unit
) {
    val colors = LocalLifeOsColors.current
    var monthText by rememberSaveable { mutableStateOf(YearMonth.from(selected).toString()) }
    var choosingMonth by rememberSaveable { mutableStateOf(false) }
    var year by rememberSaveable { mutableIntStateOf(selected.year) }
    var monthValue by rememberSaveable { mutableIntStateOf(selected.monthValue) }
    val month = YearMonth.parse(monthText)
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 20.dp, vertical = 36.dp),
            contentAlignment = Alignment.TopStart) {
            Surface(Modifier.padding(top = minOf(76.dp, maxHeight * .10f))
                .widthIn(max = if (choosingMonth) 260.dp else 350.dp).fillMaxWidth()
                .heightIn(max = maxHeight * .88f),
                shape = RoundedCornerShape(32.dp), color = colors.card,
                border = BorderStroke(1.dp, colors.line), shadowElevation = 8.dp) {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (choosingMonth) "选择年月" else "${month.year}年${month.monthValue}月",
                            Modifier.weight(1f).clickable(enabled = !choosingMonth) {
                                year = month.year; monthValue = month.monthValue; choosingMonth = true
                            }.padding(vertical = 10.dp), style = LifeType.DateHero, color = colors.ink)
                        IconButton(onDismiss) { Icon(Icons.Outlined.Close, "关闭日期选择", Modifier.size(18.dp)) }
                    }
                    if (choosingMonth) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            HomeDateWheel((minOf(1900, selected.year)..maxOf(2100, selected.year)).toList(),
                                year, { year = it }, { "${it}年" }, "年份", Modifier.weight(1f))
                            HomeDateWheel((1..12).toList(), monthValue, { monthValue = it },
                                { "${it}月" }, "月份", Modifier.weight(1f))
                        }
                        TextButton({ monthText = YearMonth.of(year, monthValue).toString(); choosingMonth = false },
                            Modifier.align(Alignment.End)) { Text("查看这个月") }
                    } else {
                        val previous = { monthText = month.minusMonths(1).toString() }
                        val next = { monthText = month.plusMonths(1).toString() }
                        Column(Modifier.lifeHorizontalSwipe(onLeft = next, onRight = previous).semantics {
                            customActions = listOf(CustomAccessibilityAction("上个月") { previous(); true },
                                CustomAccessibilityAction("下个月") { next(); true })
                        }) {
                            Row(Modifier.fillMaxWidth()) {
                                listOf("日", "一", "二", "三", "四", "五", "六").forEach {
                                    Box(Modifier.weight(1f).height(40.dp), contentAlignment = Alignment.Center) {
                                        Text(it, style = LifeText.caption, color = colors.muted)
                                    }
                                }
                            }
                            HorizontalDivider(color = colors.line)
                            val first = month.atDay(1).minusDays((month.atDay(1).dayOfWeek.value % 7).toLong())
                            val count = ((month.atDay(1).dayOfWeek.value % 7 + month.lengthOfMonth() + 6) / 7) * 7
                            List(count) { first.plusDays(it.toLong()) }.chunked(7).forEach { week ->
                                Row(Modifier.fillMaxWidth()) {
                                    week.forEach { date ->
                                        val day = days[date]
                                        val inMonth = YearMonth.from(date) == month
                                        Box(Modifier.weight(1f).heightIn(min = 48.dp)
                                            .clickable(onClickLabel = "查看${date}的首页") { onSelect(date); onDismiss() }
                                            .semantics {
                                                contentDescription = date.toString() + if (day?.hasContent == true) "，有记录" else ""
                                                this.selected = date == selected
                                            }, contentAlignment = Alignment.Center) {
                                            if (day?.photos?.isNotEmpty() == true)
                                                LifeMediaImage(day.photos.first(), Modifier.size(32.dp))
                                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                                Text(date.dayOfMonth.toString(),
                                                    Modifier.background(if (date == selected) colors.accentDeep.copy(alpha = .10f) else androidx.compose.ui.graphics.Color.Transparent,
                                                        CircleShape).padding(6.dp),
                                                    style = LifeText.caption,
                                                    color = if (inMonth) colors.ink else colors.muted.copy(alpha = .7f))
                                                if (day?.events?.isNotEmpty() == true)
                                                    Box(Modifier.size(3.dp).background(colors.accentDeep, CircleShape))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            TextButton({ onSelect(LocalDate.now()); onDismiss() }) { Text("回到今天") }
                            Text("have a good day ♡", Modifier.align(Alignment.CenterVertically),
                                style = LifeType.JournalMeta, color = colors.muted)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeDateWheel(values: List<Int>, selected: Int, onSelect: (Int) -> Unit,
    label: (Int) -> String, description: String, modifier: Modifier) {
    val colors = LocalLifeOsColors.current
    val state = rememberLazyListState(values.indexOf(selected).coerceAtLeast(0))
    val scope = rememberCoroutineScope()
    val latestSelect by rememberUpdatedState(onSelect)
    val reduced = reducedLifeMotion()
    LaunchedEffect(state) {
        snapshotFlow {
            if (state.isScrollInProgress) null else {
                val layout = state.layoutInfo
                val center = (layout.viewportStartOffset + layout.viewportEndOffset) / 2
                layout.visibleItemsInfo.minByOrNull { abs(it.offset + it.size / 2 - center) }?.index
            }
        }.filterNotNull().distinctUntilChanged().collect { index -> values.getOrNull(index)?.let(latestSelect) }
    }
    LazyColumn(modifier.height(210.dp).semantics {
        contentDescription = description
        stateDescription = label(selected)
        customActions = listOf(CustomAccessibilityAction("上一个${description}") {
            val i = (values.indexOf(selected) - 1).coerceAtLeast(0)
            scope.launch { state.scrollToItem(i) }; true
        }, CustomAccessibilityAction("下一个${description}") {
            val i = (values.indexOf(selected) + 1).coerceAtMost(values.lastIndex)
            scope.launch { state.scrollToItem(i) }; true
        })
    }, state = state, contentPadding = PaddingValues(vertical = 84.dp),
        flingBehavior = rememberSnapFlingBehavior(state)) {
        itemsIndexed(values) { index, value ->
            Box(Modifier.fillMaxWidth().height(42.dp).clickable {
                scope.launch { if (reduced) state.scrollToItem(index) else state.animateScrollToItem(index) }
            }, contentAlignment = Alignment.Center) {
                Text(label(value), style = LifeText.body,
                    color = if (value == selected) colors.accentDeep else colors.muted.copy(alpha = .62f))
            }
        }
    }
}
