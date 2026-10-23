package com.qq.closie.life.finance

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.qq.closie.ClosieApplication
import com.qq.closie.life.data.LifeContainer
import kotlinx.coroutines.*
import java.security.MessageDigest

/** Android adapter only: allowlist -> conservative parser -> proposal. No canonical commands here. */
class FinanceNotificationListener : NotificationListenerService() {
    private val settings by lazy { FinanceAutomationSettings(this) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val reviewDelivery: FinanceReviewDelivery by lazy { (application as ClosieApplication).createFinanceReviewDelivery(scope) }
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        if (sbn.packageName !in FinanceNotificationParser.packages) return
        val source = FinanceNotificationParser.packages.getValue(sbn.packageName)
        val trace = FinanceDiscoveryDiagnostics.received(source)
        try { handleNotification(sbn, source, trace) }
        catch (_: Exception) { FinanceDiscoveryDiagnostics.result(source, trace, DiscoveryResult.MALFORMED) }
    }
    private fun handleNotification(sbn: StatusBarNotification, source: FinanceSource, trace: Long) {
        fun report(result: DiscoveryResult) = FinanceDiscoveryDiagnostics.result(source, trace, result)
        val current = settings.read()
        if (!current.enabled) { report(DiscoveryResult.MASTER_OFF); return }
        if ((source == FinanceSource.WECHAT && !current.wechat) || (source == FinanceSource.ALIPAY && !current.alipay)) {
            report(DiscoveryResult.SOURCE_OFF); return
        }
        if (sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) { report(DiscoveryResult.GROUP_SUMMARY); return }
        // Delivery itself proves the source exists; package-manager visibility is not a discovery gate.
        val extra = sbn.notification.extras
        val title = extra.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val body = listOfNotNull(extra.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString(),
            extra.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
            extra.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.joinToString("\n"))
            .filter(String::isNotBlank).distinct().joinToString("\n").take(1800)
        val timestamp = sbn.notification.`when`.takeIf { it > 0 } ?: sbn.postTime
        val key = MessageDigest.getInstance("SHA-256").digest(
            "${sbn.packageName}|${sbn.key}|$timestamp|$title|$body".toByteArray(Charsets.UTF_8)
        ).joinToString("") { "%02x".format(it) }
        val parsed = FinanceNotificationParser.inspect(sbn.packageName, key, title, body, timestamp)
        val proposal = parsed.proposal ?: run { report(parsed.result); return }
        if (System.currentTimeMillis() - timestamp > 24 * 60 * 60 * 1000L) { report(DiscoveryResult.STALE); return }
        scope.launch {
            try {
                // Recheck switches after dispatch; disabling stops queued discoveries too.
                val live = settings.read()
                if (!live.enabled) { report(DiscoveryResult.MASTER_OFF); return@launch }
                if ((source == FinanceSource.WECHAT && !live.wechat) || (source == FinanceSource.ALIPAY && !live.alipay)) {
                    report(DiscoveryResult.SOURCE_OFF); return@launch
                }
                val outcome = LifeContainer.getInstance(applicationContext).financeAutomationRepository
                    .discoverWithResult(proposal, title.take(240) + "\n" + body)
                val created = outcome.proposal ?: run { report(outcome.result); return@launch }
                FinanceDiscoveryDiagnostics.result(source, trace,
                    if (created.amountMinor == null) DiscoveryResult.AMBIGUOUS_AMOUNT else DiscoveryResult.CREATED,
                    created.amountMinor, created.id)
                settings.status("最近已发现一条待确认记录")
                reviewDelivery.proposalAvailable(created.id)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { report(DiscoveryResult.SAVE_FAILED); settings.status("最近一次发现未保存，请打开应用检查恢复状态后重试") }
        }
    }
    private var rebind: kotlinx.coroutines.Job? = null
    private fun canReconnect() = settings.read().enabled && androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)
    override fun onListenerConnected() { rebind?.cancel(); rebind = null; super.onListenerConnected(); FinanceDiscoveryDiagnostics.connection(true) }
    override fun onListenerDisconnected() {
        reviewDelivery.close(); super.onListenerDisconnected(); FinanceDiscoveryDiagnostics.connection(false)
        if (!canReconnect() || rebind?.isActive == true) return
        val component = android.content.ComponentName(this, FinanceNotificationListener::class.java)
        runCatching { requestRebind(component) }
        rebind = scope.launch {
            for (wait in listOf(1000L, 3000L)) {
                kotlinx.coroutines.delay(wait)
                if (!canReconnect()) break
                runCatching { requestRebind(component) }
            }
        }
    }
    override fun onDestroy() { rebind?.cancel(); reviewDelivery.close(); FinanceDiscoveryDiagnostics.connection(false); scope.cancel(); super.onDestroy() }
}
