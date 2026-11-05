package com.qq.closie.life.finance.acquisition

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VisibleDetailRootTest {
    private val bounds = Rect(0, 0, 400, 800)
    private val pkg = FinanceHostFixtures.ALIPAY
    private val adapter = ProviderRegistry.providers.single { pkg in it.packageNames }.adapter

    private fun window(id: Int) = AccessibilityWindowInfo.obtain().also { shadowOf(it).setId(id) }
    private fun node(text: String? = null, vararg children: AccessibilityNodeInfo) = AccessibilityNodeInfo.obtain().apply {
        this.text = text; packageName = pkg; isVisibleToUser = true; setBoundsInScreen(bounds)
        shadowOf(this).setAccessibilityWindowInfo(window(7))
        children.forEach { shadowOf(this).addChild(it) }
    }
    private data class Page(val root: AccessibilityNodeInfo, val orderRow: AccessibilityNodeInfo, val orderValue: AccessibilityNodeInfo)
    private fun page(): Page {
        // Configure original handles before getChild() creates copies. Robolectric's windowId
        // comes from its explicit window shadow, rather than AccessibilityNodeInfo.mWindowId.
        val value = node("ORDER-123456")
        val order = node(null, node("订单号"), value)
        val root = node(null, node("账单详情"), node(null, node("合成商户"), node("-¥12.34"), node("交易成功")),
            node(null, node("支付时间"), node("2026-01-02 10:20:30")), node(null, node("付款方式"), node("花呗")), order)
        root.isVisibleToUser = false
        root.text = "订单号：HIDDEN-ROOT-TEXT"
        return Page(root, order, value)
    }

    @Test fun structuralInvisibleRootCanExposeItsTrulyVisibleAuthorizedChildren() {
        val root = page().root
        assertNull(VisibleDetailReader.inspect(root, adapter).fields)
        val fields = requireNotNull(VisibleDetailReader.inspectAuthorizedRoot(root, adapter, 7, pkg, bounds).fields)
        assertEquals("ORDER-123456", fields.fields["订单号"])
        assertNotNull(adapter.parse(fields, System.currentTimeMillis()).evidence)
    }

    @Test fun invisibleRootCannotAuthorizeAnotherWindowPackageOrEmptyArea() {
        val root = page().root
        assertNull(VisibleDetailReader.inspectAuthorizedRoot(root, adapter, 8, pkg, bounds).fields)
        assertNull(VisibleDetailReader.inspectAuthorizedRoot(root, adapter, 7, FinanceHostFixtures.WECHAT, bounds).fields)
        assertNull(VisibleDetailReader.inspectAuthorizedRoot(root, adapter, 7, pkg, Rect()).fields)
    }

    @Test fun invisibleSubtreeAndForeignOrOffWindowDescendantsStayUnreadable() {
        val page = page(); val root = page.root
        // The order row remains visible in the tree, but its value is not authorized.
        val orderRow = page.orderRow
        val value = page.orderValue
        value.isVisibleToUser = false
        assertNull(VisibleDetailReader.inspectAuthorizedRoot(root, adapter, 7, pkg, bounds).fields?.fields?.get("订单号"))
        value.isVisibleToUser = true
        shadowOf(value).setAccessibilityWindowInfo(window(8))
        assertNull(VisibleDetailReader.inspectAuthorizedRoot(root, adapter, 7, pkg, bounds).fields?.fields?.get("订单号"))
        shadowOf(value).setAccessibilityWindowInfo(window(7))
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
