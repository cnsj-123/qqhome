package com.qq.closie.life.finance.acquisition

import android.view.accessibility.AccessibilityNodeInfo

/** Android handles stay synchronous and local. The pure reader uses synthetic trees in tests. */
internal object VisibleDetailReader {
    fun read(root: AccessibilityNodeInfo, adapter: TransactionDetailAdapter): VisibleDetailFields? =
        SemanticDetailReader.read(AndroidDetailNode(root), adapter)
    fun inspect(root: AccessibilityNodeInfo, adapter: TransactionDetailAdapter, includeOccluded:Boolean=false): DetailReadInspection =
        SemanticDetailReader.inspect(AndroidDetailNode(root,includeOccluded), adapter)
    private class AndroidDetailNode(private val node: AccessibilityNodeInfo,private val includeOccluded:Boolean=false) : DetailNode {
        override val visible get() = includeOccluded || node.isVisibleToUser
        override val sensitive get() = node.isPassword || node.isEditable
        override val text get() = node.text
        override val childCount get() = node.childCount
        override fun child(index: Int) = node.getChild(index)?.let {AndroidDetailNode(it,includeOccluded)}
        override fun parent() = node.parent?.let {AndroidDetailNode(it,includeOccluded)}
        @Suppress("DEPRECATION") override fun close() = node.recycle()
    }
}
