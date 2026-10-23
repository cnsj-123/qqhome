package com.qq.closie.ui.lifeos.finance

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/** Generated from res/raw/finance_icon_*.svg. 24-unit source gives 1.2dp strokes at 20dp. */
internal object FinanceCategoryVectors {
    val labels = mapOf("food" to "餐饮", "transport" to "交通", "home" to "居家", "health" to "健康", "shopping" to "购物", "gift" to "礼物", "other" to "其他")
    private val paths = mapOf(
        "food" to "M7 3v7M4 3v5c0 2 6 2 6 0V3M7 10v11M17 3c-3 3-3 8 0 8h2V3h-2v18",
        "transport" to "M5 16V6c0-4 14-4 14 0v10H5ZM5 10h14M8 6h8M7 16v4M17 16v4M8 13h.01M16 13h.01",
        "home" to "M3 11 12 3l9 8M5 10v11h14V10M10 21v-7h4v7",
        "health" to "M9 3h6v6h6v6h-6v6H9v-6H3V9h6Z",
        "shopping" to "M5 7h14l2 14H3L5 7ZM8 8V6a4 4 0 0 1 8 0v2",
        "gift" to "M3 9h18v4H3ZM5 13v8h14v-8M12 9v12M12 9C6 9 5 7 6 5c2-3 6 1 6 4ZM12 9c6 0 7-2 6-4-2-3-6 1-6 4Z",
        "other" to "M5 5h5v5H5ZM14 5h5v5h-5ZM5 14h5v5H5ZM14 14h5v5h-5Z"
    )
    private val vectors = paths.mapValues { (key, path) ->
        ImageVector.Builder("finance-$key", 24.dp, 24.dp, 24f, 24f).apply {
            addPath(pathData = PathParser().parsePathString(path).toNodes(), fill = null,
                stroke = SolidColor(Color.Black), strokeLineWidth = 1.44f,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
        }.build()
    }
    internal fun path(key:String)=paths[key] ?: paths.getValue("other")
    fun get(key: String) = vectors[key] ?: vectors.getValue("other")
}
