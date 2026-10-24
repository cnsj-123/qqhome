package com.qq.closie.ui.lifeos.home

import com.qq.closie.life.ui.theme.BundledText as Text

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.qq.closie.ui.lifeos.components.*
import com.qq.closie.ui.lifeos.theme.*
import com.qq.closie.life.ui.theme.LifeType
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun LifeOsHomeScreen(
    state: HomeUiState,
    onDrawer: () -> Unit, onCalendar: () -> Unit, onMap: () -> Unit,
    onCompanion: () -> Unit, onCapture: () -> Unit,
    modifier: Modifier = Modifier, onToday: () -> Unit = {},
    onDateSelected: (LocalDate) -> Unit = {}, onRemovePhoto: (String) -> Unit = {}
) {
    var datePopup by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    val colors = LocalLifeOsColors.current
    var showTodayText by androidx.compose.runtime.saveable.rememberSaveable(state.date) { mutableStateOf(false) }
    var truncatedToday by remember(state.date) { mutableStateOf(false) }
    val todayText = state.todayFacts.joinToString("\n") { it.title }
    var viewerOrigin by remember {mutableStateOf<LifeSurfaceOrigin?>(null)}
    var viewerPhotoId by remember { mutableStateOf<String?>(null) }
    SuspendFunctionPageSwipe(viewerPhotoId != null || showTodayText || datePopup)
    Column(modifier.fillMaxSize().semantics {
        customActions = listOf(CustomAccessibilityAction("打开生活的抽屉") { onDrawer(); true },
            CustomAccessibilityAction("找伙伴") { onCompanion(); true })
    }) {
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 30.dp, vertical = 22.dp)
        ) {
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Life OS", style = LifeType.Wordmark, color = colors.ink.copy(alpha = .88f))
                        Text("收好自己的日子", style = LifeType.JournalTagline, color = colors.muted)
                    }
                    IconButton(onCapture) { Icon(Icons.Outlined.Add, "记一下", Modifier.size(20.dp), tint = colors.accentDeep) }
                    IconButton({ datePopup = true }) { Icon(Icons.Outlined.CalendarMonth, "生活日历", Modifier.size(20.dp)) }
                    IconButton(onMap) { Icon(Icons.Outlined.Map, "生活地图", Modifier.size(20.dp)) }
                }
                Spacer(Modifier.height(16.dp))
                Text(state.date.format(DateTimeFormatter.ofPattern("M月d日", Locale.CHINA)) + " ⌄", Modifier.clickable(onClickLabel = "选择首页日期") { datePopup = true }.padding(vertical = 4.dp), style = LifeType.DateHero, color = colors.ink)
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val year=state.date.format(DateTimeFormatter.ofPattern("yyyy 年 · EEEE", Locale.CHINA))
                    val large=androidx.compose.ui.platform.LocalDensity.current.fontScale>1.2f
                    if(maxWidth<300.dp || large) Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                        Text(year,style=if(state.eligibleMedia.isEmpty())LifeType.JournalEmptyYear else LifeType.JournalMeta,color=colors.muted)
                        Text("把今天轻轻收好",Modifier.align(Alignment.End),style=LifeType.JournalMeta,color=colors.muted)
                    } else Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                        Text(year,style=if(state.eligibleMedia.isEmpty())LifeType.JournalEmptyYear else LifeType.JournalMeta,color=colors.muted)
                        Text("把今天轻轻收好",style=LifeType.JournalMeta,color=colors.muted)
                    }
                }
            }
            item {
                if (state.date != LocalDate.now()) TextButton(onToday) { Text("回到今天") }
                HomeSectionLabel("today")
                if (state.isLoading) Spacer(Modifier.fillMaxWidth().height(140.dp))
                else if (state.eligibleMedia.isEmpty() && state.todayFacts.isNotEmpty()) {
                    Text(todayText, style = LifeType.journalFor(todayText), color = colors.ink, maxLines = 8,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        onTextLayout = { truncatedToday = it.hasVisualOverflow })
                    if (truncatedToday) TextButton({ showTodayText = true }) { Text("查看全文") }
                } else if (state.eligibleMedia.isEmpty()) {
                    Column(Modifier.fillMaxWidth().heightIn(min = 140.dp).padding(top = 28.dp), horizontalAlignment = Alignment.End) {
                        Text("好像没有随手一拍", Modifier.align(Alignment.CenterHorizontally), style = LifeType.journalEmptyFor("好像没有随手一拍"), color = colors.muted)
                        Text("今天还没有照片", Modifier.padding(top = 30.dp), style = LifeType.JournalMeta, color = colors.muted)
                    }
                } else Box(Modifier.fillMaxWidth()) {
                    LifePhotoStack(state.eligibleMedia, onPhotoClick = {viewerOrigin=null;viewerPhotoId=it.id},
                        hiddenPhotoId=viewerPhotoId,onPhotoOrigin={photo,origin->viewerOrigin=origin;viewerPhotoId=photo.id})
                }
            }
            item {
                HomeSectionLabel("memory")
                if (state.historyItems.isEmpty()) {
                    Text("long long ago", style = LifeType.JournalStamp, color = colors.muted)
                    Text("以后这里会慢慢留下以前的日子。", Modifier.padding(top = 10.dp), style = LifeType.journalFor("以后这里会慢慢留下以前的日子。"), color = colors.ink)
                }
                else state.historyItems.forEach {
                    Text(it.date.format(DateTimeFormatter.ofPattern("MM.dd")), style = LifeType.JournalStamp, color = colors.muted)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        it.photo?.let { photo -> LifeMediaImage(photo, Modifier.size(76.dp)) }
                        Text(it.title, Modifier.weight(1f), style = LifeType.journalFor(it.title), color = colors.ink)
                    }
                    Spacer(Modifier.height(12.dp))
                }
            }
            item {
                HomeSectionLabel("ahead")
                if (state.futureIntents.isEmpty()) Text("最近好像没有什么想法", style = LifeType.journalAheadFor("最近好像没有什么想法"), color = colors.ink)
                else state.futureIntents.forEach {
                    Text(it.title, style = LifeType.journalAheadFor(it.title), color = colors.ink)
                    Text("预计 · ${it.expectedDate ?: "日期未定"}", style = LifeType.journalMetaFor("预计 · ${it.expectedDate ?: "日期未定"}"), color = colors.inkSecondary)
                    Spacer(Modifier.height(12.dp))
                }
            }
            item {
                Row(Modifier.fillMaxWidth().padding(top = 36.dp, bottom = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                    HorizontalDivider(Modifier.weight(1f), color = colors.line)
                    Text("今天留下的事", Modifier.padding(start = 48.dp), style = LifeType.JournalFactHeading, color = colors.muted)
                }
                if (state.todayFacts.isEmpty()) Text("今天还没有写下的经历", style = LifeType.JournalFactMeta, color = colors.muted)
                else state.todayFacts.forEach {
                    Text(it.title, style = LifeType.journalAheadFor(it.title), color = colors.ink)
                    Text(it.domainLabel, style = LifeType.JournalFactMeta, color = colors.muted)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(com.qq.closie.R.drawable.lifeos_launch_cat), "生活伙伴", Modifier.size(width = 71.dp, height = 77.dp).clickable(onClickLabel = "找伙伴", onClick = onCompanion))
                }
                Spacer(Modifier.height(24.dp))
            }
        }

    }
    if (datePopup) HomeDatePopup(state.date, state.calendarDays, onDateSelected) { datePopup = false }
    if (showTodayText) AlertDialog(onDismissRequest = { showTodayText = false }, title = { Text("今天的记录") },
        text = {
            Text(todayText, Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), style = LifeText.body)
        }, confirmButton = { TextButton({ showTodayText = false }) { Text("收好") } })
    val selected = state.eligibleMedia.firstOrNull { it.id == viewerPhotoId }
    if (selected != null) LifePhotoViewer(state.eligibleMedia, selected.id, onDismiss = { viewerPhotoId = null },
        onRemove = { onRemovePhoto(it.id); viewerPhotoId = null },origin=viewerOrigin)
}

@Composable
private fun HomeSectionLabel(title: String) {
    val colors = LocalLifeOsColors.current
    Text(title, Modifier.padding(top = 26.dp, bottom = 10.dp), style = LifeType.JournalSection, color = colors.muted)
}

@Preview(name = "Life OS · Production empty", showBackground = true)
@Composable
private fun LifeOsHomePreview() {
    LifeOsTheme { LifeOsHomeScreen(HomeUiState(LocalDate.of(2026, 10, 8)), {}, {}, {}, {}, {}) }
}
