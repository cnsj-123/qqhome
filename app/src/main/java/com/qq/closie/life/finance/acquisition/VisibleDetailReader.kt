package com.qq.closie.life.finance.acquisition

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/** Android handles stay synchronous and local. The pure reader uses synthetic trees in tests. */
internal object VisibleDetailReader {
    fun read(root: AccessibilityNodeInfo, adapter: TransactionDetailAdapter): VisibleDetailFields? =
        SemanticDetailReader.read(AndroidDetailNode(root), adapter)
    fun inspect(root: AccessibilityNodeInfo, adapter: TransactionDetailAdapter): DetailReadInspection =
        SemanticDetailReader.inspect(AndroidDetailNode(root), adapter)
    fun inspectAuthorizedRoot(root: AccessibilityNodeInfo, adapter: TransactionDetailAdapter,
        windowId: Int, packageName: String, windowBounds: Rect): DetailReadInspection {
        if (root.isVisibleToUser) return inspect(root, adapter)
        if (windowBounds.isEmpty || root.windowId != windowId || root.packageName?.toString() != packageName) {
            return DetailReadInspection(rejection = DetailReadRejection.NOT_DETAIL)
        }
        // Some window roots are structural and marked invisible. Open only that container;
        // descendants must be truly visible and belong to the same authorized window.
        return SemanticDetailReader.inspect(AndroidDetailNode(root, WindowScope(root, windowId, packageName, windowBounds)), adapter)
    }

    private data class WindowScope(val root: AccessibilityNodeInfo, val id: Int, val packageName: String, val bounds: Rect)

    private class AndroidDetailNode(private val node: AccessibilityNodeInfo, private val scope: WindowScope? = null) : DetailNode {
        private val structuralRoot get() = scope != null && node == scope.root
        override val visible: Boolean
            get() {
                if (structuralRoot) return true
                if (!node.isVisibleToUser) return false
                val owner = scope ?: return true
                if (node.windowId != owner.id || node.packageName?.toString() != owner.packageName) return false
                val bounds = Rect().also(node::getBoundsInScreen)
                return Rect.intersects(owner.bounds, bounds)
            }
        override val sensitive get() = node.isPassword || node.isEditable
        override val text get() = if (structuralRoot) null else node.text
        override val childCount get() = node.childCount
        override fun child(index: Int) = node.getChild(index)?.let {AndroidDetailNode(it, scope)}
        override fun parent() = if (structuralRoot) null else node.parent?.let {AndroidDetailNode(it, scope)}
        @Suppress("DEPRECATION") override fun close() = node.recycle()
    }
}
