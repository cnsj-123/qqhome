package com.qq.closie.life.finance.acquisition

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import com.qq.closie.data.backup.RestoreStartupGate

internal data class FinanceHostWindow(val id:Int,val packageName:String,val type:Int,
    val active:Boolean,val focused:Boolean,val identity:String?,val displayId:Int=0)
internal data class FinanceHostSnapshot(val orientation:Int,val windows:List<FinanceHostWindow>)

/** One host lease per user click or recognized detail. The editor retains frozen evidence. */
internal class FinanceHostSession(val host:FinanceHostWindow,val orientation:Int) {
    @Volatile private var invalid=false
    private val restoreGeneration=RestoreStartupGate.state.value.restoreGeneration
    val notCancelled:Boolean get()=!invalid && RestoreStartupGate.isReady &&
        restoreGeneration==RestoreStartupGate.state.value.restoreGeneration

    fun observe(event:AccessibilityEvent,snapshot:FinanceHostSnapshot,editing:Boolean) {
        if(invalid)return
        // Host navigation can reuse a window. IME and paper events have their own window IDs.
        val hostNavigated=event.windowId==host.id &&
            event.eventType==AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.packageName?.toString()==host.packageName
        if(hostNavigated || !(if(editing) editorCurrent(snapshot) else captureCurrent(snapshot))) cancel()
    }
    private fun sameHost(snapshot:FinanceHostSnapshot):Boolean {
        if(snapshot.orientation!=orientation)return false
        if(snapshot.windows.none {it.id==host.id && it.packageName==host.packageName &&
                it.type==AccessibilityWindowInfo.TYPE_APPLICATION && it.displayId==host.displayId})return false
        return snapshot.windows.none {it.type==AccessibilityWindowInfo.TYPE_APPLICATION &&
            (it.active || it.focused) && (it.id!=host.id || it.packageName!=host.packageName)}
    }
    fun captureCurrent(snapshot:FinanceHostSnapshot)=notCancelled && sameHost(snapshot) &&
        snapshot.windows.any {it.id==host.id && it.active && it.focused && it.identity==host.identity}
    fun editorCurrent(snapshot:FinanceHostSnapshot)=notCancelled && sameHost(snapshot)
    fun cancel(){invalid=true}
}

internal fun com.qq.closie.life.finance.NormalizedTransactionEvidence.hostIdentity() =
    DetailSemantics.digest("${referenceDigest}:${amountMinor}:${occurredAt}:${kind}:${role}")

/** Metadata never traverses transaction nodes. Facts require an exact target and a live grant. */
internal class FinanceHostWindows(private val service:AccessibilityService,
    private val inspect:(android.view.accessibility.AccessibilityNodeInfo,TransactionDetailAdapter)->DetailReadInspection=VisibleDetailReader::inspect) {
    private val packages=java.util.concurrent.ConcurrentHashMap<Int,String>()
    fun record(event:AccessibilityEvent) {
        if(event.windowId>=0)event.packageName?.toString()?.takeIf {it.isNotBlank()}?.let {packages[event.windowId]=it}
    }
    private fun root(window:AccessibilityWindowInfo)=if(android.os.Build.VERSION.SDK_INT>=33)window.getRoot(0) else window.root
    fun snapshot(target:FinanceHostWindow?=null,canRead:()->Boolean={false}):FinanceHostSnapshot {
        val list=runCatching {service.windows}.getOrDefault(emptyList())
        val windows=list.mapNotNull { window ->
            try {
                // No root fetch or implicit descendant prefetch for metadata or unrelated windows.
                FinanceHostWindow(window.id,packages[window.id].orEmpty(),window.type,window.isActive,window.isFocused,null,
                    if(android.os.Build.VERSION.SDK_INT>=30)window.displayId else 0)
            } catch(_:Exception){null}
            finally {@Suppress("DEPRECATION") window.recycle()}
        }
        val metadata=FinanceHostSnapshot(service.resources.configuration.orientation,windows)
        packages.keys.retainAll(windows.map {it.id}.toSet())
        if(target==null || !canRead())return metadata
        val live=windows.singleOrNull {it.id==target.id && it.type==AccessibilityWindowInfo.TYPE_APPLICATION}
            ?: return metadata
        if(windows.any {it.type==AccessibilityWindowInfo.TYPE_APPLICATION && (it.active || it.focused) && it.id!=target.id})return metadata
        if(!live.active || !live.focused)return metadata
        val provider=ProviderRegistry.providers.firstOrNull {target.packageName in it.packageNames} ?: return metadata
        // Reacquire only the selected root; no adapter/text access occurs for any other window.
        val roots=runCatching {service.windows}.getOrDefault(emptyList())
        var identity:String?=null
        var actualPackage=live.packageName
        for(window in roots)try {
            if(window.id!=target.id || window.type!=AccessibilityWindowInfo.TYPE_APPLICATION || !canRead())continue
            val root=root(window) ?: continue
            try {
                actualPackage=root.packageName?.toString().orEmpty();packages[window.id]=actualPackage
                if(actualPackage!=target.packageName || !root.isVisibleToUser || !canRead())continue
                identity=inspect(root,provider.adapter).fields?.let {
                    if(canRead())provider.adapter.parse(it,System.currentTimeMillis()).evidence?.hostIdentity() else null
                }
            } finally {@Suppress("DEPRECATION") root.recycle()}
        } catch(_:Exception){identity=null} finally {@Suppress("DEPRECATION") window.recycle()}
        return metadata.copy(windows=windows.map {if(it.id==target.id)it.copy(packageName=actualPackage,identity=identity) else it})
    }
    /** Called by an explicit click; automatic discovery must supply its source/target grant. */
    fun bindActive():FinanceHostSession? {
        val metadata=snapshot()
        val selected=metadata.windows.singleOrNull {it.type==AccessibilityWindowInfo.TYPE_APPLICATION && it.active && it.focused} ?: return null
        var pkg:String?=null
        val windows=runCatching {service.windows}.getOrDefault(emptyList())
        for(window in windows)try {
            if(window.id!=selected.id || !window.isActive || !window.isFocused)continue
            val node=root(window) ?: continue
            try {pkg=node.packageName?.toString()} finally {@Suppress("DEPRECATION") node.recycle()}
        } catch(_:Exception){pkg=null} finally {@Suppress("DEPRECATION") window.recycle()}
        val host=selected.copy(packageName=pkg ?: return null)
        packages[host.id]=host.packageName
        if(host.packageName==service.packageName)return null
        return FinanceHostSession(host,metadata.orientation)
    }
    fun bindVisible(target:VisibleDetailScheduler.Target,evidence:com.qq.closie.life.finance.NormalizedTransactionEvidence,
        permitted:()->Boolean):FinanceHostSession? {
        if(!permitted())return null
        val metadata=snapshot()
        val selected=metadata.windows.singleOrNull {it.id==target.windowId && it.type==AccessibilityWindowInfo.TYPE_APPLICATION && it.active && it.focused} ?: return null
        val facts=snapshot(selected.copy(packageName=target.packageName),permitted)
        val host=facts.windows.singleOrNull {it.id==target.windowId && it.packageName==target.packageName && it.identity==evidence.hostIdentity()} ?: return null
        return FinanceHostSession(host,metadata.orientation)
    }
    fun current(session:FinanceHostSession,permitted:()->Boolean,editing:Boolean=false):FinanceHostSnapshot {
        if(!session.notCancelled || !permitted())return snapshot().copy(windows=emptyList())
        // Explicit capture and mounted review never traverse host content.
        return if(editing || session.host.identity==null)snapshot()
            else snapshot(session.host,{session.notCancelled && permitted()})
    }
    }
