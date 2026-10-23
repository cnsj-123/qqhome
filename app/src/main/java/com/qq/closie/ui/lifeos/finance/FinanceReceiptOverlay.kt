package com.qq.closie.ui.lifeos.finance

import android.app.KeyguardManager
import android.content.*
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.*
import android.view.inputmethod.InputMethodManager
import android.widget.*
import com.qq.closie.ClosieApplication
import com.qq.closie.data.appearance.ThemePalette
import com.qq.closie.life.data.LifeContainer
import com.qq.closie.life.finance.*
import com.qq.closie.life.ui.theme.LifeType
import com.qq.closie.life.ui.theme.applyLifeTypography
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One editable surface at a time. New proposals remain in the durable inbox and notifications. */
internal class FinanceReceiptOverlay(private val context:Context,private val scope:CoroutineScope) {
    companion object {
        private var owner: FinanceReceiptOverlay? = null
        private var captureBusy = false
        val occupied: Boolean get() = owner != null || captureBusy
        fun beginCapture(): Boolean {
            if (captureBusy) return false
            captureBusy = true
            owner?.close()
            return true
        }
        fun endCapture() { captureBusy = false }
    }
    private val windows=context.getSystemService(WindowManager::class.java)
    private val editScope get()=(context.applicationContext as ClosieApplication).financeReviewEditScope
    private var window:View?=null
    private var observer:Job?=null
    private var monitor:Job?=null
    private var autosave:Job?=null
    private var flushLater:(()->Unit)?=null
    private var screenReceiver:BroadcastReceiver?=null
    private var configuration:ComponentCallbacks?=null

    suspend fun show(id:String, userInitiated:Boolean=false, expectedEpoch:Long?=null, canPresent:()->Boolean={true}):Boolean {
        val restoreGeneration = com.qq.closie.data.backup.RestoreStartupGate.state.value.restoreGeneration
        if(!canPresent() || !com.qq.closie.data.backup.RestoreStartupGate.isReady || !Settings.canDrawOverlays(context) || context.getSystemService(KeyguardManager::class.java).isKeyguardLocked) return false
        return try {
            val container=LifeContainer.getInstance(context)
            val automation=container.financeAutomationRepository.observe().first()
            val proposal=automation.proposals.singleOrNull {it.id==id && it.status in setOf(FinanceProposalStatus.ACTIVE,FinanceProposalStatus.SNOOZED)} ?: return false
            val snapshot=container.financeRepository.snapshot()
            val epoch=snapshot.v16?.controlRows?.singleOrNull()?.restoreEpoch ?: 0L
            if (expectedEpoch != null && epoch != expectedEpoch) return false
            val saved=snapshot.v16?.reviewSessionRows.orEmpty().singleOrNull {it.proposalId==id && it.voidedAt==null && it.state!=FinanceReviewState.COMPLETED}
            val safe=FinanceProposalPrivacy.sanitize(proposal,FinanceProposalPrivacy.userDescriptions(automation.rules.flatMap {it.actions}))
            val initial=saved?.toEntryDraft(snapshot) ?: FinanceEntryDraft(FinanceEditorKind.valueOf(proposal.kind.name),
                proposal.amountMinor?.let {FinanceMoney.input(it)}.orEmpty(),proposal.accountId.orEmpty(),proposal.targetAccountId.orEmpty(),
                FinanceTime.input(proposal.occurredAt),safe.description,proposal.category,
                automation.tags.filter {it.proposalId==id}.joinToString("，"){it.name},proposal.occurredAt,proposal.subcategory,
                FinanceRelationshipDraft.fromProposal(proposal),FinanceCategoryTree(snapshot.categories).find(proposal.category,proposal.subcategory)?.id)
            val tokens=ThemePalette.resolve((context.applicationContext as ClosieApplication).appearanceRepository.read())
            withContext(Dispatchers.Main.immediate) {
                val settings=FinanceAutomationSettings(context).read()
                fun permitted(prefs: FinanceDiscoverySettings): Boolean =
                    if (userInitiated && proposal.source == FinanceSource.SCREENSHOT)
                        com.qq.closie.life.finance.acquisition.FinanceScreenshotPreference.enabled(context)
                    else prefs.deliveryMode == FinanceReviewDeliveryMode.IMMEDIATE && prefs.enhancedReceiptOverlay && prefs.permits(proposal)
                if(captureBusy || !canPresent() || window!=null || (owner != null && owner !== this@FinanceReceiptOverlay) || !permitted(settings) || FinanceReviewRequests.foreground || !Settings.canDrawOverlays(context)) return@withContext false
                val density=context.resources.displayMetrics.density
                fun dp(n:Int)=(n*density).toInt()
                val body=LinearLayout(context).apply {
                    orientation=LinearLayout.VERTICAL;setPadding(dp(20),dp(12),dp(20),dp(12))
                    background=GradientDrawable().apply {setColor(Color.parseColor(tokens.card));cornerRadius=dp(24).toFloat()}
                }
                val editable=mutableListOf<View>();var ready=false;var busy=false
                fun setBusy(value:Boolean){busy=value;editable.forEach {it.isEnabled=!value}}
                fun text(value:String)=TextView(context).apply {text=value;applyLifeTypography(LifeType.Caption);setTextColor(Color.parseColor(tokens.ink));setPadding(0,dp(6),0,dp(6))}.also(body::addView)
                text("Life OS · 可编辑消费小票 · 尚未入账")
                text(proposal.discoverySourceLabel()+" · 新小票会留在待确认队列，不覆盖本次编辑。")
                var scheduleSave:()->Unit={}
                var updateVisibility:()->Unit={}
                fun input(label:String,value:String,numeric:Boolean=false):EditText {
                    text(label)
                    return EditText(context).apply {
                        setSingleLine(true);inputType=if(numeric) android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL else android.text.InputType.TYPE_CLASS_TEXT
                        imeOptions=android.view.inputmethod.EditorInfo.IME_ACTION_DONE;setText(value);applyLifeTypography(LifeType.Body)
                        setTextColor(Color.parseColor(tokens.ink));minHeight=dp(48)
                        setOnEditorActionListener {view,action,_->if(action==android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                            context.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(view.windowToken,0);true
                        } else false}
                        addTextChangedListener(object:TextWatcher {override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){}
                            override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){if(ready && !busy)scheduleSave()}
                            override fun afterTextChanged(s:Editable?){} })
                    }.also {body.addView(it);editable.add(it)}
                }
                var openPanel:(String, View)->Unit = { _, _ -> }
                var closePanel:()->Boolean = { false }
                fun choice(label:String,options:List<Pair<String,String>>,selected:String):()->String {
                    var value = options.firstOrNull { it.first==selected }?.first ?: options.firstOrNull()?.first.orEmpty()
                    val control = Button(context).apply {minHeight=dp(48);applyLifeTypography(LifeType.Body)}
                    fun caption() { control.text=label+" · "+options.firstOrNull { it.first==value }?.second.orEmpty() }
                    caption()
                    control.setOnClickListener {
                        val choices=LinearLayout(context).apply {orientation=LinearLayout.VERTICAL}
                        options.forEach { option ->
                            choices.addView(Button(context).apply {
                                text=(if(option.first==value) "✓ " else "")+option.second
                                minHeight=dp(48);applyLifeTypography(LifeType.Body)
                                setOnClickListener {value=option.first;caption();closePanel();updateVisibility();scheduleSave()}
                            })
                        }
                        openPanel(label,choices)
                    }
                    body.addView(control);editable.add(control)
                    return {value}
                }
                val kind=choice("类型",FinanceEditorKind.entries.map {it.name to it.label},initial.kind.name)
                val amount=input("金额 · CNY",initial.amount,true)
                val accounts=listOf("" to "请选择真实账户")+snapshot.accounts.filter {it.archivedAt==null && it.currencyCode=="CNY"}.map {it.id to it.name}
                val account=choice("收付/转出账户",accounts,initial.accountId)
                val targetStart = body.childCount
                val target=choice("转账时的转入账户",accounts,initial.targetAccountId)
                val targetViews = (targetStart until body.childCount).map { body.getChildAt(it) }
                val date=input("实际发生时间 · 年-月-日 时:分",initial.occurredAt)
                date.isFocusable = false
                date.setOnClickListener {
                    val selected = java.util.Calendar.getInstance()
                    runCatching { FinanceTime.parse(date.text.toString()) }.getOrNull()?.let { selected.timeInMillis = it }
                    val panel=LinearLayout(context).apply {orientation=LinearLayout.VERTICAL}
                    val picker=DatePicker(context).apply {
                        init(selected.get(java.util.Calendar.YEAR),selected.get(java.util.Calendar.MONTH),
                            selected.get(java.util.Calendar.DAY_OF_MONTH),null)
                    }
                    val time=TimePicker(context).apply {
                        setIs24HourView(true);hour=selected.get(java.util.Calendar.HOUR_OF_DAY);minute=selected.get(java.util.Calendar.MINUTE)
                    }
                    panel.addView(picker);panel.addView(time)
                    panel.addView(Button(context).apply {
                        text="使用此时间";minHeight=dp(48)
                        setOnClickListener {
                            selected.set(picker.year,picker.month,picker.dayOfMonth,time.hour,time.minute,0)
                            selected.set(java.util.Calendar.MILLISECOND,0)
                            date.setText(FinanceTime.input(selected.timeInMillis));closePanel();scheduleSave()
                        }
                    })
                    openPanel("实际发生时间",panel)
                }
                val description=input("备注（可留空）",initial.description)
                val category=choice("分类",listOf("" to "未分类/自定义")+snapshot.categories.map {it.id to (FinanceProjection.categoryPath(snapshot.categories,it.id) ?: it.name)},initial.categoryId.orEmpty())
                var moreOpen = initial.relationship.role !in setOf(FinanceFlowRole.PAYMENT, FinanceFlowRole.OTHER) ||
                    initial.relationship.nature != FinanceNature.PERSONAL
                val moreButton = Button(context).apply { text = "业务关联与更多信息"; minHeight = dp(48) }.also(body::addView)
                val extrasStart = body.childCount
                val customCategory=input("自定义主分类（选填）",initial.category)
                val customSubcategory=input("自定义子分类（选填）",initial.subcategory)
                val tags=input("标签 · 逗号分隔",initial.tags)
                val nature=choice("业务性质",FinanceNature.entries.map {it.name to it.label},initial.relationship.nature.name)
                val role=choice("资金角色",FinanceFlowRole.entries.map {it.name to it.label},initial.relationship.role.name)
                val event=choice("关联原事项（退款等需要）",listOf("" to "新事项/无关联")+snapshot.v4?.events.orEmpty().filter {it.voidedAt==null}.map {it.id to (it.description.ifBlank {"事项"}+" · "+FinanceTime.input(it.occurredAt))},initial.relationship.eventId.orEmpty())
                val share=input("个人承担（特殊事项可填，可留空）",initial.relationship.share,true)
                val reduction=input("退款/返利冲减个人成本（新经济事项需明确，可填0）",initial.costReductionText,true)
                val extrasViews = (extrasStart until body.childCount).map { body.getChildAt(it) }
                updateVisibility = {
                    targetViews.forEach { it.visibility = if (kind() == FinanceEditorKind.TRANSFER.name) View.VISIBLE else View.GONE }
                    extrasViews.forEach { it.visibility = if (moreOpen) View.VISIBLE else View.GONE }
                    moreButton.text = if (moreOpen) "收起业务关联与更多信息" else "业务关联与更多信息"
                }
                moreButton.setOnClickListener { moreOpen = !moreOpen; updateVisibility() }
                updateVisibility()
                fun current():FinanceEntryDraft {
                    val eventId=event().ifBlank {null}
                    return initial.copy(kind=FinanceEditorKind.valueOf(kind()),amount=amount.text.toString(),accountId=account(),targetAccountId=target(),
                        occurredAt=date.text.toString(),description=description.text.toString(),categoryId=category().ifBlank {null},
                        category=customCategory.text.toString(),subcategory=customSubcategory.text.toString(),tags=tags.text.toString(),
                        relationship=initial.relationship.copy(nature=FinanceNature.valueOf(nature()),role=FinanceFlowRole.valueOf(role()),
                            eventId=eventId,eventUpdatedAt=snapshot.v4?.events.orEmpty().singleOrNull {it.id==eventId}?.updatedAt,share=share.text.toString()),
                        costReductionText=reduction.text.toString())
                }
                val message=text("自动保存复核草稿；确认前不改变余额。复杂关系或重复核对可接力到应用内。")
                val mutex=Mutex();var revision=saved?.revision
                suspend fun persist(draft:FinanceEntryDraft,later:Boolean=false)=mutex.withLock {withContext(NonCancellable) {
                    container.financeRepository.reviews.save(FinanceCommandToken(java.util.UUID.randomUUID().toString(),epoch),
                        draft.reviewInput(proposal,epoch),revision,draft.reviewExpected(id),later)
                    revision=Math.addExact(revision ?: 0L,1L)
                }}
                scheduleSave={if(!busy){autosave?.cancel();val draft=current();autosave=editScope.launch {
                    delay(250);try{persist(draft)}catch(e:CancellationException){throw e}catch(_:Exception){message.text="草稿状态已改变，请接力到应用内核对"}
                }}}
                fun later(openApp:Boolean=false, continueQueue:Boolean=true) {
                    if(busy)return
                    setBusy(true);autosave?.cancel();val draft=current()
                    editScope.launch {
                        try {persist(draft,true);if(openApp)context.startActivity(FinanceReviewNotifications.openIntent(context,id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));close(false, advance = !openApp && continueQueue)}
                        catch(e:CancellationException){throw e}
                        catch(_:Exception){setBusy(false);message.text="保存未完成；已保存内容仍保留，请在应用内复核"}
                    }
                }
                val actions=LinearLayout(context).apply { setPadding(dp(12), dp(8), dp(12), dp(10)) }
                fun button(label:String,action:()->Unit)=Button(context).apply {text=label;minHeight=dp(48);applyLifeTypography(LifeType.Action);setOnClickListener {action()}}.also {actions.addView(it,LinearLayout.LayoutParams(0,dp(52),1f));editable.add(it)}
                button("稍后"){later()};button("应用内接力"){later(true)}
                button("确认保存") {
                    if(!busy) {setBusy(true);autosave?.cancel();val draft=current()
                        editScope.launch {
                            try {persist(draft);mutex.withLock {
                                container.financeRepository.reviews.confirm(FinanceCommandToken(java.util.UUID.randomUUID().toString(),epoch),id,
                                    requireNotNull(revision),FinanceTime.resolve(draft.occurredAt,draft.preservedOccurredAt))
                            };FinanceReviewNotifications.cancel(context,id);close(false, advance = true)}
                            catch(e:CancellationException){throw e}
                            catch(e:Exception){setBusy(false);message.text=e.message ?: "确认未完成，请接力到应用内核对"}
                        }
                    }
                }
                val panelBody=LinearLayout(context).apply {orientation=LinearLayout.VERTICAL;visibility=View.GONE;setPadding(dp(16),dp(12),dp(16),dp(12))}
                val pages=LinearLayout(context).apply {orientation=LinearLayout.VERTICAL;addView(body);addView(panelBody)}
                closePanel = {
                    if(panelBody.visibility!=View.VISIBLE) false
                    else {panelBody.visibility=View.GONE;panelBody.removeAllViews();body.visibility=View.VISIBLE;true}
                }
                openPanel = { title, content ->
                    context.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(body.windowToken,0)
                    body.clearFocus();panelBody.removeAllViews()
                    panelBody.addView(Button(context).apply {text="返回小票 · "+title;minHeight=dp(48);setOnClickListener {closePanel()}})
                    panelBody.addView(content)
                    body.visibility=View.GONE;panelBody.visibility=View.VISIBLE
                }
                val scroll=object:ScrollView(context) {
                    private fun back(event:KeyEvent):Boolean {
                        if(event.keyCode!=KeyEvent.KEYCODE_BACK)return false
                        val ime=rootWindowInsets?.let {androidx.core.view.WindowInsetsCompat.toWindowInsetsCompat(it,this).isVisible(androidx.core.view.WindowInsetsCompat.Type.ime())}==true
                        if(ime)return false
                        if(event.action==KeyEvent.ACTION_UP && !closePanel())later()
                        return true
                    }
                    override fun dispatchKeyEvent(event:KeyEvent)=back(event)||super.dispatchKeyEvent(event)
                    override fun dispatchKeyEventPreIme(event:KeyEvent)=back(event)||super.dispatchKeyEventPreIme(event)
                }.apply {isFocusableInTouchMode=true;addView(pages)}
                body.background = null
                val frame = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    background = FinanceReceiptPaper(Color.parseColor(tokens.card), Color.parseColor(tokens.line), density)
                    elevation = dp(8).toFloat()
                    addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
                    addView(actions, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
                }
                val params=WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT,
                    (context.resources.displayMetrics.heightPixels*.8f).toInt(),WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_SECURE,PixelFormat.TRANSLUCENT).apply {
                    gravity=Gravity.BOTTOM;softInputMode=WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
                }
                val attached = attachFinanceReceipt(restoreGeneration,canPresent,authorized={
                    !captureBusy && permitted(FinanceAutomationSettings(context).read()) &&
                        Settings.canDrawOverlays(context) && !context.getSystemService(KeyguardManager::class.java).isKeyguardLocked
                }) {windows.addView(frame,params)}
                if (!attached) return@withContext false
                window=frame;owner=this@FinanceReceiptOverlay;scroll.requestFocus();ready=true
                androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(frame) { _, insets ->
                    val bars=insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
                    val ime=insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime())
                    val height=context.resources.displayMetrics.heightPixels
                    val available=(height-maxOf(bars.bottom,ime.bottom)-bars.top).coerceAtLeast(dp(120))
                    val targetHeight=minOf((height*.82f).toInt(),available)
                    if(params.height!=targetHeight || params.y!=bars.bottom) {
                        params.height=targetHeight;params.y=bars.bottom
                        runCatching {windows.updateViewLayout(frame,params)}.onFailure {close()}
                    }
                    insets
                }
                androidx.core.view.ViewCompat.requestApplyInsets(frame)
                flushLater={later(continueQueue = false)};scheduleSave()
                configuration=object:ComponentCallbacks {
                    override fun onLowMemory(){}
                    override fun onConfigurationChanged(newConfig:android.content.res.Configuration) {
                        close() // A rotated host is a new target; the saved draft remains in the inbox.
                    }
                }.also(context::registerComponentCallbacks)
                screenReceiver=object:BroadcastReceiver(){override fun onReceive(c:Context?,intent:Intent?){close()}}.also {receiver->
                    if(Build.VERSION.SDK_INT>=33)context.registerReceiver(receiver,IntentFilter(Intent.ACTION_SCREEN_OFF),Context.RECEIVER_NOT_EXPORTED)
                    else context.registerReceiver(receiver,IntentFilter(Intent.ACTION_SCREEN_OFF))
                }
                observer=scope.launch(Dispatchers.Main.immediate) {
                    combine(container.financeAutomationRepository.observe(),FinanceReviewRequests.visible,FinanceAutomationSettings(context).observe(),container.financeRepository.observeSnapshot(),
                        com.qq.closie.data.backup.RestoreStartupGate.state.map {it.status to it.restoreGeneration}.distinctUntilChanged()) {data,foreground,prefs,finance,gate->
                        (gate.first!=com.qq.closie.data.backup.RestoreStartupGate.Status.READY || gate.second!=restoreGeneration) || foreground || !permitted(prefs) ||
                            (finance.v16?.controlRows?.singleOrNull()?.restoreEpoch ?: 0L)!=epoch || data.proposals.none {it.id==id && it.revision==proposal.revision && it.status in setOf(FinanceProposalStatus.ACTIVE,FinanceProposalStatus.SNOOZED)}
                    }.catch {emit(true)}.first {it};close()
                }
                monitor=scope.launch(Dispatchers.Main.immediate) {while(isActive){delay(500)
                    if(!canPresent() || !Settings.canDrawOverlays(context) || context.getSystemService(KeyguardManager::class.java).isKeyguardLocked){close();break}
                }}
                true
            }
        } catch(e:CancellationException){throw e}
        catch(_:Exception){withContext(Dispatchers.Main.immediate){close()};false}
    }
    /** Never discards a candidate. Application-owned edit work survives listener teardown. */
    fun close(persist:Boolean=true, advance:Boolean=false) {
        if (owner === this) owner = null
        val flush=flushLater;flushLater=null
        observer?.cancel();observer=null;monitor?.cancel();monitor=null;autosave?.cancel();autosave=null
        screenReceiver?.let {runCatching {context.unregisterReceiver(it)}};screenReceiver=null
        configuration?.let {context.unregisterComponentCallbacks(it)};configuration=null
        window?.let {view->context.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(view.windowToken,0);runCatching {windows.removeViewImmediate(view)}};window=null
        if(persist)flush?.invoke()
        if(advance && !occupied) editScope.launch { FinanceReceiptQueue.pending.advance() }
    }
}
