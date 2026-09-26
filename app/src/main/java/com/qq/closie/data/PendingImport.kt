package com.qq.closie.data

/**
 * Cross-activity bridge used by "分享至 Closie" so an incoming share message can pre-fill the
 * editor without threading state through the navigation graph.
 */
object PendingImport {
    @Volatile
    var text: String? = null
}
