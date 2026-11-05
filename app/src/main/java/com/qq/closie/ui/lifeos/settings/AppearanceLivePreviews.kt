package com.qq.closie.ui.lifeos.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import com.qq.closie.data.appearance.AppearanceSettings
import com.qq.closie.life.ui.theme.BundledText as Text
import com.qq.closie.ui.lifeos.components.LifeFunctionPages
import com.qq.closie.ui.lifeos.components.LifePaperBackdrop
import com.qq.closie.ui.lifeos.components.LocalFunctionPagePolicy
import com.qq.closie.ui.lifeos.drawer.LifeOsDrawer
import com.qq.closie.ui.lifeos.home.*
import com.qq.closie.ui.lifeos.theme.LifeOsTheme
import com.qq.closie.ui.lifeos.theme.LifeText
import java.time.LocalDate

private val previewDate = LocalDate.of(2026, 10, 12)
private val previewHome = HomeUiState(
    date = previewDate,
    todayFacts = listOf(HomeFactUi("preview-today", "午后在小区散步", "日记")),
    historyItems = listOf(HistoryItemUi("preview-memory", previewDate.minusDays(4), "秋天第一次穿上薄外套。")),
    futureIntents = listOf(FutureIntentUi("preview-ahead", previewDate.plusDays(5), "周末去旧书店"))
)

@Composable
internal fun AppearanceLivePreviews(
    settings: AppearanceSettings, home: Boolean = true, function: Boolean = true, sideBySide: Boolean = false
) {
    LifeOsTheme(settings) {
        CompositionLocalProvider(LocalFunctionPagePolicy provides null) {
            if (sideBySide) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (home) PreviewCard(settings, drawer = false, Modifier.weight(1f))
                    if (function) PreviewCard(settings, drawer = true, Modifier.weight(1f))
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (home) PreviewCard(settings, drawer = false)
                    if (function) PreviewCard(settings, drawer = true)
                }
            }
        }
    }
}

@Composable
private fun PreviewCard(settings: AppearanceSettings, drawer: Boolean, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(if (drawer) "功能页预览" else "首页预览", style = LifeText.caption)
        Surface(shape = RoundedCornerShape(12.dp)) {
            BoxWithConstraints(Modifier.fillMaxWidth().aspectRatio(405f / 550f).clipToBounds().clearAndSetSemantics {}) {
                val scale = maxWidth / 405.dp
                val preview = Modifier.wrapContentSize(Alignment.TopStart, unbounded = true)
                    .requiredSize(405.dp, 550.dp).graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        transformOrigin = TransformOrigin(0f, 0f)
                    }
                Box(preview) {
                    if (drawer) {
                        LifeFunctionPages(open = true, enabled = false, style = settings.functionPageStyle,
                            reducedMotion = false, home = true, effects = settings.functionEffects, onOpenChange = {},
                            functionPage = {
                                LifeOsDrawer({}, {}, active = false, moduleOrder = settings.moduleOrder, interactive = false)
                            }) { PreviewHome() }
                    } else {
                        LifePaperBackdrop(settings.functionEffects, Modifier.matchParentSize(), home = true)
                        PreviewHome()
                    }
                }
            }
        }
    }
}

@Composable
private fun PreviewHome() {
    LifeOsHomeScreen(previewHome, {}, {}, {}, {}, {}, interactive = false, today = previewDate)
}
