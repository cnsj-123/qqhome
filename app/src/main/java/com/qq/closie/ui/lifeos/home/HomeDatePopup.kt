package com.qq.closie.ui.lifeos.home

import com.qq.closie.life.ui.theme.BundledText as Text
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
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
        val window=(LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect {window?.setDimAmount(0f)}
        Box(Modifier.fillMaxSize()) {
        val dim=if(colors.isDark)Color.Black.copy(alpha=.32f) else Color(0xFF84758F).copy(alpha=.16f)
        Box(Modifier.fillMaxSize().background(dim)
            .testTag("home-date-scrim").clickable(onClickLabel="关闭日期选择",onClick=onDismiss))
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = if(choosingMonth)30.dp else 28.dp, vertical = 36.dp),
            contentAlignment = Alignment.TopStart) {
            Surface(Modifier.padding(top = minOf(104.dp, maxHeight * .13f))
                .widthIn(max = if (choosingMonth) 210.dp else 350.dp).fillMaxWidth().testTag("home-date-paper")
                .heightIn(max = maxHeight * .88f).pointerInput(Unit) {detectTapGestures(onTap={})},
                shape = RoundedCornerShape(if(choosingMonth)35.dp else 40.dp), color = colors.card.copy(alpha=.97f),
                border = BorderStroke(1.dp, colors.line), shadowElevation = 8.dp) {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal=if(choosingMonth)29.dp else 24.dp,vertical=24.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (choosingMonth) "选择年月" else "${month.year}年${month.monthValue}月",
                            Modifier.weight(1f).clickable {
                                if(choosingMonth) {monthText=YearMonth.of(year,monthValue).toString();choosingMonth=false}
                                else {year = month.year; monthValue = month.monthValue; choosingMonth = true}
                            }.padding(vertical = if(choosingMonth)6.dp else 10.dp), style = LifeType.DateHero.copy(fontSize=20.sp), color = colors.ink,
                            textAlign=if(choosingMonth) androidx.compose.ui.text.style.TextAlign.Start else androidx.compose.ui.text.style.TextAlign.Center)
                    }
                    if (choosingMonth) {
                        Box(Modifier.fillMaxWidth().height(210.dp)) {
                        Box(Modifier.align(Alignment.Center).fillMaxWidth().height(26.dp).background(
                            Brush.verticalGradient(listOf(Color.Transparent,colors.accent.copy(alpha=.13f),Color.Transparent))))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            HomeDateWheel((minOf(1900, selected.year)..maxOf(2100, selected.year)).toList(),
                                year, { year = it;monthText=YearMonth.of(year,monthValue).toString() }, { it.toString() }, "年份", Modifier.weight(1f))
                            HomeDateWheel((1..12).toList(), monthValue, { monthValue = it;monthText=YearMonth.of(year,monthValue).toString() },
                                { "${it}月" }, "月份", Modifier.weight(1f))
                        }
                        }
                    } else {
                        val previous = { monthText = month.minusMonths(1).toString() }
                        val next = { monthText = month.plusMonths(1).toString() }
                        Column(Modifier.lifeHorizontalSwipe(onLeft = next, onRight = previous).semantics {
                            customActions = listOf(CustomAccessibilityAction("上个月") { previous(); true },
                                CustomAccessibilityAction("下个月") { next(); true })
                        }) {
                            Row(Modifier.fillMaxWidth()) {
                                listOf("日", "一", "二", "三", "四", "五", "六").forEach {
                                    Box(Modifier.weight(1f).height(36.dp), contentAlignment = Alignment.Center) {
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
                                        Box(Modifier.weight(1f).heightIn(min = 46.dp)
                                            .clickable(onClickLabel = "查看${date}的首页") { onSelect(date); onDismiss() }
                                            .semantics {
                                                contentDescription = date.toString() + if (day?.hasContent == true) "，有记录" else ""
                                                this.selected = date == selected
                                            }, contentAlignment = Alignment.Center) {
                                            if (day?.photos?.isNotEmpty() == true)
                                                LifeMediaImage(day.photos.first(), Modifier.size(32.dp).clip(RoundedCornerShape(4.dp)).testTag("home-photo-"+date))
                                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                                Text(date.dayOfMonth.toString(),
                                                    Modifier.background(if (date == selected) colors.accentDeep.copy(alpha = .10f) else androidx.compose.ui.graphics.Color.Transparent,
                                                        CircleShape).padding(6.dp),
                                                    style = LifeText.caption,
                                                    color = if (inMonth) colors.ink else colors.muted.copy(alpha = .7f))
                                                Row(horizontalArrangement=Arrangement.spacedBy(3.dp)) {
                                                    if (day?.events?.isNotEmpty() == true || day?.intents?.isNotEmpty()==true)
                                                        Box(Modifier.size(8.dp).testTag("home-important-"+date)
                                                            .border(BorderStroke(1.dp,colors.accent),CircleShape))
                                                    if(day?.timedContent?.isNotEmpty()==true)
                                                        Box(Modifier.size(3.dp).testTag("home-writing-"+date).background(colors.accent, CircleShape))
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        Row(Modifier.fillMaxWidth().padding(top=12.dp), horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment=Alignment.CenterVertically) {
                            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(5.dp)) {
                                val firstPhoto=days.values.firstNotNullOfOrNull {it.photos.firstOrNull()}
                                if(firstPhoto!=null) LifeMediaImage(firstPhoto,Modifier.size(16.dp).clip(RoundedCornerShape(3.dp)))
                                else Icon(painterResource(com.qq.closie.R.drawable.lifeos_finance_entry_note),null,Modifier.size(14.dp),tint=colors.secondary)
                                Text("有照片",style=LifeText.caption,color=colors.inkSecondary)
                            }
                            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(5.dp)) {
                                Box(Modifier.size(8.dp).border(BorderStroke(1.dp,colors.accent),CircleShape))
                                Text("重要日子",style=LifeText.caption,color=colors.inkSecondary)
                            }
                            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(5.dp)) {
                                Box(Modifier.size(3.dp).background(colors.accent,CircleShape))
                                Text("文字记录",style=LifeText.caption,color=colors.inkSecondary)
                            }
                        }
                        Text("Have a good day ♡",Modifier.align(Alignment.End).padding(top=10.dp),style=LifeType.JournalMeta,color=colors.muted)
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
                Text(label(value), style = LifeType.DateHero.copy(fontSize=if(value==selected)20.sp else 14.sp,letterSpacing=1.sp),
                    color = if (value == selected) colors.accentDeep else colors.muted.copy(alpha = .62f))
            }
        }
    }
}
