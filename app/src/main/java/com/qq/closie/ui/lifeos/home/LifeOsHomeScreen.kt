package com.qq.closie.ui.lifeos.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qq.closie.R
import com.qq.closie.life.ui.theme.BundledText as Text
import com.qq.closie.life.ui.theme.LifeType
import com.qq.closie.ui.lifeos.components.*
import com.qq.closie.ui.lifeos.media.LifeMediaUi
import com.qq.closie.ui.lifeos.theme.*
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private val homeDayFormat = DateTimeFormatter.ofPattern("M月d日", Locale.CHINA)
private val homeWeekFormat = DateTimeFormatter.ofPattern("yyyy 年 · EEEE", Locale.CHINA)
private val homeMemoryFormat = DateTimeFormatter.ofPattern("MM.dd")

private data class HomePhotoSelection(val id: String, val memory: Boolean, val origin: LifeSurfaceOrigin?)

@Composable
fun LifeOsHomeScreen(
    state: HomeUiState,
    onDrawer: () -> Unit,
    onCalendar: () -> Unit,
    onMap: () -> Unit,
    onCompanion: () -> Unit,
    onCapture: () -> Unit,
    modifier: Modifier = Modifier,
    onToday: () -> Unit = {},
    onDateSelected: (LocalDate) -> Unit = {},
    onRemovePhoto: (String) -> Unit = {},
    interactive: Boolean = true,
    today: LocalDate = LocalDate.now()
) {
    var datePopup by rememberSaveable(state.date) { mutableStateOf(false) }
    var showTodayText by rememberSaveable(state.date) { mutableStateOf(false) }
    var truncatedToday by remember(state.date) { mutableStateOf(false) }
    var viewer by remember(state.date) { mutableStateOf<HomePhotoSelection?>(null) }
    val memoryPhotos = remember(state.historyItems) { state.historyItems.mapNotNull { it.photo }.distinctBy { it.id } }
    val allPhotos = remember(state.eligibleMedia, memoryPhotos) { (state.eligibleMedia + memoryPhotos).distinctBy { it.id } }
    val aspects by rememberPhotoAspects(allPhotos)
    val todayText = remember(state.todayFacts) { state.todayFacts.joinToString("\n") { it.title } }
    val viewerPhotos = if (viewer?.memory == true) memoryPhotos else state.eligibleMedia
    val visibleViewer = interactive && viewerPhotos.any { it.id == viewer?.id }
    val hasToday = state.eligibleMedia.isNotEmpty() || state.todayFacts.isNotEmpty()
    val colors = LocalLifeOsColors.current
    val openDate = { datePopup = true }
    if (interactive) SuspendFunctionPageSwipe(visibleViewer || showTodayText || datePopup)

    Box(modifier.fillMaxSize().then(if (interactive) Modifier.semantics {
        customActions = listOf(
            CustomAccessibilityAction("打开生活的抽屉") { onDrawer(); true },
            CustomAccessibilityAction("找伙伴") { onCompanion(); true }
        )
    } else Modifier)) {
        LazyColumn(
            Modifier.fillMaxSize(),
            userScrollEnabled = interactive && !visibleViewer,
            contentPadding = PaddingValues(start = 32.dp, end = 32.dp, top = 25.dp, bottom = if (hasToday) 91.dp else 30.dp)
        ) {
            item {
                HomeHeader(state.date, today, interactive, onCapture, onCalendar, onMap, openDate, onToday)
            }
            if (state.isLoading || state.loadFailed) item {
                HomeSectionLabel("TODAY")
                Text(if (state.loadFailed) "暂时无法读取这一天" else "正在翻开这一天…",
                    style = LifeType.JournalMeta, color = colors.inkSecondary)
            } else {
                item {
                    HomeSectionLabel("TODAY")
                    when {
                        state.eligibleMedia.isNotEmpty() -> LifePhotoStack(
                            photos = state.eligibleMedia,
                            onPhotoClick = { viewer = HomePhotoSelection(it.id, false, null) },
                            hiddenPhotoId = viewer?.takeIf { visibleViewer && !it.memory }?.id,
                            onPhotoOrigin = { photo, origin -> viewer = HomePhotoSelection(photo.id, false, origin) },
                            interactive = interactive,
                            aspects = aspects
                        )
                        state.todayFacts.isNotEmpty() -> {
                            Text(todayText, style = LifeType.journalFor(todayText), color = colors.ink, maxLines = 7,
                                overflow = TextOverflow.Ellipsis,
                                onTextLayout = { truncatedToday = it.hasVisualOverflow })
                            if (interactive && truncatedToday) TextButton({ showTodayText = true }) {
                                Text("查看全文", style = LifeType.JournalMeta)
                            }
                        }
                        else -> HomeEmptyToday(state.date == today)
                    }
                }
                item {
                    HomeSectionLabel("MEMORY")
                    if (state.historyItems.isEmpty()) {
                        Text("long long ago", style = LifeType.JournalStamp, color = colors.muted)
                        Text("以后，这里会慢慢留下以前的日子。", Modifier.padding(top = 10.dp),
                            style = LifeType.journalFor("以后，这里会慢慢留下以前的日子。"), color = colors.ink)
                    } else {
                        val orientation = LocalConfiguration.current.orientation
                        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            state.historyItems.take(6).forEach { memory ->
                                key(memory.sourceId) {
                                    val photo = memory.photo
                                    var origin by remember { mutableStateOf<LifeSurfaceOrigin?>(null) }
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp),
                                        verticalAlignment = Alignment.CenterVertically) {
                                        if (photo != null) LifeMediaImage(
                                            photo, Modifier.size(76.dp, 70.dp)
                                                .lifeSurfaceOrigin(orientation) { origin = it }
                                                .graphicsLayer { alpha = if (visibleViewer && viewer?.id == photo.id) 0f else 1f }
                                                .clickable(enabled = interactive, onClickLabel = "查看回忆照片") {
                                                    viewer = HomePhotoSelection(photo.id, true, origin)
                                                }
                                        )
                                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                            Text(memory.date.format(homeMemoryFormat), style = LifeType.JournalStamp, color = colors.muted)
                                            memory.title?.takeIf(String::isNotBlank)?.let {
                                                Text(it, style = LifeType.journalFor(it), color = colors.ink)
                                            }
                                            val note = photo?.caption?.takeIf(String::isNotBlank)
                                            if (note != null || (photo != null && memory.title.isNullOrBlank())) {
                                                Text(note ?: "要不要留下点什么呢", style = LifeType.JournalMeta, color = colors.accentDeep)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                if (state.futureIntents.isNotEmpty()) item {
                    HomeSectionLabel("AHEAD")
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        state.futureIntents.forEach {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Text(it.title, Modifier.weight(1f), style = LifeType.journalAheadFor(it.title), color = colors.ink)
                                val date = it.expectedDate?.format(homeDayFormat) ?: "日期未定"
                                Text("预计 · " + date, style = LifeType.JournalStamp, color = colors.inkSecondary)
                            }
                        }
                    }
                }
                item {
                    HomeFactHeading(if (state.date == today) "今天留下的事" else "这天留下的事")
                    if (state.todayFacts.isEmpty()) {
                        Text(if (state.date == today) "今天还没有写下的经历" else "这一天没有写下的经历",
                            style = LifeType.JournalFactMeta, color = colors.muted)
                    } else Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        state.todayFacts.forEach {
                            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(it.title, Modifier.weight(1f, fill = false), style = LifeType.journalAheadFor(it.title), color = colors.ink)
                                Text(it.domainLabel, style = LifeType.JournalFactMeta, color = colors.muted)
                            }
                        }
                    }
                }
            }
        }
        if (hasToday && !state.isLoading && !state.loadFailed) Image(
            painterResource(R.drawable.lifeos_home_cat), null,
            Modifier.align(Alignment.BottomEnd).padding(end = 30.dp, bottom = 9.dp).size(80.dp, 85.dp)
        )
        if (visibleViewer) viewer?.let { selection ->
            LifePhotoViewer(
                viewerPhotos, selection.id, onDismiss = { viewer = null },
                onRemove = { onRemovePhoto(it.id); viewer = null }, origin = selection.origin,
                aspects = aspects, dateFor = { photo ->
                    if (!selection.memory) state.date else state.historyItems.firstOrNull { it.photo?.id == photo.id }?.date
                }, today = today
            )
        }
    }

    if (interactive && datePopup) HomeDatePopup(state.date, state.calendarDays, onDateSelected) { datePopup = false }
    if (interactive && showTodayText) AlertDialog(
        onDismissRequest = { showTodayText = false },
        title = { Text("这一天的记录") },
        text = { Text(todayText, Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), style = LifeText.body) },
        confirmButton = { TextButton({ showTodayText = false }) { Text("收好") } }
    )
}

@Composable
private fun HomeHeader(
    date: LocalDate,
    today: LocalDate,
    interactive: Boolean,
    onCapture: () -> Unit,
    onCalendar: () -> Unit,
    onMap: () -> Unit,
    onDate: () -> Unit,
    onToday: () -> Unit
) {
    val colors = LocalLifeOsColors.current
    val historical = date != today
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val wrapped = maxWidth < 265.dp || LocalDensity.current.fontScale > 1.3f
        @Composable fun Brand() {
            Column {
                Text("Life OS", style = LifeType.Wordmark.copy(fontSize = 40.sp), color = colors.ink)
                Text("收好自己的日子", Modifier.padding(start = 50.dp), style = LifeType.JournalTagline, color = colors.muted)
            }
        }
        @Composable fun Actions() {
            Row {
                IconButton(onCapture, enabled = interactive) { Icon(Icons.Outlined.Add, "记一下", Modifier.size(20.dp), tint = colors.accentDeep) }
                IconButton(onCalendar, enabled = interactive) { Icon(Icons.Outlined.CalendarMonth, "生活日历", Modifier.size(20.dp), tint = colors.inkSecondary) }
                IconButton(onMap, enabled = interactive) { Icon(Icons.Outlined.Map, "生活地图", Modifier.size(20.dp), tint = colors.inkSecondary) }
            }
        }
        Column {
            if (wrapped) {
                Brand()
                Box(Modifier.align(Alignment.End)) { Actions() }
            } else Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { Brand() }
                Actions()
            }
            Spacer(Modifier.height(14.dp))
            val dateAction = if (interactive) Modifier.clickable(onClickLabel = "选择首页日期", onClick = onDate) else Modifier
            Column(dateAction.heightIn(min = 48.dp), verticalArrangement = Arrangement.Center) {
                Text(date.format(homeDayFormat), style = LifeType.DateHero, color = colors.ink)
                Text(date.format(homeWeekFormat), style = LifeType.JournalMeta, color = colors.muted)
            }
        }
        if (!wrapped || maxWidth >= 230.dp) {
            if (historical) {
                TextButton(onToday, Modifier.align(Alignment.BottomEnd).offset(y = 8.dp), enabled = interactive,
                    contentPadding = PaddingValues(horizontal = 0.dp)) {
                    Text("回到今天", style = LifeType.JournalMeta, color = colors.accentDeep)
                }
            } else Text("把今天轻轻收好", Modifier.align(Alignment.BottomEnd), style = LifeType.JournalMeta, color = colors.muted)
        }
    }
}

@Composable
private fun HomeEmptyToday(isToday: Boolean) {
    val colors = LocalLifeOsColors.current
    val first = if (isToday) "今日无事发生" else "这一天很安静"
    Column(Modifier.fillMaxWidth().padding(top = 5.dp, bottom = 6.dp)) {
        Text(first, Modifier.align(Alignment.CenterHorizontally), style = LifeType.journalEmptyFor(first), color = colors.muted)
        Row(Modifier.align(Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
            Image(painterResource(R.drawable.lifeos_home_sleeping_cat), null, Modifier.size(110.dp))
            if (isToday) Text("爱卿勿念", style = LifeType.journalEmptyFor("爱卿勿念"), color = colors.muted)
        }
        Text(if (isToday) "今天还没有照片" else "这一天没有照片", Modifier.align(Alignment.End).padding(end = 12.dp),
            style = LifeType.JournalMeta, color = colors.muted)
    }
}

@Composable
private fun HomeSectionLabel(title: String) {
    val colors = LocalLifeOsColors.current
    Text(title, Modifier.padding(top = 22.dp, bottom = 10.dp), style = LifeType.JournalSection, color = colors.muted)
}

@Composable
private fun HomeFactHeading(title: String) {
    val colors = LocalLifeOsColors.current
    BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 27.dp, bottom = 14.dp)) {
        val widths = listOf(7.5.dp, 11.dp, 9.dp, 13.dp, 8.5.dp)
        val positions = listOf(.22f, .40f, .57f, .70f, .79f)
        val heights = listOf(13.dp, 4.dp, 17.dp, 12.dp, 6.dp)
        val angles = listOf(-60f, 40f, -139f, 0f, 137f)
        if (maxWidth >= 270.dp && LocalDensity.current.fontScale <= 1.2f) {
            widths.indices.forEach { i ->
                Image(painterResource(R.drawable.lifeos_home_paw), null,
                    Modifier.offset(maxWidth * positions[i], heights[i]).size(widths[i])
                        .graphicsLayer { rotationZ = angles[i]; alpha = if (i == 3) 1f else .70f },
                    colorFilter = ColorFilter.tint(colors.muted))
            }
        }
        Text(title, Modifier.align(Alignment.TopEnd).padding(top = 10.dp), style = LifeType.JournalMeta, color = colors.muted)
    }
}

@Preview(name = "Life OS · Production empty", showBackground = true)
@Composable
private fun LifeOsHomePreview() {
    LifeOsTheme { LifeOsHomeScreen(HomeUiState(LocalDate.now()), {}, {}, {}, {}, {}, interactive = false) }
}
