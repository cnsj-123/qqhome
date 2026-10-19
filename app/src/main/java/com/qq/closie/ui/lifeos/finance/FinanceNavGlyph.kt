package com.qq.closie.ui.lifeos.finance

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.material3.LocalContentColor

@Composable
internal fun FinanceNavGlyph(view: FinanceView) {
    val color = LocalContentColor.current
    Canvas(Modifier.size(22.dp)) {
        val unit = size.width / 24f
        fun line(x: Float, y: Float, xx: Float, yy: Float) = drawLine(color, Offset(x * unit, y * unit), Offset(xx * unit, yy * unit), 1.45f * unit, StrokeCap.Round)
        fun box(x: Float, y: Float, w: Float, h: Float) = drawRect(color, Offset(x * unit, y * unit), Size(w * unit, h * unit), style = Stroke(1.45f * unit))
        when (view) {
            FinanceView.TRANSACTIONS -> { box(5f, 3f, 14f, 18f); line(8f, 8f, 16f, 8f); line(8f, 12f, 16f, 12f); line(8f, 16f, 13f, 16f) }
            FinanceView.ACCOUNTS -> { box(3f, 5f, 18f, 15f); box(14f, 10f, 7f, 5f); line(4f, 5f, 17f, 2f) }
            FinanceView.INSIGHTS -> { line(4f, 21f, 21f, 21f); line(6f, 17f, 6f, 10f); line(12f, 17f, 12f, 3f); line(18f, 17f, 18f, 7f) }
        }
    }
}
