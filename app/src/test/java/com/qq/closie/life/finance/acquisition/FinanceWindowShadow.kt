package com.qq.closie.life.finance.acquisition

import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import org.robolectric.shadows.ShadowAccessibilityWindowInfo

/** Robolectric 4.12.2 lacks API33 getRoot(int). Model that overload without relaxing production. */
@Implements(AccessibilityWindowInfo::class,minSdk=21)
class FinanceWindowShadow:ShadowAccessibilityWindowInfo() {
    @RealObject private lateinit var window:AccessibilityWindowInfo
    companion object {val rootReads=java.util.concurrent.ConcurrentHashMap<Int,Int>()}
    private fun count(){rootReads.merge(window.id,1){a,b->a+b}}
    @Implementation
    protected override fun getRoot():AccessibilityNodeInfo? {count();return super.getRoot()}
    @Implementation(minSdk=33)
    protected fun getRoot(prefetchingStrategy:Int):AccessibilityNodeInfo? {
        check(prefetchingStrategy==0){"Host reads must not request descendant prefetch"}
        count();return super.getRoot()
    }
}
