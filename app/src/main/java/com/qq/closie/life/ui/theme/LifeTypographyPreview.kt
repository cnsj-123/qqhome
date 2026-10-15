package com.qq.closie.life.ui.theme

import com.qq.closie.life.ui.theme.BundledText as Text

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Review-only specimen of the current Home A application hierarchy and bundled fallback. */
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
            role = "Brand — Shalimar · 32sp",
            sample = "Life OS",
            style = LifeType.Brand,
            gap = dims.itemGap
        )
        Specimen(
            role = "Display — 演示悠然小楷 · 30sp",
            sample = "生活自有答案",
            style = LifeType.Display,
            gap = dims.itemGap
        )
        Specimen(
            role = "PageTitle — AR PL UKai TW · 23sp",
            sample = "把每一件小事，安静地放好。",
            style = LifeType.PageTitle,
            gap = dims.itemGap
        )
        Specimen(
            role = "EditorialTitle — AR PL UKai TW · 21sp",
            sample = "安静的秩序，慢慢长出来。",
            style = LifeType.EditorialTitle,
            gap = dims.itemGap
        )
        Specimen(
            role = "SectionTitle — 平方洒脱体 · 17sp",
            sample = "最近",
            style = LifeType.SectionTitle,
            gap = dims.itemGap
        )
        Specimen(
            role = "ModuleTitle — 平方洒脱体 · 18sp",
            sample = "衣橱",
            style = LifeType.ModuleTitle,
            gap = dims.itemGap
        )
        Specimen(
            role = "Body — 平方洒脱体 · 16sp",
            sample = "一段普通正文。",
            style = LifeType.Body,
            gap = dims.itemGap
        )
        Specimen(
            role = "BodySecondary — 平方洒脱体 · 14sp",
            sample = "一段次级正文，说明与呼吸。",
            style = LifeType.BodySecondary,
            gap = dims.itemGap
        )
        Specimen(
            role = "Caption — 平方三生体 · 13sp",
            sample = "9月24日 · 星期四",
            style = LifeType.Caption,
            gap = dims.itemGap
        )
        Specimen(
            role = "Navigation — 平方洒脱体 · 13sp",
            sample = "首页 时间线 生活 我的",
            style = LifeType.Navigation,
            gap = dims.itemGap
        )
        Specimen(
            role = "Action — 平方洒脱体 · 15sp",
            sample = "开始整理",
            style = LifeType.Action,
            gap = dims.itemGap
        )
        Specimen(
            role = "MonoNumber — Noto Sans SC · 15sp · tnum",
            sample = "¥ 3,705.50",
            style = LifeType.MonoNumber,
            gap = dims.itemGap
        )
        Specimen(
            role = "Timestamp — Almendra · 13sp",
            sample = "09.24 · 08:31",
            style = LifeType.Timestamp,
            gap = dims.itemGap
        )
        Specimen(
            role = "HandNote — Pacifico · 16sp",
            sample = "quiet days, soft archive",
            style = LifeType.HandNote,
            gap = dims.itemGap
        )
        Specimen(
            role = "EmptyTitle — 演示悠然小楷 · 23sp",
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
    com.qq.closie.ui.lifeos.theme.LifeOsTheme {
        LifeTypeSpecimen()
    }
}
