package com.qq.closie.life.finance.acquisition

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import com.qq.closie.ClosieApplication
import com.qq.closie.ui.lifeos.finance.FinanceReceiptOverlay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.withTimeout

/** An explicit click transfers one exact host lease through consent, one frame and its editor. */
internal class FinanceProjectionHost(private val service:AccessibilityService,private val windows:FinanceHostWindows,
    private val session:FinanceHostSession,private val overlayFactory:((()->Unit)->FinanceReceiptOverlay)?=null) {
    val id:String=java.util.UUID.randomUUID().toString()
    private enum class Phase { CONSENT, CAPTURING, EDITING, CLOSED }
    private var phase=Phase.CONSENT
    private val preferenceGeneration=FinanceScreenshotPreference.generation
    private val events=MutableStateFlow(0L)
    private var overlay:FinanceReceiptOverlay?=null
    val live:Boolean get()=phase!=Phase.CLOSED && session.notCancelled &&
        preferenceGeneration==FinanceScreenshotPreference.generation && FinanceScreenshotPreference.enabled(service)
    private fun snapshot(editing:Boolean=false)=windows.current(session,{live},editing)
    fun captureCurrent()=live && session.captureCurrent(snapshot())
    fun editorCurrent()=live && session.editorCurrent(snapshot(true))
    fun observe(event:AccessibilityEvent) {
        windows.record(event)
        if(!live){close();return}
        if(phase==Phase.CONSENT) {
            // Consent may cover the original host. Do not read its facts until it returns.
            val metadata=windows.snapshot()
            val allowed=setOf(service.packageName,session.host.packageName,"com.android.systemui",
                "com.android.permissioncontroller","com.google.android.permissioncontroller")
            if(metadata.orientation!=session.orientation || metadata.windows.any {
                it.type==AccessibilityWindowInfo.TYPE_APPLICATION && (it.active || it.focused) && it.packageName.isNotBlank() && it.packageName !in allowed
            })close()
        } else {
            val state=snapshot(phase==Phase.EDITING)
            session.observe(event,state,phase==Phase.EDITING)
            if(!(if(phase==Phase.EDITING)session.editorCurrent(state) else session.captureCurrent(state)))close()
        }
        events.value++
    }
    suspend fun awaitReturn() {
        withTimeout(3000) {events.onStart {emit(-1)}.first {check(live);captureCurrent()}}
        phase=Phase.CAPTURING
        check(captureCurrent())
    }
    suspend fun present(id:String,epoch:Long,current:()->Boolean):Boolean {
        if(!current() || !captureCurrent())return false
        val app=service.application as ClosieApplication
        val paper=overlayFactory?.invoke {close()} ?: FinanceReceiptOverlay(app,app.financeReviewEditScope,onClosed={close()})
        overlay=paper
        val shown=paper.show(id,true,epoch,canContinue={current() && editorCurrent()},canPresent={current() && captureCurrent()})
        if(shown)phase=Phase.EDITING else close()
        return shown
    }
    fun close() {
        if(phase==Phase.CLOSED)return
        phase=Phase.CLOSED;session.cancel();val paper=overlay;overlay=null;paper?.close()
        if(FinanceProjectionHosts.current===this)FinanceProjectionHosts.current=null
        events.value++
    }
}
internal object FinanceProjectionHosts {
    var current:FinanceProjectionHost?=null
    fun offer(host:FinanceProjectionHost){current?.close();current=host}
    fun take(id:String?):FinanceProjectionHost?=current?.takeIf {it.id==id && it.live}
}
