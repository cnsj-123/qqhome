@file:OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)

package com.qq.closie.ui.lifeos.settings

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import com.qq.closie.life.ui.theme.LifeFonts
import com.qq.closie.ui.lifeos.components.reducedLifeMotion

/** Measurements from the 405.33 × 880 settings frames, rather than the journal body scale. */
internal object SettingsText {
    val title = TextStyle(fontFamily = LifeFonts.Journal, fontSize = 24.sp, lineHeight = 32.sp, letterSpacing = .96.sp)
    val body = TextStyle(fontFamily = LifeFonts.Journal, fontSize = 14.sp, lineHeight = 16.sp, letterSpacing = .4.sp)
    val caption = body.copy(fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = .5.sp)
    val detail = body.copy(fontSize = 11.sp, lineHeight = 16.sp, letterSpacing = .3.sp)
    val tiny = body.copy(fontSize = 10.sp, lineHeight = 12.sp, letterSpacing = .3.sp)
}

private data class SettingsMotionScope(val shared: SharedTransitionScope, val visibility: AnimatedVisibilityScope)
private val LocalSettingsMotion = staticCompositionLocalOf<SettingsMotionScope?> { null }

/** The NavHost supplies its actual animated destination scope; no second navigation state. */
@Composable
internal fun SettingsMotionScopeProvider(
    sharedScope: SharedTransitionScope,
    visibilityScope: AnimatedVisibilityScope,
    content: @Composable () -> Unit
) = CompositionLocalProvider(LocalSettingsMotion provides SettingsMotionScope(sharedScope, visibilityScope), content = content)

/** Only navigation rows participate. Expanding sections and permission choices never share a page key. */
internal fun settingsPaperKey(title: String): String? = when (title) {
    "主题", "主题与配色" -> "appearance"
    "备份与恢复", "数据与备份" -> "backup"
    "导出与媒体存储", "导出中心" -> "export"
    "权限与授权", "隐私与 AI 权限" -> "privacy"
    "自动发现与通知", "自动发现" -> "discovery"
    "待确认记录", "待确认工作区" -> "review"
    "账目专项设置" -> "finance-settings"
    else -> null
}

internal fun Modifier.settingsPaperBounds(key: String?): Modifier = composed {
    val scope = LocalSettingsMotion.current
    if (scope == null || key == null || reducedLifeMotion()) this
    else with(scope.shared) {
        this@composed.sharedBounds(
            rememberSharedContentState("settings-paper:$key"), scope.visibility,
            boundsTransform = { _, _ -> spring(dampingRatio = .5f, stiffness = 350f) }
        )
    }
}

internal fun Modifier.settingsPaperTitle(key: String?): Modifier = composed {
    val scope = LocalSettingsMotion.current
    if (scope == null || key == null || reducedLifeMotion()) this
    else with(scope.shared) {
        this@composed.sharedElement(
            rememberSharedContentState("settings-title:$key"), scope.visibility,
            boundsTransform = { _, _ -> spring(dampingRatio = .5f, stiffness = 350f) }
        )
    }
}
