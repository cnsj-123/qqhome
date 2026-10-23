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

/** Foreground, opt-in, bounded visible-detail reads. No automatic screenshot or navigation. */
class FinanceAccessibilityService : AccessibilityService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val scheduler = VisibleDetailScheduler(scope, ::read)
    private var settingsObserver: Job? = null
    private var permittedProviders: Set<String> = emptySet()
    private var sessionTarget: VisibleDetailScheduler.Target? = null
    private val shown = linkedSetOf<String>()
    private val settings by lazy { FinanceAutomationSettings(this) }
    private val screenshot by lazy { FinanceScreenshotController(this, scope) { delivery.close() } }
    private val delivery by lazy { (application as ClosieApplication).createFinanceReviewDelivery(scope) }

    override fun onServiceConnected() {
        VisibleDetailRuntime.connected(true)
        settingsObserver?.cancel()
        settingsObserver = scope.launch {
            settings.observe().collect { value ->
                val enabled = if (value.visibleDetailEnabled) ProviderRegistry.enabled(value.enabledVisibleProviders) else emptyList()
                val permitted = enabled.map { it.key }.toSet()
                if (permitted != permittedProviders) {
                    cancelReads(); delivery.close(); permittedProviders = permitted
                }
                serviceInfo = serviceInfo.apply {
                    eventTypes = if (enabled.isNotEmpty() || FinanceScreenshotPreference.enabled(this@FinanceAccessibilityService))
                        AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED else 0
                    packageNames = if (FinanceScreenshotPreference.enabled(this@FinanceAccessibilityService)) null else enabled.flatMap { it.packageNames }.toTypedArray()
                    notificationTimeout = 350
                    flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                    feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
                }
                if (enabled.isEmpty()) { cancelReads(); delivery.close() }
                screenshot.update()
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.eventType !in setOf(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)) return
        val config = settings.read()
        val packageName = event.packageName?.toString().orEmpty()
        screenshot.hostEvent(packageName); screenshot.update()
        val provider = if (config.visibleDetailEnabled)
            VisibleTransactionEvidenceRouter.provider(packageName, config.enabledVisibleProviders) else null
        if (provider == null) { cancelReads(); return }
        val target = VisibleDetailScheduler.Target(packageName, event.windowId)
        if (sessionTarget != target) { sessionTarget = target; shown.clear() }
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
        // Session-only suppression, with full facts: an order number alone is never transaction identity.
        val key = "${recognized.referenceDigest}:${recognized.amountMinor}:${recognized.occurredAt}:${recognized.kind}:${recognized.role}"
        if (key in shown) return true
        return try {
            val result = withContext(Dispatchers.IO) {
                LifeContainer.getInstance(applicationContext).financeAutomationRepository.discoverVisible(recognized) { allowed(target) }
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
                    live.kind == recognized.kind && live.role == recognized.role) delivery.proposalAvailable(proposal.id) {
                        allowed(target) && evidence(target, provider)?.let { fresh ->
                            fresh.referenceDigest == recognized.referenceDigest && fresh.amountMinor == recognized.amountMinor &&
                                fresh.occurredAt == recognized.occurredAt && fresh.kind == recognized.kind && fresh.role == recognized.role
                        } == true
                    }
            }
            true
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            VisibleDetailRuntime.report(VisibleDetailRuntime.Phase.STORE_FAILED)
            settings.status("详情识别未能保存，请打开账目检查后重试")
            true
        }
    }

    private fun cancelReads() { scheduler.cancel(); sessionTarget = null; shown.clear() }
    override fun onInterrupt() { cancelReads(); delivery.close(); VisibleDetailRuntime.report(VisibleDetailRuntime.Phase.INTERRUPTED) }
    override fun onUnbind(intent: Intent?): Boolean {
        cancelReads(); screenshot.close(permanent = false); delivery.close(); VisibleDetailRuntime.connected(false)
        return super.onUnbind(intent)
    }
    override fun onDestroy() {
        cancelReads(); screenshot.close(); scope.cancel(); delivery.close(); VisibleDetailRuntime.connected(false); super.onDestroy()
    }
}
