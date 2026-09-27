package com.qq.closie.data

/**
 * A transient, not-yet-saved product draft handed from the quick-capture preview to the editor.
 * Nothing is written to the wardrobe here: the editor creates the item only when the user taps
 * 保存. This keeps "保存并继续编辑" honest — the preview does not silently add a wardrobe item.
 */
data class PendingProductDraft(
    val name: String = "",
    val price: Double? = null,
    val platform: String = "",
    val screenshotPath: String? = null
)

object PendingProduct {
    @Volatile
    var draft: PendingProductDraft? = null
}
