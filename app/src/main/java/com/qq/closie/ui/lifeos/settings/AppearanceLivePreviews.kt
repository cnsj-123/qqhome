package com.qq.closie.ui.lifeos.settings

import com.qq.closie.life.ui.theme.BundledText as Text
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.qq.closie.R
import com.qq.closie.data.appearance.AppearanceSettings
import com.qq.closie.life.ui.theme.LifeType
import com.qq.closie.ui.lifeos.components.LifePaperBackdrop
import com.qq.closie.ui.lifeos.theme.*

/** Display-only examples, using the same paper rendering and type roles as the live surfaces. */
@Composable
internal fun AppearanceLivePreviews(settings: AppearanceSettings) {
    LifeOsTheme(settings) {
        val colors = LocalLifeOsColors.current
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("首页预览", style = LifeText.title)
            Surface(shape = RoundedCornerShape(20.dp), border = BorderStroke(1.dp, colors.line)) {
                Box(Modifier.fillMaxWidth().heightIn(min = 190.dp)) {
                    LifePaperBackdrop(settings.functionEffects, Modifier.matchParentSize(), home = true)
                    Column(Modifier.padding(20.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Life OS", Modifier.weight(1f), style = LifeType.Wordmark, color = colors.ink)
                            Text("＋", style = LifeText.title, color = colors.accentDeep)
                        }
                        Text("今天", style = LifeType.DateHero, color = colors.ink)
                        Text("today", Modifier.padding(top = 12.dp), style = LifeType.JournalSection, color = colors.muted)
                        Text("把今天轻轻收好", style = LifeType.JournalMeta, color = colors.inkSecondary)
                        Image(painterResource(R.drawable.lifeos_launch_cat), null,
                            Modifier.align(Alignment.End).size(52.dp))
                    }
                }
            }
            Text("功能页预览", style = LifeText.title)
            Surface(shape = RoundedCornerShape(20.dp), border = BorderStroke(1.dp, colors.line)) {
                Box(Modifier.fillMaxWidth().heightIn(min = 190.dp)) {
                    // The actual mounted Home paper remains underneath the function surface.
                    LifePaperBackdrop(settings.functionEffects, Modifier.matchParentSize(), home = true)
                    LifePaperBackdrop(settings.functionEffects, Modifier.matchParentSize())
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("账簿", style = LifeText.title, color = colors.ink)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            listOf("收入", "支出", "结余").forEach {
                                Column { Text(it, style = LifeText.caption, color = colors.muted)
                                    Text("—", style = LifeType.MoneyCompact, color = colors.ink) }
                            }
                        }
                        HorizontalDivider(color = colors.line)
                        Text("记录、账户与分类", style = LifeText.body, color = colors.inkSecondary)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("账簿", style = LifeText.caption, color = colors.accentDeep)
                            Text("账户", style = LifeText.caption, color = colors.inkSecondary)
                            Text("分析", style = LifeText.caption, color = colors.inkSecondary)
                        }
                    }
                }
            }
        }
    }
}
