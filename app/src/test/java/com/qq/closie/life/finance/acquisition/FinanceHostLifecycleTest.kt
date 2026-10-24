package com.qq.closie.life.finance.acquisition

import android.accessibilityservice.AccessibilityService
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.qq.closie.data.backup.RestoreStartupGate
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Actual Android events/windows/nodes -> shipping reader -> page lease. No manually toggled valid Boolean. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class FinanceHostLifecycleTest {
    class HostService:AccessibilityService() {
        override fun onAccessibilityEvent(event:AccessibilityEvent?){}
        override fun onInterrupt(){}
    }
    private val controller=Robolectric.buildService(HostService::class.java)
    private lateinit var service:HostService
    private lateinit var reader:FinanceHostWindows
    private var exposed=true
    private var granted:FinanceHostSession?=null
    private fun bind()=reader.bindActive().also {granted=it}
    private fun facts()=reader.snapshot(granted?.host,{true})
    @Before fun setup(){RestoreStartupGate.resetForTesting();RestoreStartupGate.markReady();service=controller.create().get();reader=FinanceHostWindows(service)}
    @After fun cleanup(){controller.destroy();RestoreStartupGate.resetForTesting()}
    private fun node(text:String?=null,vararg children:AccessibilityNodeInfo):AccessibilityNodeInfo =
        AccessibilityNodeInfo.obtain().apply {this.text=text;isVisibleToUser=exposed;children.forEach {shadowOf(this).addChild(it)}}
    private fun host(order:String="ORDER-123456",focused:Boolean=true,extra:String="普通刷新"):AccessibilityWindowInfo {
        val root=node(null,node("账单详情"),node(null,node("滴滴出行"),node("-¥12.34"),node("交易成功")),
            node(null,node("支付时间"),node("2026-01-02 10:20:30")),node(null,node("付款方式"),node("花呗")),
            node(null,node("订单号"),node(order)),node(extra)).apply {packageName="com.eg.android.AlipayGphone"}
        return window(1,"com.eg.android.AlipayGphone",AccessibilityWindowInfo.TYPE_APPLICATION,focused,root)
    }
    private fun window(id:Int,pkg:String,type:Int,focused:Boolean,root:AccessibilityNodeInfo=node()):AccessibilityWindowInfo {
        root.packageName=pkg
        return AccessibilityWindowInfo.obtain().also {shadowOf(it).apply {setId(id);setType(type);setRoot(root);setActive(focused);setFocused(focused)}}
    }
    private fun windows(vararg values:AccessibilityWindowInfo){shadowOf(service).setWindows(values.toList())}
    private fun event(type:Int,id:Int,pkg:String)=AccessibilityEvent.obtain(type).apply {packageName=pkg;shadowOf(this).setWindowId(id)}
    @Test fun normalContentRefreshKeepsClickBoundCaptureButSamePackageNewOrderLatchesInvalidation() {
        windows(host());val lease=requireNotNull(bind());assertNotNull(lease.host.identity)
        windows(host(extra="同页刷新后文字"))
        lease.observe(event(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,1,lease.host.packageName),facts(),false)
        assertTrue(lease.captureCurrent(facts()))
        windows(host(order="ORDER-999999"))
        lease.observe(event(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,1,lease.host.packageName),facts(),false)
        assertFalse(lease.captureCurrent(facts()))
        windows(host());assertFalse(lease.captureCurrent(facts()))
    }
    @Test fun receiptFocusImeAndSubpanelsKeepEditorForMoreThanTwoSecondsThenOtherApplicationInvalidates() {
        windows(host());val lease=requireNotNull(bind())
        windows(host(focused=false),window(2,service.packageName,AccessibilityWindowInfo.TYPE_SYSTEM,true))
        lease.observe(event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,2,service.packageName),facts(),true)
        assertFalse(lease.captureCurrent(facts()));assertTrue(lease.editorCurrent(facts()))
        windows(host(focused=false),window(2,service.packageName,AccessibilityWindowInfo.TYPE_SYSTEM,true),
            window(3,"synthetic.ime",AccessibilityWindowInfo.TYPE_INPUT_METHOD,true))
        lease.observe(event(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,3,"synthetic.ime"),facts(),true)
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(2500))
        assertTrue(lease.editorCurrent(facts()))
        lease.observe(event(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,2,service.packageName),facts(),true)
        assertTrue(lease.editorCurrent(facts()))
        windows(window(8,"other.app",AccessibilityWindowInfo.TYPE_APPLICATION,true))
        lease.observe(event(AccessibilityEvent.TYPE_WINDOWS_CHANGED,8,"other.app"),facts(),true)
        assertFalse(lease.editorCurrent(facts()))
    }
    @Test fun ownApplicationNavigationIsNotConfusedWithOwnReceiptAndUnknownPagesCannotOwnAnEditor() {
        windows(host());val lease=requireNotNull(bind())
        windows(host(focused=false),window(2,service.packageName,AccessibilityWindowInfo.TYPE_APPLICATION,true))
        lease.observe(event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,2,service.packageName),facts(),true)
        assertFalse(lease.editorCurrent(facts()))
        windows(window(4,"unknown.host",AccessibilityWindowInfo.TYPE_APPLICATION,true))
        val opaque=requireNotNull(bind());assertTrue(opaque.captureCurrent(facts()))
        assertFalse(opaque.editorCurrent(facts()))
    }
    @Test fun onlyBoundEditorMayValidateFactsOccludedByItsOwnPaperAndStillDetectsAnotherOrder() {
        windows(host());val lease=requireNotNull(bind())
        exposed=false
        windows(host(focused=false),window(2,service.packageName,AccessibilityWindowInfo.TYPE_SYSTEM,true))
        assertFalse(lease.editorCurrent(facts())) // the visible-only capture path must stay strict
        val editing=reader.current(lease,{true},true)
        lease.observe(event(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,2,service.packageName),editing,true)
        assertTrue(lease.editorCurrent(editing))
        windows(host(order="ORDER-999999",focused=false),window(2,service.packageName,AccessibilityWindowInfo.TYPE_SYSTEM,true))
        val changed=reader.current(lease,{true},true)
        lease.observe(event(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,1,lease.host.packageName),changed,true)
        assertFalse(lease.editorCurrent(changed))
    }
    @Test fun restoreAndRotationCannotResurrectOriginalLease() {
        windows(host());val lease=requireNotNull(bind())
        assertFalse(lease.captureCurrent(facts().copy(orientation=12345)))
        RestoreStartupGate.beginRestore();RestoreStartupGate.markReady()
        assertFalse(lease.editorCurrent(facts()))
    }
}
