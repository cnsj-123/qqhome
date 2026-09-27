package com.qq.closie.life.ui.shell

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import com.qq.closie.BuildConfig
import com.qq.closie.life.crash.LifeCrashLog
import com.qq.closie.life.ui.components.LifeDivider
import com.qq.closie.life.ui.components.LifeGap
import com.qq.closie.life.ui.components.LifeListRow
import com.qq.closie.life.ui.components.LifePage
import com.qq.closie.life.ui.components.LifeSection
import com.qq.closie.life.ui.components.LifeTopBar
import com.qq.closie.life.ui.theme.LifeTheme
import com.qq.closie.life.ui.theme.rememberLifeDimensions

/**
 * 我的 — settings, backup, the honest status of what does not exist yet, and (debug builds only)
 * the local crash journal.
 *
 * The grouping matters: 数据与备份 and 设置 are real entries that navigate into Closie's existing
 * 设置 screen. 媒体库 and 同步 are rendered as disabled rows with explicit 即将开放 / 尚未实现
 * values — the app has no sync server and no media library screen, and a tappable row there would
 * be the single most misleading thing in the app. 版本 is read-only information, so it is not
 * greyed out like a placeholder: it is simply not clickable.
 *
 * The 调试 block exists only when [BuildConfig.DEBUG] is true. It surfaces the on-device crash
 * journal written by [LifeCrashLog] (filesDir/debug/last_crash.txt): one row copies the stack to
 * the clipboard (the "send it to me" path on a real device), one row clears it. In release the
 * whole block compiles away — R8 strips the `if (BuildConfig.DEBUG)` branch and nothing crash-
 * related is reachable.
 */
@Composable
fun ProfileScreen(
    onOpenSettings: () -> Unit,
    onOpenBackup: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val dims = rememberLifeDimensions()

    // Crash journal state is read once per visit; copy/clear mutate it in place. Reading the
    // file on every recomposition would be pointless disk churn for a page this quiet.
    var crashLog by remember {
        mutableStateOf(if (BuildConfig.DEBUG) LifeCrashLog.read(context) else null)
    }

    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager

    ProfileContent(
        versionName = BuildConfig.VERSION_NAME,
        versionCode = BuildConfig.VERSION_CODE.toString(),
        crashLog = crashLog,
        onCopyCrashLog = {
            crashLog?.let { text ->
                clipboard?.setPrimaryClip(ClipData.newPlainText("Life OS crash log", text))
            }
        },
        onClearCrashLog = {
            LifeCrashLog.clear(context)
            crashLog = null
        },
        onOpenSettings = onOpenSettings,
        onOpenBackup = onOpenBackup,
        modifier = modifier
    )
}

@Composable
internal fun ProfileContent(
    versionName: String,
    versionCode: String,
    crashLog: String?,
    onCopyCrashLog: () -> Unit,
    onClearCrashLog: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenBackup: () -> Unit,
    modifier: Modifier = Modifier
) {
    val dims = rememberLifeDimensions()
    LifePage(modifier = modifier) {
        item {
            LifeTopBar(title = "我的")
        }

        item { LifeGap(dims.blockGap) }

        item {
            Column(modifier = Modifier.fillMaxWidth()) {
                LifeListRow(title = "数据与备份", onClick = onOpenBackup)
                LifeDivider()
                LifeListRow(title = "设置", onClick = onOpenSettings)
            }
        }

        item { LifeGap(dims.sectionGap) }

        item {
            Column(modifier = Modifier.fillMaxWidth()) {
                LifeListRow(title = "媒体库", value = "即将开放", enabled = false)
                LifeDivider()
                LifeListRow(title = "同步", value = "尚未实现", enabled = false)
            }
        }

        item { LifeGap(dims.sectionGap) }

        item {
            // Read-only identity: primary value is the human version, secondary is the build
            // number that maps 1:1 to a CI artifact (LifeOS-v0.2.0-build200001.apk).
            LifeListRow(
                title = "版本",
                value = versionName,
                subtitle = "Build $versionCode"
            )
        }

        // ── Debug-only crash journal ────────────────────────────────────────────────────────
        // Never rendered in release: crashLog is null (guard in ProfileScreen) AND the whole
        // branch is stripped by R8 because BuildConfig.DEBUG is a compile-time constant there.
        if (BuildConfig.DEBUG) {
            item { LifeGap(dims.sectionGap) }

            item {
                LifeSection(title = "调试") {
                    LifeListRow(
                        title = "上次崩溃日志",
                        value = if (crashLog != null) "复制" else "无记录",
                        enabled = crashLog != null,
                        onClick = if (crashLog != null) onCopyCrashLog else null,
                        showChevron = false
                    )
                    LifeDivider()
                    LifeListRow(
                        title = "清除崩溃日志",
                        value = "清除",
                        enabled = crashLog != null,
                        onClick = if (crashLog != null) onClearCrashLog else null,
                        showChevron = false
                    )
                }
            }
        }
    }
}

// Device matrix (360 compact / 393 primary / 411 regular), with and without a crash journal.
@Preview(showBackground = true, widthDp = 360, heightDp = 800, name = "我的 — 360x800")
@Preview(showBackground = true, widthDp = 393, heightDp = 852, name = "我的 — 393x852")
@Preview(showBackground = true, widthDp = 411, heightDp = 891, name = "我的 — 411x891")
@Composable
private fun ProfilePreview() {
    LifeTheme {
        ProfileContent(
            versionName = "0.2.0",
            versionCode = "200001",
            crashLog = null,
            onCopyCrashLog = {},
            onClearCrashLog = {},
            onOpenSettings = {},
            onOpenBackup = {}
        )
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 800, name = "我的 — 有崩溃日志 360x800")
@Preview(showBackground = true, widthDp = 393, heightDp = 852, name = "我的 — 有崩溃日志 393x852")
@Preview(showBackground = true, widthDp = 411, heightDp = 891, name = "我的 — 有崩溃日志 411x891")
@Composable
private fun ProfilePreviewWithCrash() {
    LifeTheme {
        ProfileContent(
            versionName = "0.2.0",
            versionCode = "200001",
            crashLog = "Life OS crash log\nTime: 2026-09-24 08:31:05.123\n…",
            onCopyCrashLog = {},
            onClearCrashLog = {},
            onOpenSettings = {},
            onOpenBackup = {}
        )
    }
}
