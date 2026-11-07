package com.qq.closie.ui.lifeos.finance

import android.content.Context
import android.os.Looper
import android.view.*
import android.widget.*
import androidx.test.core.app.ApplicationProvider
import com.qq.closie.ClosieApplication
import com.qq.closie.data.backup.RestoreStartupGate
import com.qq.closie.life.data.LifeContainer
import com.qq.closie.life.finance.*
import com.qq.closie.life.finance.acquisition.*
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSettings

/** Real WindowManager attachment and native editor tree under shadows; not a device/IME recording. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class FinanceReceiptSessionTest {
    private val context:Context=ApplicationProvider.getApplicationContext()
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private val edits=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private lateinit var overlay:FinanceReceiptOverlay
    private lateinit var container:LifeContainer
    private val host=FinanceHostWindow(1,"synthetic.host",android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION,true,true,"original-transaction")
    private var snapshot=FinanceHostSnapshot(1,listOf(host))
    private lateinit var lease:FinanceHostSession
    @Before fun setup() {
        RestoreStartupGate.resetForTesting();RestoreStartupGate.markReady();ShadowSettings.setCanDrawOverlays(true)
        FinanceReviewRequests.foreground=false;FinanceScreenshotPreference.set(context,true)
        container=LifeContainer.createForTesting(context,androidx.room.Room.inMemoryDatabaseBuilder(context,
            com.qq.closie.life.data.database.LifeDatabase::class.java).allowMainThreadQueries().build())
        overlay=FinanceReceiptOverlay(context,scope,containerProvider={container},editOwner=edits)
        lease=FinanceHostSession(host,1)
    }
    @After fun cleanup() {
        overlay.close(false);FinanceReceiptOverlay.endCapture();scope.cancel()
        edits.cancel()
        FinanceScreenshotPreference.set(context,false);container.lifeDatabase.close();RestoreStartupGate.resetForTesting()
    }
    private suspend fun attach(at:Long?=null):String {
        val id=container.financeRepository.media.createScreenshotCandidate(container.financeRepository.economics.token(),
            FinanceOcrFields(1234,at,FinanceProposalKind.EXPENSE,null,"合成",currencyCode="CNY")){true}
        assertTrue(FinanceReceiptOverlay.beginCapture())
        assertTrue(overlay.show(id,true,canContinue={lease.editorCurrent(snapshot)},canPresent={lease.captureCurrent(snapshot)}))
        return id
    }
    private fun views():List<View> {
        fun all(v:View):List<View> = listOf(v)+(if(v is ViewGroup)(0 until v.childCount).flatMap {all(v.getChildAt(it))} else emptyList())
        return org.robolectric.shadow.api.Shadow.extract<org.robolectric.shadows.ShadowWindowManagerImpl>(context.getSystemService(WindowManager::class.java)).views.flatMap(::all)
    }
    @Test fun attachedEditorSurvivesFocusTransferAndNativeEditsButClosesOnActualPageChange()=runBlocking {
        attach()
        snapshot=snapshot.copy(windows=listOf(host.copy(active=false,focused=false),
            FinanceHostWindow(2,context.packageName,android.view.accessibility.AccessibilityWindowInfo.TYPE_SYSTEM,true,true,null)))
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(2500))
        assertTrue(FinanceReceiptOverlay.occupied)
        val inputs=views().filterIsInstance<EditText>();inputs.first().setText("23.45")
        views().filterIsInstance<EditText>().single {it.contentDescription=="备注（可留空）"}.setText("合成编辑")
        for(label in listOf("收付/转出账户","分类","实际发生时间")) {
            val button=if(label=="实际发生时间")views().filterIsInstance<EditText>().firstOrNull {!it.isFocusable}
                else views().filterIsInstance<Button>().firstOrNull {it.contentDescription==label}
            assertNotNull(label,button);button!!.performClick()
            val back=views().filterIsInstance<Button>().firstOrNull {it.text.toString().startsWith("返回小票")}
            assertNotNull("same-window subpanel must open",back);back!!.performClick()
            assertTrue(FinanceReceiptOverlay.occupied)
        }
        assertFalse(FinanceReceiptOverlay.beginCapture()) // a second request cannot evict an editor
        val navigation = android.view.accessibility.AccessibilityEvent.obtain(android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED).apply {
            packageName = host.packageName
            shadowOf(this).setWindowId(host.id)
        }
        lease.observe(navigation,snapshot,true)
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(501))
        assertFalse(FinanceReceiptOverlay.occupied)
    }
    @Test fun occupancySignalSpansCaptureToEditorAndNotifiesClose()=runBlocking {
        attach();assertTrue(FinanceReceiptOverlay.occupancyChanges.value)
        FinanceReceiptOverlay.endCapture();assertTrue(FinanceReceiptOverlay.occupancyChanges.value)
        overlay.close(false);assertFalse(FinanceReceiptOverlay.occupancyChanges.value)
    }

    private val repository get()=container.financeRepository
    private fun control(label:String)=views().filterIsInstance<Button>().single {it.contentDescription==label}
    private fun button(label:String)=views().filterIsInstance<Button>().single {it.text.toString()==label}
    private fun dateField()=views().filterIsInstance<EditText>().single {it.contentDescription?.toString()?.startsWith("实际发生时间")==true}
    private fun choose(option:String) {
        views().filterIsInstance<Button>().single {it.contentDescription==null && it.text.toString().removePrefix("✓ ")==option}.performClick()
    }
    private fun applyField(){control("应用字段编辑").performClick()}
    private fun updatePicker() {
        views().filterIsInstance<DatePicker>().single().updateDate(2026,10,3)
        views().filterIsInstance<TimePicker>().single().apply {hour=10;minute=44}
    }
    private suspend fun await(description:String, condition:suspend ()->Boolean) {
        val deadline=System.nanoTime()+java.time.Duration.ofSeconds(10).toNanos()
        do {
            shadowOf(Looper.getMainLooper()).idle()
            if(condition())return
            Thread.yield()
        } while(System.nanoTime()<deadline)
        fail(description)
    }
    private fun advanceDebounce(){shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(300))}
    private suspend fun session(id:String)=repository.snapshot().v16?.reviewSessionRows.orEmpty().singleOrNull {it.proposalId==id}

    @Test fun datePanelRejectsStaleOuterClickCancelsAndAppliesBeforeExactlyOneCanonicalCommit()=runBlocking {
        val old=FinanceTime.parse("2026-10-12 14:32")
        val new=FinanceTime.parse("2026-11-03 10:44")
        val account=repository.saveAccount(name="合成零钱",kind=FinanceAccountKind.WECHAT,balanceAnchorAt=0L)
        val id=attach(old)
        control("收付/转出账户").performClick();choose("合成零钱 · CNY");applyField()
        val confirm=control("确认记账")
        dateField().performClick();updatePicker()
        assertFalse(confirm.isEnabled)
        assertEquals(View.GONE,(confirm.parent as View).visibility)
        confirm.performClick() // A retained listener cannot bypass the actual editor state either.
        advanceDebounce()
        await("applied draft saved during date panel") {session(id)?.accountId==account.id}
        assertEquals(FinanceTime.input(old),session(id)?.occurredText)
        assertTrue(repository.snapshot().entries.isEmpty())
        button("取消").performClick()
        assertEquals(FinanceTime.input(old),dateField().text.toString())
        assertTrue(confirm.isEnabled)
        dateField().performClick();updatePicker();applyField()
        assertEquals(FinanceTime.input(new),dateField().text.toString())
        confirm.performClick();confirm.performClick()
        await("single applied-date canonical confirmation") {repository.snapshot().entries.size==1 && !overlay.isShowing}
        val entry=repository.snapshot().entries.single()
        assertEquals(new,entry.occurredAt);assertEquals(1234L,entry.amountMinor);assertEquals(account.id,entry.accountId)
        assertEquals(FinanceReviewState.COMPLETED,session(id)?.state)
    }

    @Test fun accountCategoryAndNatureUseExplicitApplyAndCancelWithRealDraftPersistence()=runBlocking {
        val a=repository.saveAccount(name="合成钱包",kind=FinanceAccountKind.CASH)
        val b=repository.saveAccount(name="合成旅行卡",kind=FinanceAccountKind.BANK_CARD)
        val category=repository.createCategory("合成咖啡",null)
        val id=attach()
        control("收付/转出账户").performClick();choose("合成钱包 · CNY");applyField()
        control("收付/转出账户").performClick();choose("合成旅行卡 · CNY");button("取消").performClick()
        assertEquals("合成钱包",control("收付/转出账户").text.toString())
        control("分类").performClick();choose("合成咖啡");overlay.back()
        assertEquals("未分类/自定义",control("分类").text.toString())
        control("业务性质 · 分账").performClick();button("取消").performClick()
        assertTrue(control("业务性质 · 普通消费").isSelected)
        advanceDebounce();await("only applied account saved") {session(id)?.accountId==a.id}
        assertNull(session(id)?.categoryId);assertEquals(FinanceNature.PERSONAL,session(id)?.nature)
        control("收付/转出账户").performClick();choose("合成旅行卡 · CNY");applyField()
        control("分类").performClick();choose("合成咖啡");applyField()
        control("业务性质 · 分账").performClick();applyField()
        assertTrue(control("业务性质 · 分账").isSelected)
        advanceDebounce();await("applied fields saved together") {
            session(id)?.let {it.accountId==b.id && it.categoryId==category.id && it.nature==FinanceNature.SPLIT}==true
        }
        assertTrue(repository.snapshot().entries.isEmpty())
    }

    @Test fun failedConfirmationRetainsAppliedInputAndCanRetryWithoutDuplicate()=runBlocking {
        val account=repository.saveAccount(name="合成零钱",kind=FinanceAccountKind.WECHAT,balanceAnchorAt=0L)
        val id=attach(FinanceTime.parse("2026-10-12 14:32"))
        control("收付/转出账户").performClick();choose("合成零钱 · CNY");applyField()
        val amount=views().filterIsInstance<EditText>().single {it.contentDescription?.toString()?.startsWith("金额 ·")==true}
        amount.setText("0");val confirm=control("确认记账");confirm.performClick()
        await("rejected zero amount retains real draft and restores controls") {session(id)?.amountText=="0" && confirm.isEnabled}
        assertTrue("The first rejection must be the invalid amount",views().filterIsInstance<TextView>().any {it.text.toString()=="金额必须大于 0"})
        assertTrue(overlay.isShowing);assertEquals("0",amount.text.toString());assertTrue(repository.snapshot().entries.isEmpty())
        assertEquals(account.id,session(id)?.accountId)
        val rejectedRevision=requireNotNull(session(id)).revision
        amount.setText("23.45");confirm.performClick();confirm.performClick()
        await("retry returns from the real Room command") {!overlay.isShowing || confirm.isEnabled}
        val retried=requireNotNull(session(id))
        val errors=views().filterIsInstance<TextView>().map {it.text.toString()}.filter {it.isNotBlank()}
        assertEquals("Retry must persist the corrected applied amount; revision=${retried.revision}; paper=$errors","23.45",retried.amountText)
        assertTrue("Retry must advance the persisted revision; paper=$errors",retried.revision>rejectedRevision)
        assertEquals("Retry must commit exactly one entry; review=$retried; paper=$errors",1,repository.snapshot().entries.size)
        assertFalse("A successful retry closes its original paper; review=$retried; paper=$errors",overlay.isShowing)
        assertEquals(FinanceReviewState.COMPLETED,retried.state)
        assertEquals(2345L,repository.snapshot().entries.single().amountMinor)
    }

    @Test fun screenshotPermissionRevocationInvalidatesRetainedConfirmAndDiscardsPendingDate()=runBlocking {
        val old=FinanceTime.parse("2026-10-12 14:32")
        val id=attach(old);val retained=control("确认记账")
        dateField().performClick();updatePicker()
        FinanceScreenshotPreference.set(context,false)
        FinanceScreenshotPreference.set(context,true) // The old generation must never revive.
        retained.performClick()
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(501))
        await("revoked paper closes and flushes only applied draft") { !overlay.isShowing && session(id)?.state==FinanceReviewState.LATER }
        assertEquals(FinanceTime.input(old),session(id)?.occurredText)
        assertTrue(repository.snapshot().entries.isEmpty())
    }

    @Test fun lockScreenClosesFieldEditorAndRetainsOnlyTheAppliedDate()=runBlocking {
        val old=FinanceTime.parse("2026-10-12 14:32")
        val id=attach(old)
        dateField().performClick();updatePicker()
        val lock=shadowOf(context.getSystemService(android.app.KeyguardManager::class.java))
        lock.setKeyguardLocked(true)
        try {
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(501))
            await("screen lock closes the attached editor and flushes applied data") {
                !overlay.isShowing && session(id)?.state==FinanceReviewState.LATER
            }
            assertEquals(FinanceTime.input(old),session(id)?.occurredText)
            assertTrue(repository.snapshot().entries.isEmpty())
        } finally {lock.setKeyguardLocked(false)}
    }

    @Test fun detachedFieldListenersCannotWriteOrLaunchAfterAnotherCandidateMounts()=runBlocking {
        val oldDate=FinanceTime.parse("2026-10-12 14:32")
        val oldId=attach(oldDate)
        val oldConfirm=control("确认记账")
        val oldAccount=control("收付/转出账户")
        val oldHandoff=button("应用内接力")
        val oldLater=button("稍后处理 · 进入待确认")
        val oldAmount=views().filterIsInstance<EditText>().single {it.contentDescription?.toString()?.startsWith("金额 ·")==true}
        dateField().performClick();updatePicker()
        val oldApply=control("应用字段编辑")
        overlay.close()
        await("old applied draft flushed on close") {session(oldId)?.state==FinanceReviewState.LATER}
        assertEquals(FinanceTime.input(oldDate),session(oldId)?.occurredText)

        val nextId=attach(oldDate)
        advanceDebounce();await("new mounted draft is durable") {session(nextId)!=null}
        val nextConfirm=control("确认记账")
        dateField().performClick();updatePicker()
        val nextApply=control("应用字段编辑")
        val before=repository.portableSnapshot()
        assertFalse(oldConfirm.isEnabled);assertFalse(oldApply.isEnabled)
        oldApply.performClick();oldAccount.performClick();oldApply.performClick()
        oldAmount.setText("999.99")
        oldConfirm.performClick();oldLater.performClick();oldHandoff.performClick()
        advanceDebounce()
        assertEquals(before,repository.portableSnapshot())
        assertTrue(overlay.isShowing);assertFalse(nextConfirm.isEnabled)
        assertEquals(FinanceTime.input(oldDate),dateField().text.toString())
        assertNull(shadowOf(context.applicationContext as android.app.Application).nextStartedActivity)
        nextApply.performClick()
        assertEquals("2026-11-03 10:44",dateField().text.toString())
        assertTrue(nextConfirm.isEnabled);assertTrue(repository.snapshot().entries.isEmpty())
    }

    @Test fun handoffFinishingAfterHostCloseDoesNotLaunchAnActivityOrCloseANewPaper()=runBlocking {
        val id=attach()
        button("应用内接力").performClick()
        overlay.close()
        await("handoff draft is preserved by the close-only flush") {session(id)?.state==FinanceReviewState.LATER}
        val nextId=attach()
        advanceDebounce();await("replacement receipt remains mounted") {session(nextId)!=null}
        assertTrue(overlay.isShowing)
        assertNull(shadowOf(context.applicationContext as android.app.Application).nextStartedActivity)
        assertTrue(repository.snapshot().entries.isEmpty())
    }
}
