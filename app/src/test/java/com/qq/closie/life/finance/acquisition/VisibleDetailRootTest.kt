package com.qq.closie.life.finance.acquisition

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VisibleDetailRootTest {
    private val bounds = Rect(0, 0, 400, 800)
    private val pkg = FinanceHostFixtures.ALIPAY
    private val adapter = ProviderRegistry.providers.single { pkg in it.packageNames }.adapter

    private fun page(): AccessibilityNodeInfo = FinanceHostFixtures.root().also { root ->
        var nextId = 1L
        fun scope(node: AccessibilityNodeInfo) {
            node.packageName = pkg
            ReflectionHelpers.setField(node, "mWindowId", 7)
            ReflectionHelpers.setField(node, "mSourceNodeId", nextId++)
            node.setBoundsInScreen(bounds)
            for (i in 0 until node.childCount) scope(requireNotNull(node.getChild(i)))
        }
        scope(root)
        root.isVisibleToUser = false
    }

    @Test fun structuralInvisibleRootCanExposeItsTrulyVisibleAuthorizedChildren() {
        val root = page()
        assertNull(VisibleDetailReader.inspect(root, adapter).fields)
        val fields = requireNotNull(VisibleDetailReader.inspectAuthorizedRoot(root, adapter, 7, pkg, bounds).fields)
        assertEquals("ORDER-123456", fields.fields["订单号"])
        assertNotNull(adapter.parse(fields, 1L).evidence)
    }

    @Test fun invisibleRootCannotAuthorizeAnotherWindowPackageOrEmptyArea() {
        val root = page()
        assertNull(VisibleDetailReader.inspectAuthorizedRoot(root, adapter, 8, pkg, bounds).fields)
        assertNull(VisibleDetailReader.inspectAuthorizedRoot(root, adapter, 7, FinanceHostFixtures.WECHAT, bounds).fields)
        assertNull(VisibleDetailReader.inspectAuthorizedRoot(root, adapter, 7, pkg, Rect()).fields)
    }

    @Test fun invisibleSubtreeAndForeignOrOffWindowDescendantsStayUnreadable() {
        val root = page()
        // The order row remains visible in the tree, but its value is not authorized.
        val orderRow = requireNotNull(root.getChild(root.childCount - 1))
        val value = requireNotNull(orderRow.getChild(1))
        value.isVisibleToUser = false
        assertNull(VisibleDetailReader.inspectAuthorizedRoot(root, adapter, 7, pkg, bounds).fields?.fields?.get("订单号"))
        value.isVisibleToUser = true
        ReflectionHelpers.setField(value, "mWindowId", 8)
        assertNull(VisibleDetailReader.inspectAuthorizedRoot(root, adapter, 7, pkg, bounds).fields?.fields?.get("订单号"))
        ReflectionHelpers.setField(value, "mWindowId", 7)
        value.packageName = FinanceHostFixtures.WECHAT
        assertNull(VisibleDetailReader.inspectAuthorizedRoot(root, adapter, 7, pkg, bounds).fields?.fields?.get("订单号"))
        value.packageName = pkg
        value.setBoundsInScreen(Rect(500, 0, 600, 800))
        assertNull(VisibleDetailReader.inspectAuthorizedRoot(root, adapter, 7, pkg, bounds).fields?.fields?.get("订单号"))
        value.setBoundsInScreen(bounds)
        orderRow.isVisibleToUser = false
        assertNull(VisibleDetailReader.inspectAuthorizedRoot(root, adapter, 7, pkg, bounds).fields?.fields?.get("订单号"))
    }
}
