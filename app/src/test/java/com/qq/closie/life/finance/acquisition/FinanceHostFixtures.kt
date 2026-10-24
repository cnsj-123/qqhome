package com.qq.closie.life.finance.acquisition

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.*
import org.robolectric.Shadows.shadowOf

internal class FinanceFixtureService:AccessibilityService() {
    override fun onAccessibilityEvent(event:AccessibilityEvent?){}
    override fun onInterrupt(){}
}
internal object FinanceHostFixtures {
    const val ALIPAY="com.eg.android.AlipayGphone"
    const val WECHAT="com.tencent.mm"
    fun node(text:String?=null,vararg children:AccessibilityNodeInfo):AccessibilityNodeInfo=
        AccessibilityNodeInfo.obtain().apply {this.text=text;isVisibleToUser=true;children.forEach {shadowOf(this).addChild(it)}}
    fun root(pkg:String=ALIPAY,order:String="ORDER-123456"):AccessibilityNodeInfo {
        val body=if(pkg==WECHAT)node(null,node("账单详情"),node(null,node("合成商户"),node("-12.34")),
            node(null,node("当前状态"),node("支付成功")),node(null,node("支付时间"),node("2026年1月2日 10:20:30")),
            node(null,node("支付方式"),node("零钱")),node(null,node("交易单号"),node(order)))
        else node(null,node("账单详情"),node(null,node("合成商户"),node("-¥12.34"),node("交易成功")),
            node(null,node("支付时间"),node("2026-01-02 10:20:30")),node(null,node("付款方式"),node("花呗")),
            node(null,node("订单号"),node(order)))
        return body.apply {packageName=pkg}
    }
    fun window(id:Int,pkg:String,focused:Boolean=true,order:String="ORDER-123456",type:Int=AccessibilityWindowInfo.TYPE_APPLICATION)=
        AccessibilityWindowInfo.obtain().also {shadowOf(it).apply {setId(id);setType(type);setRoot(root(pkg,order));setActive(focused);setFocused(focused)}}
    fun windows(service:AccessibilityService,vararg windows:AccessibilityWindowInfo){shadowOf(service).setWindows(windows.toList())}
    fun event(id:Int=1,pkg:String=ALIPAY)=AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
        .apply {packageName=pkg;shadowOf(this).setWindowId(id)}
    fun evidence(pkg:String=ALIPAY,order:String="ORDER-123456"):com.qq.closie.life.finance.NormalizedTransactionEvidence {
        val provider=ProviderRegistry.providers.single {pkg in it.packageNames}
        val fields=requireNotNull(VisibleDetailReader.inspect(root(pkg,order),provider.adapter).fields)
        return requireNotNull(provider.adapter.parse(fields,System.currentTimeMillis()).evidence)
    }
}
