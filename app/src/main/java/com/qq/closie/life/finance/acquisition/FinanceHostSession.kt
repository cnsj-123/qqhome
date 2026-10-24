package com.qq.closie.life.finance.acquisition

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import com.qq.closie.ui.lifeos.finance.FinanceReceiptOverlay

internal data class FinanceHostWindow(val id:Int,val packageName:String,val type:Int,
    val active:Boolean,val focused:Boolean,val identity:String?)
internal data class FinanceHostSnapshot(val orientation:Int,val windows:List<FinanceHostWindow>)

/** A latched page lease. Capture eligibility and an attached editor's eligibility are different.
 * Only allowlisted transaction fields are digested; no page text or Android node is retained. */
internal class FinanceHostSession(val host:FinanceHostWindow,val orientation:Int,private val ownPackage:String) {
    @Volatile private var invalid=false
    private val restoreGeneration=com.qq.closie.data.backup.RestoreStartupGate.state.value.restoreGeneration
    val notCancelled:Boolean get()=!invalid && com.qq.closie.data.backup.RestoreStartupGate.isReady &&
        restoreGeneration==com.qq.closie.data.backup.RestoreStartupGate.state.value.restoreGeneration
    fun observe(event:AccessibilityEvent,snapshot:FinanceHostSnapshot,editing:Boolean) {
        if(invalid)return
        val eventWindow=snapshot.windows.firstOrNull {it.id==event.windowId}
        val ownSurface=event.packageName?.toString()==ownPackage &&
            eventWindow?.type!=AccessibilityWindowInfo.TYPE_APPLICATION
        val ime=eventWindow?.type==AccessibilityWindowInfo.TYPE_INPUT_METHOD
        // Never infer navigation from an event's package alone. Inspect the current windows/facts.
        if(!sameHost(snapshot) || (!ownSurface && !ime && event.windowId==host.id &&
            event.eventType==AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            host.identity!=null && snapshot.windows.firstOrNull {it.id==host.id}?.identity!=host.identity)) invalid=true
        if(!editing && !captureCurrent(snapshot))invalid=true
    }
    private fun sameHost(snapshot:FinanceHostSnapshot):Boolean {
        if(snapshot.orientation!=orientation)return false
        val live=snapshot.windows.firstOrNull {it.id==host.id && it.packageName==host.packageName &&
            it.type==AccessibilityWindowInfo.TYPE_APPLICATION} ?: return false
        if(live.identity!=host.identity)return false
        return snapshot.windows.none {it.type==AccessibilityWindowInfo.TYPE_APPLICATION &&
            (it.active || it.focused) && (it.id!=host.id || it.packageName!=host.packageName)}
    }
    fun captureCurrent(snapshot:FinanceHostSnapshot)=notCancelled && sameHost(snapshot) &&
        snapshot.windows.any {it.id==host.id && it.active && it.focused}
    fun editorCurrent(snapshot:FinanceHostSnapshot)=notCancelled && host.identity!=null && sameHost(snapshot)
    fun cancel(){invalid=true}
}

/** Every read is synchronous/bounded. Behind an editor use getWindows(), never rootInActiveWindow. */
internal class FinanceHostWindows(private val service:AccessibilityService) {
    fun snapshot(readFacts:Boolean=true):FinanceHostSnapshot {
        val list=runCatching {service.windows}.getOrDefault(emptyList())
        val windows=list.mapNotNull { window ->
            try {
                val root=window.root ?: return@mapNotNull null
                try {
                    val pkg=root.packageName?.toString() ?: return@mapNotNull null
                    val provider=ProviderRegistry.providers.firstOrNull {pkg in it.packageNames}
                    val identity=if(readFacts && window.type==AccessibilityWindowInfo.TYPE_APPLICATION && provider!=null)
                        VisibleDetailReader.inspect(root,provider.adapter).fields?.let { fields ->
                            // Includes status, amount, time, channel and reference; an order number alone is insufficient.
                            provider.adapter.parse(fields,System.currentTimeMillis()).evidence?.let { e->
                                DetailSemantics.digest("${e.referenceDigest}:${e.amountMinor}:${e.occurredAt}:${e.kind}:${e.role}")
                            }
                        } else null
                    FinanceHostWindow(window.id,pkg,window.type,window.isActive,window.isFocused,identity)
                } finally {@Suppress("DEPRECATION") root.recycle()}
            } catch(_:Exception){null}
            finally {@Suppress("DEPRECATION") window.recycle()}
        }
        return FinanceHostSnapshot(service.resources.configuration.orientation,windows)
    }
    fun bindActive():FinanceHostSession? {
        val snapshot=snapshot()
        val host=snapshot.windows.singleOrNull {it.type==AccessibilityWindowInfo.TYPE_APPLICATION &&
            it.active && it.focused && it.packageName!=service.packageName} ?: return null
        return FinanceHostSession(host,snapshot.orientation,service.packageName)
    }
}
