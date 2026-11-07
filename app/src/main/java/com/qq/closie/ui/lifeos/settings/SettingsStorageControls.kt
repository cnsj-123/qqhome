package com.qq.closie.ui.lifeos.settings

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import coil.annotation.ExperimentalCoilApi
import coil.disk.DiskCache
import coil.imageLoader
import coil.memory.MemoryCache
import com.qq.closie.data.backup.RestoreStartupGate
import com.qq.closie.life.media.MediaResourceRole
import com.qq.closie.life.repository.MediaRepository
import com.qq.closie.life.ui.theme.BundledText as Text
import com.qq.closie.ui.lifeos.theme.LocalLifeOsColors
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Only Coil owns these bytes. Backup/export scratch and all originals are outside this API. */
@OptIn(ExperimentalCoilApi::class)
internal class SettingsImageCache(private val memory: MemoryCache?, private val disk: DiskCache?) {
    fun sizeBytes(): Long = (memory?.size?.toLong() ?: 0L) + (disk?.size ?: 0L)
    fun clear() {
        memory?.clear()
        disk?.clear()
    }
}

internal data class SettingsMediaStorage(
    val assets: Int,
    val localOriginals: Int,
    val externalReferences: Int,
    val missingOriginals: Int,
    val externalUris: List<String>,
)

/** A read-only projection of the existing media owner, never a second media index. */
internal suspend fun readSettingsMediaStorage(media: MediaRepository, fileExists:(String)->Boolean = {File(it).isFile}): SettingsMediaStorage =
    media.storageSnapshot().let { snapshot ->
        val assets = snapshot.assets
        val ids = assets.map {it.id}.toHashSet()
        val originalRoles=setOf(MediaResourceRole.ORIGINAL, MediaResourceRole.PRIMARY_IMAGE,
            MediaResourceRole.ORIGINAL_CONTAINER, MediaResourceRole.PAIRED_VIDEO, MediaResourceRole.AUDIO)
        val originals = snapshot.resources.filter { it.mediaAssetId in ids && it.role in originalRoles }
        val local = originals.mapNotNull { it.managedPath }.distinct()
        val external = originals.mapNotNull { it.contentUri }.distinct()
        val localPresence = local.associateWith {currentCoroutineContext().ensureActive();fileExists(it)}
        val represented = originals.map {it.mediaAssetId}.toHashSet()
        check(RestoreStartupGate.isReady && RestoreStartupGate.state.value.restoreGeneration==snapshot.restoreGeneration) {
            "媒体来源已因恢复而变化，请重读"
        }
        SettingsMediaStorage(assets.size, localPresence.values.count {it}, external.size,
            localPresence.values.count {!it} + assets.count {it.id !in represented},
            external)
    }

private suspend fun readableReferences(context: Context, uris: List<String>): Int = uris.count { source ->
    currentCoroutineContext().ensureActive()
    try {
        context.contentResolver.openFileDescriptor(Uri.parse(source), "r")?.use { true } ?: false
    } catch (_: Exception) { false }
}

/** The final two measured rows of 数据与备份. All counts are read from the current process/data. */
@OptIn(ExperimentalCoilApi::class)
@Composable
internal fun SettingsStorageControls(media: MediaRepository, enabled: Boolean = true) {
    val context = LocalContext.current
    val colors = LocalLifeOsColors.current
    val loader = context.imageLoader
    val cache = remember(loader) { SettingsImageCache(loader.memoryCache, loader.diskCache) }
    val scope = rememberCoroutineScope()
    val gate by RestoreStartupGate.state.collectAsState()
    var refresh by remember { mutableIntStateOf(0) }
    var bytes by remember { mutableStateOf<Long?>(null) }
    var clearing by remember { mutableStateOf(false) }
    var cacheResult by remember { mutableStateOf<String?>(null) }
    var mediaSummary by remember(media,gate.restoreGeneration) { mutableStateOf<SettingsMediaStorage?>(null) }
    var mediaFailure by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }
    var readable by remember(media,gate.restoreGeneration) { mutableStateOf<Int?>(null) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh++ }

    LaunchedEffect(cache, refresh, enabled) {
        if (!enabled) return@LaunchedEffect
        try {
            bytes = withContext(Dispatchers.IO) { cache.sizeBytes() }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { bytes = null; cacheResult = "缓存大小暂不可读取" }
    }
    LaunchedEffect(media, refresh, enabled, gate.restoreGeneration, gate.canAcquireBusinessLease) {
        if (!enabled || !gate.canAcquireBusinessLease) { mediaSummary = null; readable = null; return@LaunchedEffect }
        mediaFailure = false
        readable = null
        try {
            mediaSummary = withContext(Dispatchers.IO) { readSettingsMediaStorage(media) }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { mediaSummary = null; mediaFailure = true }
    }
    LaunchedEffect(mediaSummary, expanded, enabled, gate.restoreGeneration, gate.canAcquireBusinessLease) {
        readable = null
        val summary = mediaSummary ?: return@LaunchedEffect
        if (expanded && enabled && gate.canAcquireBusinessLease) readable = withContext(Dispatchers.IO) {
            readableReferences(context, summary.externalUris)
        }
    }

    SettingsSection("媒体与存储")
    Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("缓存清理", style = SettingsText.body, color = colors.ink)
            val size = bytes?.let { android.text.format.Formatter.formatShortFileSize(context, it) }
            Text(if (size == null) "正在读取缩略图缓存…" else "缩略图与临时渲染共 $size · 不删原件与记录",
                style = SettingsText.detail, color = colors.inkSecondary)
        }
        TextButton(onClick = {
            if (clearing || !enabled) return@TextButton
            clearing = true
            cacheResult = null
            scope.launch {
                try {
                    withContext(Dispatchers.IO) { cache.clear() }
                    bytes = withContext(Dispatchers.IO) { cache.sizeBytes() }
                    cacheResult = "缩略图缓存已清理"
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { cacheResult = "缓存清理未完成，请重试" }
                finally { clearing = false }
            }
        }, modifier = Modifier.widthIn(min = 48.dp).testTag("settings-clear-image-cache"),
            enabled = enabled && !clearing && bytes != null) {
            Text(if (clearing) "清理中" else "清理", style = SettingsText.detail, color = colors.accent)
        }
    }
    cacheResult?.let { Text(it, style = SettingsText.detail, color = colors.inkSecondary) }
    val summary = mediaSummary.takeIf {enabled && gate.canAcquireBusinessLease}
    SettingsLine("相册引用与媒体备份",
        subtitle = when {
            !enabled || !gate.canAcquireBusinessLease -> "操作完成后可查看媒体状态"
            mediaFailure -> "媒体状态暂不可读取，请重新进入本页"
            summary == null -> "正在读取本地媒体…"
            summary.assets == 0 -> "尚未添加媒体 · 不上传系统相册"
            else -> "${summary.localOriginals} 份应用内原件 · ${summary.externalReferences} 份外部引用"
        },
        trailing = if ((summary?.missingOriginals ?: 0) > 0) "待处理" else "查看",
        onClick = if (summary != null && enabled && gate.canAcquireBusinessLease) ({ expanded = !expanded }) else null)
    if (expanded && summary != null) {
        Column(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            if (summary.missingOriginals > 0) Text("${summary.missingOriginals} 份原件暂不可用",
                style = SettingsText.detail, color = colors.inkSecondary)
            if (summary.externalReferences > 0) Text(readable?.let {
                "$it 份引用可读取 · ${summary.externalReferences - it} 份需重新选择原件"
            } ?: "正在检查引用访问权限…", style = SettingsText.detail, color = colors.inkSecondary)
            Text("应用内原件随完整备份保存。外部照片与文件只保存引用，换机后可能需要重新选择；引用可读取不代表已经备份。",
                style = SettingsText.detail, color = colors.inkSecondary)
        }
    }
}
