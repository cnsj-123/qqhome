package com.qq.closie.life.ui.shell

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.qq.closie.BuildConfig
import com.qq.closie.life.ui.components.LifeDivider
import com.qq.closie.life.ui.components.LifeGap
import com.qq.closie.life.ui.components.LifeListRow
import com.qq.closie.life.ui.components.LifePage
import com.qq.closie.life.ui.components.LifeTopBar
import com.qq.closie.life.ui.theme.LifeSpacing
import com.qq.closie.life.ui.theme.LifeTheme

/**
 * 我的 — settings, backup, and the honest status of what does not exist yet.
 *
 * The grouping matters: 数据与备份 and 设置 are real entries that navigate into Closie's existing
 * 设置 screen. 媒体库 and 同步 are rendered as disabled rows with explicit 即将开放 / 尚未实现
 * values — the app has no sync server and no media library screen, and a tappable row there would
 * be the single most misleading thing in the app. 版本 is read-only information, so it is not
 * greyed out like a placeholder: it is simply not clickable.
 */
@Composable
fun ProfileScreen(
    onOpenSettings: () -> Unit,
    onOpenBackup: () -> Unit,
    modifier: Modifier = Modifier
) {
    ProfileContent(
        versionName = BuildConfig.VERSION_NAME,
        onOpenSettings = onOpenSettings,
        onOpenBackup = onOpenBackup,
        modifier = modifier
    )
}

@Composable
internal fun ProfileContent(
    versionName: String,
    onOpenSettings: () -> Unit,
    onOpenBackup: () -> Unit,
    modifier: Modifier = Modifier
) {
    LifePage(modifier = modifier) {
        item {
            LifeTopBar(title = "我的")
        }

        item { LifeGap(LifeSpacing.xl) }

        item {
            Column(modifier = Modifier.fillMaxWidth()) {
                LifeListRow(title = "数据与备份", onClick = onOpenBackup)
                LifeDivider()
                LifeListRow(title = "设置", onClick = onOpenSettings)
            }
        }

        item { LifeGap(LifeSpacing.sectionGap) }

        item {
            Column(modifier = Modifier.fillMaxWidth()) {
                LifeListRow(title = "媒体库", value = "即将开放", enabled = false)
                LifeDivider()
                LifeListRow(title = "同步", value = "尚未实现", enabled = false)
            }
        }

        item { LifeGap(LifeSpacing.sectionGap) }

        item {
            LifeListRow(title = "版本", value = versionName)
        }
    }
}

@Preview(showBackground = true, name = "我的")
@Composable
private fun ProfilePreview() {
    LifeTheme {
        ProfileContent(
            versionName = "0.1.0",
            onOpenSettings = {},
            onOpenBackup = {}
        )
    }
}
