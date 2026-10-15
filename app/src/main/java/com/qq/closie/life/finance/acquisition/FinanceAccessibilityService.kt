package com.qq.closie.life.finance.acquisition

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import com.qq.closie.ClosieApplication
import com.qq.closie.life.data.LifeContainer
import com.qq.closie.life.finance.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect

/** Foreground visible-detail acquisition only. No actions, navigation, history reads or logging. */
class FinanceAccessibilityService : AccessibilityService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var pending: Job? = null
    private var lastRead = 0L
    private var permittedProviders: Set<String> = emptySet()
    private val settings by lazy { FinanceAutomationSettings(this) }
    private val delivery by lazy { (application as ClosieApplication).createFinanceReviewDelivery(scope) }
    override fun onServiceConnected() {
        scope.launch {
            settings.observe().collect { value ->
                val enabled = if (value.visibleDetailEnabled) ProviderRegistry.enabled(value.enabledVisibleProviders) else emptyList()
                val permitted = enabled.map { it.key }.toSet()
                if (permitted != permittedProviders) { pending?.cancel(); delivery.close(); permittedProviders = permitted }
                serviceInfo = serviceInfo.apply {
                    eventTypes = if (enabled.isNotEmpty())
                        AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED else 0
                    packageNames = enabled.flatMap { it.packageNames }.toTypedArray()
                    notificationTimeout = 350
                    // Required for root.window metadata. Never enumerate windows or read another root.
                    flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                    feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
                }
                if (enabled.isEmpty()) { pending?.cancel(); delivery.close() }
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val config = settings.read()
        if (!config.visibleDetailEnabled || event == null || pending?.isActive == true) return
        val now = SystemClock.uptimeMillis()
        if (now - lastRead < 500L) return
        val provider = VisibleTransactionEvidenceRouter.provider(event.packageName?.toString().orEmpty(), config.enabledVisibleProviders) ?: return
        val adapter = provider.adapter
        lastRead = now
        val root = rootInActiveWindow ?: return
        val evidence = try {
            val rootPackage = root.packageName?.toString() ?: return
            if (rootPackage !in provider.packageNames || !root.isVisibleToUser) return
            val window = root.window ?: return
            try {
                if (!window.isActive || !window.isFocused || window.type != AccessibilityWindowInfo.TYPE_APPLICATION) return
            } finally { @Suppress("DEPRECATION") window.recycle() }
            val fields = VisibleDetailReader.read(root, adapter) ?: return
            adapter.parse(fields, System.currentTimeMillis()).takeIf { it.recognition == DetailRecognition.RECOGNIZED }
                ?.evidence?.takeIf { it.providerKey == provider.key }
        } catch (_: Exception) { null }
        finally { @Suppress("DEPRECATION") root.recycle() }
        evidence ?: return
        pending = scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    LifeContainer.getInstance(applicationContext).financeAutomationRepository.discoverVisible(evidence) {
                        settings.read().let { it.visibleDetailEnabled && provider.key in it.enabledVisibleProviders }
                    }
                }
                result.proposal?.let { delivery.proposalAvailable(it.id) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { settings.status("详情识别未能保存，请打开账目检查后重试") }
        }
    }
    override fun onInterrupt() { pending?.cancel(); delivery.close() }
    override fun onDestroy() { scope.cancel(); delivery.close(); super.onDestroy() }
}
