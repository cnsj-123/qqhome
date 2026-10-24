package com.qq.closie.life.finance.acquisition

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.KeyguardManager
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import com.qq.closie.ClosieApplication
import com.qq.closie.life.data.LifeContainer
import com.qq.closie.life.finance.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine

/** Foreground, opt-in, bounded visible-detail reads. No automatic screenshot or navigation. */
class FinanceAccessibilityService : AccessibilityService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val scheduler = VisibleDetailScheduler(scope, ::read)
    private var settingsObserver: Job? = null
    private var permittedProviders: Set<String> = emptySet()
    private var sessionTarget: VisibleDetailScheduler.Target? = null
    private val shown = linkedSetOf<String>()
    private val hostWindows=FinanceHostWindows(this)
    private var deliverySession:FinanceHostSession?=null
    private val settings by lazy { FinanceAutomationSettings(this) }
    private val screenshot by lazy { FinanceScreenshotController(this, scope) { delivery.close() } }
    private val delivery by lazy { (application as ClosieApplication).createFinanceReviewDelivery(scope) }

    override fun onServiceConnected() {
        VisibleDetailRuntime.connected(true)
        screenshot.onConnected()
        settingsObserver?.cancel()
        settingsObserver = scope.launch {
            combine(settings.observe(),FinanceScreenshotPreference.observe(this@FinanceAccessibilityService)) {value,screenshotEnabled->value to screenshotEnabled}.collect { (value,screenshotEnabled) ->
                val enabled = if (value.visibleDetailEnabled) ProviderRegistry.enabled(value.enabledVisibleProviders) else emptyList()
                val permitted = enabled.map { it.key }.toSet()
                if (permitted != permittedProviders) {
                    cancelReads(); delivery.close(); permittedProviders = permitted
                }
                serviceInfo = (serviceInfo ?: AccessibilityServiceInfo()).apply {
                    eventTypes = if (enabled.isNotEmpty() || screenshotEnabled)
                        AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or AccessibilityEvent.TYPE_WINDOWS_CHANGED else 0
                    packageNames = null // Observe IME/our window transitions as well as foreign application changes.
                    notificationTimeout = 0 // Deliver navigation/fact changes without a coalescing delay.
                    flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                    feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
                }
                if (enabled.isEmpty()) { cancelReads(); delivery.close() }
                screenshot.update()
            }
        }
    }

    override fun onAccessibilityEvent(event:AccessibilityEvent?) {
        if(event==null || event.eventType !in setOf(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,AccessibilityEvent.TYPE_WINDOWS_CHANGED))return
        if(!VisibleDetailRuntime.state.value.connected){VisibleDetailRuntime.connected(true);screenshot.onConnected()}
        val snapshot=hostWindows.snapshot(readFacts=screenshot.hasHostSession || deliverySession!=null)
        screenshot.hostEvent(event,snapshot);screenshot.update()
        deliverySession?.let {session->
            session.observe(event,snapshot,editing=com.qq.closie.ui.lifeos.finance.FinanceReceiptOverlay.occupied)
            if(!session.editorCurrent(snapshot)){cancelReads();delivery.close()}
        }
        val pkg=event.packageName?.toString().orEmpty()
        val eventWindow=snapshot.windows.firstOrNull {it.id==event.windowId}
        if(pkg==packageName || eventWindow?.type==AccessibilityWindowInfo.TYPE_INPUT_METHOD)return
        val config=settings.read()
        val provider=if(config.visibleDetailEnabled)VisibleTransactionEvidenceRouter.provider(pkg,config.enabledVisibleProviders) else null
        if(provider==null) {
            if(snapshot.windows.any {it.type==AccessibilityWindowInfo.TYPE_APPLICATION && (it.active || it.focused) &&
                    it.packageName!=sessionTarget?.packageName}){cancelReads();delivery.close()}
            return
        }
        if(com.qq.closie.ui.lifeos.finance.FinanceReceiptOverlay.occupied)return
        val target=VisibleDetailScheduler.Target(pkg,event.windowId)
        if(sessionTarget!=target){sessionTarget=target;shown.clear()}
        VisibleDetailRuntime.report(VisibleDetailRuntime.Phase.SETTLING)
        scheduler.submit(target)
    }

    private fun allowed(target: VisibleDetailScheduler.Target) =
        scheduler.isCurrent(target) && settings.read().let {
            it.visibleDetailEnabled &&
                VisibleTransactionEvidenceRouter.provider(target.packageName, it.enabledVisibleProviders) != null
        }

    /** Each call reacquires and releases its own root synchronously before any suspension. */
    private fun evidence(target: VisibleDetailScheduler.Target, provider: ProviderDefinition): NormalizedTransactionEvidence? {
        if (getSystemService(KeyguardManager::class.java).isKeyguardLocked) {
            VisibleDetailRuntime.report(VisibleDetailRuntime.Phase.LOCKED); return null
        }
        val root = rootInActiveWindow ?: run {
            VisibleDetailRuntime.report(VisibleDetailRuntime.Phase.ROOT_NOT_READY); return null
        }
        try {
            if (root.packageName?.toString() != target.packageName || !root.isVisibleToUser) {
                VisibleDetailRuntime.report(VisibleDetailRuntime.Phase.WINDOW_CHANGED); return null
            }
            val window = root.window ?: return null
            try {
                if (!window.isActive || !window.isFocused || window.type != AccessibilityWindowInfo.TYPE_APPLICATION ||
                    (target.windowId >= 0 && window.id != target.windowId)) {
                    VisibleDetailRuntime.report(VisibleDetailRuntime.Phase.WINDOW_CHANGED); return null
                }
            } finally { @Suppress("DEPRECATION") window.recycle() }
            val inspected = VisibleDetailReader.inspect(root, provider.adapter)
            if (inspected.fields == null) {
                VisibleDetailRuntime.report(when (inspected.rejection) {
                    DetailReadRejection.TREE_LIMIT -> VisibleDetailRuntime.Phase.TREE_LIMIT
                    DetailReadRejection.AMBIGUOUS_ROWS -> VisibleDetailRuntime.Phase.AMBIGUOUS_ROWS
                    else -> VisibleDetailRuntime.Phase.NOT_DETAIL
                })
                return null
            }
            val result = provider.adapter.parse(inspected.fields, System.currentTimeMillis())
            val found = result.takeIf { it.recognition == DetailRecognition.RECOGNIZED }?.evidence
                ?.takeIf { it.providerKey == provider.key }
            if (found == null) VisibleDetailRuntime.report(when (result.recognition) {
                DetailRecognition.LOW_CONFIDENCE -> VisibleDetailRuntime.Phase.LOW_CONFIDENCE
                DetailRecognition.NOT_A_DETAIL_PAGE -> VisibleDetailRuntime.Phase.NOT_DETAIL
                else -> VisibleDetailRuntime.Phase.PAGE_UNSUPPORTED
            })
            return found
        } catch (_: Exception) {
            VisibleDetailRuntime.report(VisibleDetailRuntime.Phase.ROOT_NOT_READY); return null
        } finally { @Suppress("DEPRECATION") root.recycle() }
    }

    private suspend fun read(target: VisibleDetailScheduler.Target): Boolean {
        if (!allowed(target)) return true
        val provider = VisibleTransactionEvidenceRouter.provider(target.packageName, settings.read().enabledVisibleProviders) ?: return true
        val recognized = evidence(target, provider) ?: return false
        val binding=hostWindows.bindActive() ?: return false
        if(binding.host.identity==null)return false
        deliverySession=binding
        // Session-only suppression, with full facts: an order number alone is never transaction identity.
        val key = "${recognized.referenceDigest}:${recognized.amountMinor}:${recognized.occurredAt}:${recognized.kind}:${recognized.role}"
        if (key in shown) return true
        return try {
            val result = withContext(Dispatchers.IO) {
                LifeContainer.getInstance(applicationContext).financeAutomationRepository.discoverVisible(recognized) { allowed(target) && binding.notCancelled }
            }
            val proposal = result.proposal ?: return true
            if (shown.size >= 32) shown.remove(shown.first())
            shown.add(key)
            VisibleDetailRuntime.report(VisibleDetailRuntime.Phase.PROPOSAL_READY)
            // A late result may remain in the inbox, but cannot summon a window over a different page.
            if (allowed(target)) {
                val live = evidence(target, provider)
                if (live != null && live.referenceDigest == recognized.referenceDigest &&
                    live.amountMinor == recognized.amountMinor && live.occurredAt == recognized.occurredAt &&
                    live.kind == recognized.kind && live.role == recognized.role) delivery.proposalAvailable(proposal.id,
                    canContinue={allowed(target) && binding.editorCurrent(hostWindows.snapshot())},
                    canPresent={allowed(target) && binding.captureCurrent(hostWindows.snapshot()) &&
                        evidence(target,provider)?.let {fresh->
                            fresh.referenceDigest==recognized.referenceDigest && fresh.amountMinor==recognized.amountMinor &&
                                fresh.occurredAt==recognized.occurredAt && fresh.kind==recognized.kind && fresh.role==recognized.role
                        }==true})
            }
            true
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            VisibleDetailRuntime.report(VisibleDetailRuntime.Phase.STORE_FAILED)
            settings.status("详情识别未能保存，请打开账目检查后重试")
            true
        }
    }

    private fun cancelReads() { deliverySession?.cancel();deliverySession=null;scheduler.cancel(); sessionTarget = null; shown.clear() }
    override fun onInterrupt() { cancelReads(); screenshot.close(permanent=false); delivery.close(); VisibleDetailRuntime.connected(false); VisibleDetailRuntime.report(VisibleDetailRuntime.Phase.INTERRUPTED) }
    override fun onUnbind(intent: Intent?): Boolean {
        cancelReads(); screenshot.close(permanent = false); delivery.close(); VisibleDetailRuntime.connected(false)
        return super.onUnbind(intent)
    }
    override fun onDestroy() {
        cancelReads(); screenshot.close(); scope.cancel(); delivery.close(); VisibleDetailRuntime.connected(false); super.onDestroy()
    }
}
