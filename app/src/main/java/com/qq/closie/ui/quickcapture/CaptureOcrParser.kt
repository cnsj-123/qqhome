package com.qq.closie.ui.quickcapture

/**
 * Lightweight, rule-based extraction of a product name and price from raw OCR text. This is
 * intentionally heuristic (no AI): quick capture surfaces a draft the user confirms before saving.
 */
object CaptureOcrParser {

    fun parseName(text: String): String = text.lines()
        .map { it.trim().trim('|', '-', '—', ' ') }
        .firstOrNull { line ->
            line.length in 2..60 &&
                !line.startsWith("¥") && !line.startsWith("￥") &&
                !line.matches(Regex(".*\\d.*")) &&
                BOILERPLATE.none { line.contains(it) }
        }.orEmpty()

    fun parsePrice(text: String): Double? =
        Regex("""[¥￥]\s*(\d+(?:\.\d{1,2})?)""").find(text)?.groupValues?.get(1)?.toDoubleOrNull()

    private val BOILERPLATE = setOf(
        "加入购物车", "立即购买", "马上抢", "收藏", "关注", "详情", "评价", "店铺",
        "首页", "客服", "购物车", "我的", "领券", "优惠", "分享", "复制", "打开"
    )
}
