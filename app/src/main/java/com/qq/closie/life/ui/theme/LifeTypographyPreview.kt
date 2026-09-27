package com.qq.closie.life.ui.theme

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * LifeType specimen — every role of the Life OS type system, on one page, in the real faces.
 *
 * This is a design-review surface, not navigation: it never appears in the app's routes. Each
 * entry shows the role name in [LifeType.Caption] and the sample line in the role itself, so a
 * wrong face (system font, hand font where serif belongs) is visible at a glance.
 *
 * The sample lines are the ones the spec pins: Life OS / 生活自有答案 / 把每一件小事，安静地放好。
 * / 最近 / 一段普通正文。/ ¥ 3,705.50 / 09.24 · 08:31 / quiet days, soft archive.
 *
 * The three Latin-only roles carry an explicit "NO CJK" marker in their label, and their samples
 * are Latin/numeric on purpose. HandNote renders "quiet days, soft archive" — English only, by
 * rule. Chinese text must never fall into Caveat or Special Elite: those faces have no CJK glyphs,
 * so the glyphs would be resolved by the SYSTEM font, which is how a device theme font (vivo
 * OriginOS) re-enters Life OS. If any of these three lines ever shows Chinese, the rule is broken.
 */
@Composable
private fun LifeTypeSpecimen() {
    val dims = rememberLifeDimensions()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = dims.pageHorizontal, vertical = dims.pageTop)
    ) {
        Specimen(
            role = "Brand — Noto Serif SC 600 · 28sp",
            sample = "Life OS",
            style = LifeType.Brand,
            gap = dims.itemGap
        )
        Specimen(
            role = "Display — Serif 600 · 30sp",
            sample = "生活自有答案",
            style = LifeType.Display,
            gap = dims.itemGap
        )
        Specimen(
            role = "PageTitle — Serif 600 · 26sp",
            sample = "把每一件小事，安静地放好。",
            style = LifeType.PageTitle,
            gap = dims.itemGap
        )
        Specimen(
            role = "EditorialTitle — Serif 500 · 22sp",
            sample = "安静的秩序，慢慢长出来。",
            style = LifeType.EditorialTitle,
            gap = dims.itemGap
        )
        Specimen(
            role = "SectionTitle — Serif 600 · 14sp",
            sample = "最近",
            style = LifeType.SectionTitle,
            gap = dims.itemGap
        )
        Specimen(
            role = "ModuleTitle — Serif 500 · 21sp",
            sample = "衣橱",
            style = LifeType.ModuleTitle,
            gap = dims.itemGap
        )
        Specimen(
            role = "Body — Sans 400 · 16sp",
            sample = "一段普通正文。",
            style = LifeType.Body,
            gap = dims.itemGap
        )
        Specimen(
            role = "BodySecondary — Sans 300 · 14sp",
            sample = "一段次级正文，说明与呼吸。",
            style = LifeType.BodySecondary,
            gap = dims.itemGap
        )
        Specimen(
            role = "Caption — Sans 400 · 12sp",
            sample = "9月24日 · 星期四",
            style = LifeType.Caption,
            gap = dims.itemGap
        )
        Specimen(
            role = "Navigation — Sans 500 · 11sp",
            sample = "首页 时间线 生活 我的",
            style = LifeType.Navigation,
            gap = dims.itemGap
        )
        Specimen(
            role = "Action — Sans 500 · 15sp",
            sample = "开始整理",
            style = LifeType.Action,
            gap = dims.itemGap
        )
        Specimen(
            role = "MonoNumber — Special Elite · 15sp (NO CJK)",
            sample = "¥ 3,705.50",
            style = LifeType.MonoNumber,
            gap = dims.itemGap
        )
        Specimen(
            role = "Timestamp — Special Elite · 11sp (NO CJK)",
            sample = "09.24 · 08:31",
            style = LifeType.Timestamp,
            gap = dims.itemGap
        )
        Specimen(
            role = "HandNote — Caveat · 18sp (EN only, NO CJK)",
            sample = "quiet days, soft archive",
            style = LifeType.HandNote,
            gap = dims.itemGap
        )
        Specimen(
            role = "EmptyTitle — Sans 500 · 20sp",
            sample = "这里还很安静",
            style = LifeType.EmptyTitle,
            gap = dims.itemGap
        )
    }
}

@Composable
private fun Specimen(role: String, sample: String, style: TextStyle, gap: Dp) {
    Text(text = role, style = LifeType.Caption, color = LifeColors.TextTertiary)
    Spacer(Modifier.height(2.dp))
    Text(text = sample, style = style, color = LifeColors.TextPrimary)
    Spacer(Modifier.height(gap))
}

@Preview(showBackground = true, widthDp = 360, heightDp = 2000, name = "LifeType — 360x800 compact")
@Preview(showBackground = true, widthDp = 393, heightDp = 2000, name = "LifeType — 393x852")
@Preview(showBackground = true, widthDp = 411, heightDp = 2000, name = "LifeType — 411x891 regular")
@Composable
private fun LifeTypographyPreview() {
    LifeTheme {
        LifeTypeSpecimen()
    }
}
