package com.qq.closie.ui.lifeos.home

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.qq.closie.ui.lifeos.components.*
import com.qq.closie.ui.lifeos.theme.*
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun LifeOsHomeScreen(
    state: HomeUiState,
    onDrawer: () -> Unit, onCalendar: () -> Unit, onMap: () -> Unit,
    onCompanion: () -> Unit, onCapture: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalLifeOsColors.current
    var viewerPhotoId by remember { mutableStateOf<String?>(null) }
    Column(modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 14.dp)
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDrawer) { Icon(Icons.Outlined.Menu, "打开生活的抽屉", tint = colors.inkSecondary) }
                    Column(Modifier.weight(1f)) {
                        Text("Life OS", style = LifeText.display, color = colors.ink)
                        Text("收好自己的日子", style = LifeText.caption, color = colors.muted)
                    }
                    IconButton(onClick = onCalendar) { Icon(Icons.Outlined.CalendarMonth, "生活日历", tint = colors.inkSecondary) }
                    IconButton(onClick = onMap) { Icon(Icons.Outlined.Map, "生活地图", tint = colors.inkSecondary) }
                }
                TextButton(onClick = onCompanion) { Text("伙伴 · 聊一会儿", style = LifeText.caption) }
                Spacer(Modifier.height(10.dp))
                Text(state.date.format(DateTimeFormatter.ofPattern("yyyy 年 · EEEE", Locale.CHINA)), style = LifeText.caption, color = colors.muted)
                Text(state.date.format(DateTimeFormatter.ofPattern("M月d日", Locale.CHINA)), style = LifeText.title, color = colors.ink)
                Text("把今天轻轻收好", style = LifeText.caption, color = colors.inkSecondary)
            }
            item {
                HomeSectionLabel("今天的一小叠", "TODAY")
                if (state.isLoading) LinearProgressIndicator(Modifier.fillMaxWidth(), color = colors.accent)
                else if (state.eligibleMedia.isEmpty()) {
                    LifeEmptyState("今天还没有照片。\n留白，也是一页生活。", Modifier.heightIn(min = 150.dp))
                } else LifePhotoStack(state.eligibleMedia, onPhotoClick = { viewerPhotoId = it.id })
            }
            item {
                HomeSectionLabel("历史 · 旧日的一页", "MEMORY")
                if (state.historyItems.isEmpty()) LifeEmptyState("以后，这里会慢慢留下以前的日子。")
                else state.historyItems.forEach {
                    Text(it.date.toString(), style = LifeText.caption, color = colors.muted)
                    Text(it.title, style = LifeText.body, color = colors.ink)
                    Spacer(Modifier.height(12.dp))
                }
            }
            item {
                HomeSectionLabel("未来 · 盼着的日子", "AHEAD")
                if (state.futureIntents.isEmpty()) LifeEmptyState("想去的地方、想做的事，记下后会在这里等你。")
                else state.futureIntents.forEach {
                    Text(it.title, style = LifeText.body, color = colors.ink)
                    Text("预计 · ${it.expectedDate ?: "日期未定"}", style = LifeText.caption, color = colors.inkSecondary)
                    Spacer(Modifier.height(12.dp))
                }
            }
            item {
                HomeSectionLabel("今天留下的事", "")
                if (state.todayFacts.isEmpty()) LifeEmptyState("今天还没有写下的经历。")
                else state.todayFacts.forEach {
                    Text(it.title, style = LifeText.body, color = colors.ink)
                    Text(it.domainLabel, style = LifeText.caption, color = colors.muted)
                }
                Spacer(Modifier.height(24.dp))
            }
        }
        HorizontalDivider(color = colors.line)
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            CompanionEntry(onCompanion, Modifier.weight(1f))
            TextButton(onClick = onCapture, modifier = Modifier.heightIn(min = 48.dp)) {
                Icon(Icons.Outlined.Add, contentDescription = null)
                Text("记一下")
            }
        }
    }
    val selected = state.eligibleMedia.firstOrNull { it.id == viewerPhotoId }
    if (selected != null) LifePhotoViewer(state.eligibleMedia, selected.id, onDismiss = { viewerPhotoId = null })
}

@Composable
private fun HomeSectionLabel(title: String, english: String) {
    val colors = LocalLifeOsColors.current
    Row(Modifier.fillMaxWidth().padding(top = 26.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = LifeText.caption.copy(fontWeight = FontWeight.Medium), color = colors.inkSecondary)
        Spacer(Modifier.width(10.dp))
        if (english.isNotEmpty()) Text(english, style = LifeText.caption, color = colors.muted)
        HorizontalDivider(Modifier.weight(1f).padding(start = 12.dp), color = colors.line)
    }
}

@Preview(name = "Life OS · Production empty", showBackground = true)
@Composable
private fun LifeOsHomePreview() {
    LifeOsTheme { LifeOsHomeScreen(HomeUiState(LocalDate.of(2026, 10, 8)), {}, {}, {}, {}, {}) }
}
