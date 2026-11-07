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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
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
import kotlinx.coroutines.launch

private val homeDayFormat = DateTimeFormatter.ofPattern("M月d日", Locale.CHINA)
private val homeWeekFormat = DateTimeFormatter.ofPattern("yyyy 年 · EEEE", Locale.CHINA)
private val homeMemoryFormat = DateTimeFormatter.ofPattern("MM.dd")

private data class HomePhotoSelection(
    val id: String, val memory: Boolean, val origin: LifeSurfaceOrigin?,
    val initialId: String = id, val sourceKey: String? = null
)

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
    today: LocalDate = LocalDate.now(),
    onSavePhotoCaption: (suspend (String, String, String?, Long) -> Unit)? = null
) {
    var datePopup by rememberSaveable(state.date,state.restoreGeneration) { mutableStateOf(false) }
    var showTodayText by rememberSaveable(state.date,state.restoreGeneration) { mutableStateOf(false) }
    var truncatedToday by remember(state.date) { mutableStateOf(false) }
    var viewer by remember(state.date,state.restoreGeneration) { mutableStateOf<HomePhotoSelection?>(null) }
    var captionPhoto by remember(state.date,state.restoreGeneration) { mutableStateOf<LifeMediaUi?>(null) }
    var captionText by remember { mutableStateOf("") }
    var captionGeneration by remember { mutableLongStateOf(0L) }
    var captionBusy by remember { mutableStateOf(false) }
    var captionError by remember { mutableStateOf<String?>(null) }
    val captionScope = rememberCoroutineScope()
    fun editCaption(photo: LifeMediaUi) {
        captionPhoto = photo; captionText = photo.caption.orEmpty(); captionError = null
        captionGeneration = com.qq.closie.data.backup.RestoreStartupGate.state.value.restoreGeneration
    }
    val memoryPhotos = remember(state.historyItems) { state.historyItems.mapNotNull { it.photo }.distinctBy { it.id } }
    var stackPhotoId by remember(state.date,state.restoreGeneration) {mutableStateOf<String?>(null)}
    val todayOrigins = remember(state.date, state.restoreGeneration) { LiveHomePhotoOrigins() }
    val memoryOrigins = remember(state.date, state.restoreGeneration) { LiveHomePhotoOrigins() }
    val orientation = LocalConfiguration.current.orientation
    val allPhotos = remember(state.eligibleMedia, memoryPhotos) { (state.eligibleMedia + memoryPhotos).distinctBy { it.id } }
    val aspects by rememberPhotoAspects(allPhotos)
    val todayText = remember(state.todayFacts) { state.todayFacts.joinToString("\n") { it.title } }
    val viewerPhotos = if (viewer?.memory == true) memoryPhotos else state.eligibleMedia
    val visibleViewer = interactive && viewerPhotos.any { it.id == viewer?.id }
    LaunchedEffect(viewerPhotos, viewer?.id) {
        if (viewer != null && viewerPhotos.none { it.id == viewer?.id }) viewer = null
    }
    val hasToday = state.eligibleMedia.isNotEmpty() || state.todayFacts.isNotEmpty()
    val colors = LocalLifeOsColors.current
    val homeDrawing = rememberGraphicsLayer()
    val openDate = { datePopup = true }
    if (interactive) SuspendFunctionPageSwipe(visibleViewer || showTodayText || datePopup || captionPhoto != null)

    Box(modifier.fillMaxSize().then(if (interactive) Modifier.semantics {
        customActions = listOf(
            CustomAccessibilityAction("打开生活的抽屉") { onDrawer(); true },
            CustomAccessibilityAction("找伙伴") { onCompanion(); true }
        )
    } else Modifier)) {
        Box(Modifier.fillMaxSize().drawWithContent {
            if (visibleViewer) {
                homeDrawing.record { this@drawWithContent.drawContent() }
                drawLayer(homeDrawing)
            } else drawContent()
        }) {
        LazyColumn(
            Modifier.fillMaxSize().testTag("home-scroll"),
            userScrollEnabled = interactive && !visibleViewer,
            contentPadding = PaddingValues(start = 30.dp, end = 30.dp, top = 27.dp, bottom = if (hasToday) 91.dp else 30.dp)
        ) {
            item {
                HomeHeader(state.date, today, interactive, onCapture, onCalendar, onMap, openDate, onToday)
            }
            if(state.failedSources.isNotEmpty() || state.loadingSources.isNotEmpty()) item {
                Column(Modifier.padding(top=12.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                    if(state.failedSources.isNotEmpty()) Text(state.failedSources.joinToString("、")+"暂时无法读取，其他内容仍可查看",
                        style=LifeType.JournalMeta,color=colors.inkSecondary)
                    if(state.loadingSources.isNotEmpty()) Text("正在读取"+state.loadingSources.joinToString("、"),
                        style=LifeType.JournalMeta,color=colors.inkSecondary)
                }
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
                            modifier = Modifier.testTag("home-today-stack"),
                            onPhotoClick = { viewer = HomePhotoSelection(it.id, false, null) },
                            hiddenPhotoId = viewer?.takeIf { visibleViewer && !it.memory }?.id,
                            onPhotoOrigin = { photo, origin -> viewer = HomePhotoSelection(photo.id, false, origin) },
                            interactive = interactive,
                            aspects = aspects,
                            selectedPhotoId=stackPhotoId,onSelectionChange={stackPhotoId=it},
                            onPhotoLayout = { id, coordinates, rotation ->
                                todayOrigins.update(id, id, coordinates, orientation, rotation)
                            }
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
                        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            state.historyItems.take(6).forEach { memory ->
                                key(memory.sourceId) {
                                    val photo = memory.photo
                                    val sourceKey = memory.sourceId
                                    if (photo != null) DisposableEffect(sourceKey, photo.id, memoryOrigins) {
                                        onDispose { memoryOrigins.update(sourceKey, photo.id, null, orientation) }
                                    }
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp),
                                        verticalAlignment = Alignment.Top) {
                                        if (photo != null) LifeMediaImage(
                                            photo, Modifier.size(76.dp, 70.dp)
                                                .testTag("home-memory-photo-" + sourceKey)
                                                .onGloballyPositioned {
                                                    memoryOrigins.update(sourceKey, photo.id, it, orientation)
                                                }
                                                .graphicsLayer {
                                                    alpha = if (visibleViewer && viewer?.memory == true && viewer?.id == photo.id) 0f else 1f
                                                }
                                                .clickable(enabled = interactive, onClickLabel = "查看回忆照片") {
                                                    viewer = HomePhotoSelection(photo.id, true,
                                                        memoryOrigins.current(photo.id, orientation, sourceKey), sourceKey = sourceKey)
                                                }
                                        )
                                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Text(memory.date.format(homeMemoryFormat), style = LifeType.DateHero.copy(fontSize = 12.sp, lineHeight = 13.sp, letterSpacing = .6.sp), color = colors.inkSecondary)
                                            memory.title?.takeIf(String::isNotBlank)?.let {
                                                Text(it, style = LifeType.journalFor(it).copy(fontSize = 20.sp, lineHeight = 24.sp, letterSpacing = 4.sp), color = colors.ink)
                                            }
                                            val note = photo?.caption?.takeIf(String::isNotBlank)
                                            if (note != null || (photo != null && memory.title.isNullOrBlank())) {
                                                Text(note ?: "要不要留下点什么呢",
                                                    Modifier.then(if (interactive && onSavePhotoCaption != null) Modifier.clickable(onClickLabel = "编辑照片备注") { editCaption(photo!!) } else Modifier),
                                                    style = LifeType.JournalMeta.copy(fontSize = 13.sp, lineHeight = 15.sp, letterSpacing = 4.sp), color = colors.accentDeep)
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
                            Column(verticalArrangement=Arrangement.spacedBy(3.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Text(it.title, Modifier.weight(1f), style = LifeType.JournalMeta.copy(fontSize = 20.sp, lineHeight = 27.sp, letterSpacing = 4.sp), color = colors.ink)
                                Text(buildAnnotatedString {
                                    if(it.expectedDate!=null) {
                                        withStyle(SpanStyle(fontSize = 12.sp)) { append("预计 · ") }
                                        withStyle(SpanStyle(fontSize = 14.sp)) { append(it.expectedDate.format(homeDayFormat)) }
                                    } else append("日期未定")
                                }, style = LifeType.DateHero.copy(lineHeight = 19.sp, letterSpacing = .6.sp), color = colors.inkSecondary)
                            }
                            it.note?.takeIf(String::isNotBlank)?.let {note->
                                Text(note,style=LifeType.JournalMeta,color=colors.accentDeep)
                            }
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
                                Text(it.title, Modifier.weight(1f, fill = false), style = LifeType.DateHero.copy(fontSize = 18.sp, lineHeight = 25.sp, letterSpacing = 2.7.sp), color = colors.ink)
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
        }
        if (visibleViewer) viewer?.let { selection ->
            LifePhotoViewer(
                viewerPhotos, selection.initialId, onDismiss = { viewer = null },
                onRemove = { onRemovePhoto(it.id); viewer = null }, origin = selection.origin,
                aspects = aspects, dateFor = { photo ->
                    if (!selection.memory) state.date else state.historyItems.firstOrNull { it.photo?.id == photo.id }?.date
                }, today = today, onEditCaption = if (onSavePhotoCaption == null) null else ::editCaption,
                originPaperInset = if (selection.memory) 0.dp else 7.dp,
                backdropLayer = homeDrawing,
                currentOrigin = { id ->
                    if (selection.memory) memoryOrigins.current(id, orientation, selection.sourceKey)
                    else todayOrigins.current(id, orientation)
                },
                onPhotoChanged={id->
                    if(!selection.memory) stackPhotoId=id
                    viewer=viewer?.copy(id=id)
                }
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
    captionPhoto?.let { photo -> AlertDialog(onDismissRequest = { if (!captionBusy) captionPhoto = null },
        title = { Text("照片备注") }, text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("此备注会展示在首页，原图保留。", style = LifeText.caption)
            OutlinedTextField(captionText, { if (it.length <= 1000) captionText = it }, label = { Text("留下一点回忆") },
                enabled = !captionBusy, modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp),
                textStyle = LifeType.inputFor(captionText))
            captionError?.let { Text(it, style = LifeText.caption, color = androidx.compose.material3.MaterialTheme.colorScheme.error) }
        } }, confirmButton = { TextButton({
            captionBusy = true; captionError = null
            captionScope.launch {
                try {
                    onSavePhotoCaption?.invoke(photo.id, captionText, photo.caption, captionGeneration)
                    captionPhoto = null
                } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (error: Exception) { captionError = error.message ?: "备注未保存，请重试" }
                finally { captionBusy = false }
            }
        }, enabled = !captionBusy && onSavePhotoCaption != null) { Text(if (captionBusy) "保存中…" else "保存备注") } },
        dismissButton = { TextButton({ captionPhoto = null }, enabled = !captionBusy) { Text("取消") } }) }
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
            Box(Modifier.width(168.dp).height(49.dp)) {
                Text("Life OS", style = LifeType.Wordmark.copy(fontSize = 40.sp, lineHeight = 49.sp, letterSpacing = 2.8.sp), color = colors.ink)
                Text("收好自己的日子", Modifier.offset(x = 50.dp, y = 36.dp), style = LifeType.JournalTagline.copy(fontSize = 12.sp, lineHeight = 13.sp), color = colors.inkSecondary)
            }
        }
        @Composable fun Actions() {
            Row(Modifier.offset(x = if (wrapped) 0.dp else 15.dp)) {
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
            Spacer(Modifier.height(20.dp))
            val dateAction = if (interactive) Modifier.clickable(onClickLabel = "选择首页日期", onClick = onDate) else Modifier
            Column(dateAction.heightIn(min = 48.dp)) {
                Text(date.format(homeDayFormat), style = LifeType.DateHero.copy(fontSize = 24.sp, lineHeight = 27.sp), color = colors.ink)
                Text(date.format(homeWeekFormat), style = LifeType.JournalMeta.copy(fontSize = 14.sp, lineHeight = 16.sp, letterSpacing = 2.8.sp), color = colors.inkSecondary)
            }
        }
        if (!wrapped || maxWidth >= 230.dp) {
            if (historical) {
                TextButton(onToday, Modifier.align(Alignment.BottomEnd).offset(y = 8.dp), enabled = interactive,
                    contentPadding = PaddingValues(horizontal = 0.dp)) {
                    Text("回到今天", style = LifeType.JournalMeta, color = colors.accentDeep)
                }
            } else Text("把今天轻轻收好", Modifier.align(Alignment.BottomEnd).padding(bottom = 5.dp), style = LifeType.JournalMeta.copy(fontSize = 14.sp, lineHeight = 16.sp, letterSpacing = 2.8.sp), color = colors.inkSecondary)
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
    Text(title, Modifier.padding(top = if (title == "TODAY") 17.dp else 22.dp, bottom = if (title == "TODAY") 4.dp else 14.dp),
        style = LifeType.JournalSection.copy(fontSize = 14.sp, lineHeight = 25.sp, letterSpacing = 2.8.sp), color = colors.inkSecondary)
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
                         .graphicsLayer { rotationZ = angles[i]; alpha = .70f },
                    colorFilter = ColorFilter.tint(colors.muted))
            }
        }
        Text(title, Modifier.align(Alignment.TopEnd).padding(top = 10.dp), style = LifeType.JournalMeta.copy(fontSize = 14.sp, lineHeight = 16.sp, letterSpacing = 2.8.sp), color = colors.inkSecondary)
    }
}

@Preview(name = "Life OS · Production empty", showBackground = true)
@Composable
private fun LifeOsHomePreview() {
    LifeOsTheme { LifeOsHomeScreen(HomeUiState(LocalDate.now()), {}, {}, {}, {}, {}, interactive = false) }
}
