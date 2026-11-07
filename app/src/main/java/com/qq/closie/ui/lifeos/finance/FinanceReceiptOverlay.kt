package com.qq.closie.ui.lifeos.finance

import android.app.KeyguardManager
import android.content.*
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.view.*
import android.view.inputmethod.InputMethodManager
import android.widget.*
import com.qq.closie.ClosieApplication
import com.qq.closie.data.appearance.ThemePalette
import com.qq.closie.life.data.LifeContainer
import com.qq.closie.life.finance.*
import com.qq.closie.life.finance.acquisition.FinanceScreenshotPreference
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean

/** One editable surface at a time. New proposals remain in the durable inbox and notifications. */
internal class FinanceReceiptOverlay(private val context:Context,private val scope:CoroutineScope,
    private val containerProvider:()->LifeContainer={LifeContainer.getInstance(context)},
    private val editOwner:CoroutineScope?=null,private val onClosed:()->Unit={},
    private val embeddedHost:FrameLayout?=null) {
    companion object {
        private var owner: FinanceReceiptOverlay? = null
        private val legacyCapture=Any()
        private var captureOwner:Any?=null
        private val captureBusy get()=captureOwner!=null
        private val occupancy=MutableStateFlow(false)
        val occupancyChanges:StateFlow<Boolean> = occupancy.asStateFlow()
        val occupied: Boolean get() = owner != null || captureBusy
        private fun publish(){occupancy.value=occupied}
        fun beginCapture(requestOwner:Any=legacyCapture): Boolean {
            if (occupied) return false
            captureOwner = requestOwner
            publish()
            return true
        }
        fun endCapture(requestOwner:Any?=null) { if(requestOwner==null || captureOwner===requestOwner){captureOwner=null;publish()} }
    }
    private val windows=context.getSystemService(WindowManager::class.java)
    private val editScope get()=editOwner ?: (context.applicationContext as ClosieApplication).financeReviewEditScope
    private var window:View?=null
    private var windowLease: AtomicBoolean? = null
    private var mountedEditor:FinanceNativeReceiptEditor?=null
    val isShowing:Boolean get()=window!=null && owner===this
    private var observer:Job?=null
    private var monitor:Job?=null
    private var autosave:Job?=null
    private var flushLater:(()->Unit)?=null
    private var screenReceiver:BroadcastReceiver?=null
    private var configuration:ComponentCallbacks?=null
    private var backAction:(()->Unit)?=null
    fun back(){backAction?.invoke() ?: close()}
    private fun canAttach()=embeddedHost!=null || Settings.canDrawOverlays(context)

    suspend fun show(id:String, userInitiated:Boolean=false, expectedEpoch:Long?=null, canContinue:()->Boolean={true}, canPresent:()->Boolean={true}):Boolean {
        val restoreGeneration = com.qq.closie.data.backup.RestoreStartupGate.state.value.restoreGeneration
        val screenshotGeneration=FinanceScreenshotPreference.generation
        var attachedLease:AtomicBoolean?=null
        if(!canPresent() || !com.qq.closie.data.backup.RestoreStartupGate.isReady || !canAttach() || context.getSystemService(KeyguardManager::class.java).isKeyguardLocked) return false
        return try {
            val container=containerProvider()
            val automation=container.financeAutomationRepository.observe().first()
            val proposal=automation.proposals.singleOrNull {it.id==id && it.status in setOf(FinanceProposalStatus.ACTIVE,FinanceProposalStatus.SNOOZED)} ?: return false
            val snapshot=container.financeRepository.snapshot()
            val epoch=snapshot.v16?.controlRows?.singleOrNull()?.restoreEpoch ?: 0L
            if (expectedEpoch != null && epoch != expectedEpoch) return false
            val saved=snapshot.v16?.reviewSessionRows.orEmpty().singleOrNull {it.proposalId==id && it.voidedAt==null && it.state!=FinanceReviewState.COMPLETED}
            val safe=FinanceProposalPrivacy.sanitize(proposal,FinanceProposalPrivacy.userDescriptions(automation.rules.flatMap {it.actions}))
            val permittedCurrency = FinanceEvidenceOrigin.permittedCurrency(proposal, snapshot.v16)
            val extraction=FinanceEvidenceOrigin.extraction(proposal,snapshot.v16)
            val initial=saved?.toEntryDraft(snapshot) ?: FinanceEntryDraft(FinanceEditorKind.valueOf(proposal.kind.name),
                (extraction?.amountMinor ?: proposal.amountMinor)?.let { value -> permittedCurrency?.let { FinanceMoney.input(value, it) } }.orEmpty(),
                proposal.accountId.orEmpty(),proposal.targetAccountId.orEmpty(),
                FinanceTime.input(proposal.occurredAt),safe.description,proposal.category,
                automation.tags.filter {it.proposalId==id}.joinToString("，"){it.name},proposal.occurredAt,proposal.subcategory,
                FinanceRelationshipDraft.fromProposal(proposal),FinanceCategoryTree(snapshot.categories).find(proposal.category,proposal.subcategory)?.id)
            val tokens=ThemePalette.resolve((context.applicationContext as ClosieApplication).appearanceRepository.read())
            withContext(Dispatchers.Main.immediate) {
                val settings=FinanceAutomationSettings(context).read()
                fun permitted(prefs: FinanceDiscoverySettings): Boolean =
                    if (userInitiated && proposal.source == FinanceSource.SCREENSHOT)
                        FinanceScreenshotPreference.generation==screenshotGeneration && FinanceScreenshotPreference.enabled(context)
                    else prefs.deliveryMode == FinanceReviewDeliveryMode.IMMEDIATE && prefs.enhancedReceiptOverlay && prefs.permits(proposal)
                if((captureBusy && !userInitiated) || !canPresent() || window!=null || (owner != null && owner !== this@FinanceReceiptOverlay) || !permitted(settings) || FinanceReviewRequests.foreground || !canAttach()) return@withContext false
                val lease = AtomicBoolean(false)
                fun closeCurrent(persist:Boolean=true,advance:Boolean=false) {
                    if(windowLease===lease)close(persist,advance)
                }
                fun mayConfirm(): Boolean =
                    lease.get() && canContinue() && canAttach() &&
                        com.qq.closie.data.backup.RestoreStartupGate.isReady &&
                        com.qq.closie.data.backup.RestoreStartupGate.state.value.restoreGeneration == restoreGeneration &&
                        !context.getSystemService(KeyguardManager::class.java).isKeyguardLocked &&
                        permitted(FinanceAutomationSettings(context).read())
                val density=context.resources.displayMetrics.density
                fun dp(n:Int)=(n*density).toInt()
                val mutex=Mutex();var revision=saved?.revision
                lateinit var editor:FinanceNativeReceiptEditor
                suspend fun persist(draft:FinanceEntryDraft,later:Boolean=false,closingFlush:Boolean=false)=mutex.withLock {
                    check(closingFlush || mayConfirm()) {"原复核窗口已关闭"}
                    withContext(NonCancellable) {
                    container.financeRepository.reviews.save(FinanceCommandToken(java.util.UUID.randomUUID().toString(),epoch),
                        draft.reviewInput(proposal,epoch),revision,draft.reviewExpected(id),later)
                    revision=Math.addExact(revision ?: 0L,1L)
                }}
                fun scheduleSave() {
                    if(!mayConfirm() || !editor.state.reviewing)return
                    autosave?.cancel();val draft=editor.draft()
                    autosave=editScope.launch {
                        delay(250)
                        try {persist(draft)} catch(e:CancellationException){throw e}
                        catch(_:Exception){editor.showError("草稿状态已改变，请接力到应用内核对")}
                    }
                }
                fun later(openApp:Boolean=false,continueQueue:Boolean=true) {
                    val draft=editor.startSaving() ?: return
                    autosave?.cancel()
                    editScope.launch {
                        try {
                            // Handoff saves EDITING without changing the candidate revision underneath its own window.
                            persist(draft,later=!openApp)
                            if(openApp && mayConfirm())context.startActivity(FinanceReviewNotifications.openIntent(context,id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            closeCurrent(false,advance=!openApp && continueQueue)
                        } catch(e:CancellationException){throw e}
                        catch(_:Exception){editor.finishSavingWithError("保存未完成；已保存内容仍保留，请在应用内复核")}
                    }
                }
                fun confirmReceipt() {
                    if(!mayConfirm()){closeCurrent();return}
                    val draft=editor.startSaving() ?: return
                    autosave?.cancel()
                    editScope.launch {
                        try {
                            persist(draft)
                            mutex.withLock {
                                container.financeRepository.reviews.confirm(FinanceCommandToken(java.util.UUID.randomUUID().toString(),epoch),id,
                                    requireNotNull(revision),FinanceTime.resolve(draft.occurredAt,draft.preservedOccurredAt),stillCurrent=::mayConfirm)
                            }
                            FinanceReviewNotifications.cancel(context,id);closeCurrent(false,advance=true)
                        } catch(e:CancellationException){throw e}
                        catch(e:Exception){editor.finishSavingWithError(e.message ?: "确认未完成，请接力到应用内核对")}
                    }
                }
                editor=FinanceNativeReceiptEditor(context,initial,snapshot,permittedCurrency,proposal.discoverySourceLabel(),tokens,
                    changed=::scheduleSave,confirm=::confirmReceipt,later={later()},handoff={later(true)},canInteract=::mayConfirm)
                extraction?.issue?.takeIf(String::isNotBlank)?.let(editor::showError)
                backAction={if(!editor.back())later()}
                val frame=editor.view.apply {
                    if(embeddedHost!=null)setOnClickListener {} // Blank paper never reaches the backdrop.
                    background=FinanceReceiptPaper(if(tokens.isDark) Color.parseColor(tokens.card) else Color.parseColor("#FFFCF8"),density)
                    elevation=dp(8).toFloat()
                }
                val paperWidth=(context.resources.displayMetrics.widthPixels*.87f).toInt()
                val paperHeight=(context.resources.displayMetrics.heightPixels*.56f).toInt()
                val params=WindowManager.LayoutParams(paperWidth,paperHeight,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_SECURE or
                        (if(embeddedHost==null)WindowManager.LayoutParams.FLAG_DIM_BEHIND else 0),PixelFormat.TRANSLUCENT).apply {
                    gravity=Gravity.BOTTOM
                    dimAmount=.28f // Platform dim is neutral. Preserve Figma's approximate visible contrast without a second overlay window.
                    softInputMode=WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
                }
                val attached = attachFinanceReceipt(restoreGeneration,canPresent,authorized={
                    (!captureBusy || userInitiated) && permitted(FinanceAutomationSettings(context).read()) &&
                        canAttach() && !context.getSystemService(KeyguardManager::class.java).isKeyguardLocked
                }) {
                    if(embeddedHost!=null)embeddedHost.addView(frame,FrameLayout.LayoutParams(params.width,params.height,Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
                    else windows.addView(frame,params)
                }
                if (!attached) return@withContext false
                windowLease=lease;attachedLease=lease;lease.set(true)
                mountedEditor=editor;editor.refreshInteraction()
                window=frame;owner=this@FinanceReceiptOverlay;captureOwner=null;publish();editor.scroll.requestFocus()
                if(android.animation.ValueAnimator.areAnimatorsEnabled()) {
                    frame.translationY=dp(20).toFloat();frame.scaleX=.98f;frame.scaleY=.98f;frame.alpha=0f
                    frame.animate().translationY(0f).scaleX(1f).scaleY(1f).alpha(1f).setDuration(260).start()
                }
                androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(frame) { _, insets ->
                    if(windowLease!==lease)return@setOnApplyWindowInsetsListener insets
                    val bars=insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
                    val ime=insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime())
                    editor.updateImeVisibility(insets.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()))
                    val height=context.resources.displayMetrics.heightPixels
                    val available=(height-maxOf(bars.bottom,ime.bottom)-bars.top).coerceAtLeast(dp(120))
                    val targetHeight=minOf((height*.56f).toInt(),available)
                    if(params.height!=targetHeight || params.y!=bars.bottom) {
                        params.height=targetHeight;params.y=bars.bottom
                        runCatching {
                            if(embeddedHost!=null)frame.layoutParams=FrameLayout.LayoutParams(params.width,params.height,Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {bottomMargin=params.y}
                            else windows.updateViewLayout(frame,params)
                        }.onFailure {closeCurrent()}
                    }
                    insets
                }
                androidx.core.view.ViewCompat.requestApplyInsets(frame)
                flushLater={
                    // A lost host may discard a pending field, but never autosave that un-applied edit.
                    val draft=editor.draft()
                    editScope.launch {try {persist(draft,true,closingFlush=true)} catch(e:CancellationException){throw e} catch(_:Exception) {}}
                }
                scheduleSave()
                configuration=object:ComponentCallbacks {
                    override fun onLowMemory(){}
                    override fun onConfigurationChanged(newConfig:android.content.res.Configuration) {
                        closeCurrent() // Rotation invalidates only this mounted editor.
                    }
                }.also(context::registerComponentCallbacks)
                screenReceiver=object:BroadcastReceiver(){override fun onReceive(c:Context?,intent:Intent?){closeCurrent()}}.also {receiver->
                    if(Build.VERSION.SDK_INT>=33)context.registerReceiver(receiver,IntentFilter(Intent.ACTION_SCREEN_OFF),Context.RECEIVER_NOT_EXPORTED)
                    else context.registerReceiver(receiver,IntentFilter(Intent.ACTION_SCREEN_OFF))
                }
                observer=scope.launch(Dispatchers.Main.immediate) {
                    val permissions=combine(FinanceAutomationSettings(context).observe(),FinanceScreenshotPreference.observe(context)) {prefs,_->prefs}
                    combine(container.financeAutomationRepository.observe(),FinanceReviewRequests.visible,permissions,container.financeRepository.observeSnapshot(),
                        com.qq.closie.data.backup.RestoreStartupGate.state.map {it.status to it.restoreGeneration}.distinctUntilChanged()) {data,foreground,prefs,finance,gate->
                        (gate.first!=com.qq.closie.data.backup.RestoreStartupGate.Status.READY || gate.second!=restoreGeneration) || foreground || !permitted(prefs) ||
                            (finance.v16?.controlRows?.singleOrNull()?.restoreEpoch ?: 0L)!=epoch || data.proposals.none {it.id==id && it.revision==proposal.revision && it.status in setOf(FinanceProposalStatus.ACTIVE,FinanceProposalStatus.SNOOZED)}
                    }.catch {emit(true)}.first {it};closeCurrent()
                }
                monitor=scope.launch(Dispatchers.Main.immediate) {while(isActive){delay(500)
                    if(!mayConfirm()){closeCurrent();break}
                }}
                true
            }
        } catch(e:CancellationException){throw e}
        catch(_:Exception){withContext(Dispatchers.Main.immediate){if(attachedLease!=null && windowLease===attachedLease)close()};false}
    }
    /** Never discards a candidate. Application-owned edit work survives listener teardown. */
    fun close(persist:Boolean=true, advance:Boolean=false) {
        windowLease?.set(false)
        windowLease = null
        mountedEditor?.invalidate();mountedEditor=null
        val wasShowing=isShowing
        if (owner === this) {owner = null;publish()}
        val flush=flushLater;flushLater=null
        observer?.cancel();observer=null;monitor?.cancel();monitor=null;autosave?.cancel();autosave=null
        screenReceiver?.let {runCatching {context.unregisterReceiver(it)}};screenReceiver=null
        configuration?.let {context.unregisterComponentCallbacks(it)};configuration=null
        window?.let {view->view.animate().cancel();context.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(view.windowToken,0);runCatching {
            if(embeddedHost!=null)embeddedHost.removeView(view) else windows.removeViewImmediate(view)
        }};window=null;backAction=null
        if(wasShowing)onClosed()
        com.qq.closie.life.finance.acquisition.FinanceProjectionBubbleService.ensure(context)
        if(persist)flush?.invoke()
        if(advance && !occupied) editScope.launch { FinanceReceiptQueue.pending.advance() }
    }
}
