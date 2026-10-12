package com.qq.closie.life.finance

import android.app.*
import android.content.*
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.qq.closie.MainActivity
import com.qq.closie.life.data.LifeContainer
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import java.security.MessageDigest

data class FinanceDiscoverySettings(val enabled: Boolean = false, val wechat: Boolean = true,
    val alipay: Boolean = true, val backgroundReview: Boolean = true, val lastStatus: String = "",
    val immediateReview: Boolean = true, val enhancedReceipt: Boolean = false)
class FinanceAutomationSettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("finance_discovery_device", Context.MODE_PRIVATE)
    fun read() = FinanceDiscoverySettings(prefs.getBoolean("enabled", false), prefs.getBoolean("wechat", true),
        prefs.getBoolean("alipay", true), prefs.getBoolean("background", true), prefs.getString("status", "").orEmpty(),
        prefs.getBoolean("immediateReview", true), prefs.getBoolean("enhancedReceipt", false))
    fun save(value: FinanceDiscoverySettings) {
        prefs.edit().putBoolean("enabled", value.enabled).putBoolean("wechat", value.wechat)
            .putBoolean("alipay", value.alipay).putBoolean("background", value.backgroundReview)
            .putBoolean("immediateReview", value.immediateReview).putBoolean("enhancedReceipt", value.enhancedReceipt).apply()
    }
    fun status(message: String) { prefs.edit().putString("status", message).apply() }
    fun observe() = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> trySend(read()) }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        trySend(read())
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
}

object FinanceReviewRequests {
    const val EXTRA_PROPOSAL = "finance_review_proposal"
    private val foregroundState = MutableStateFlow(false)
    val visible = foregroundState.asStateFlow()
    var foreground: Boolean
        get() = foregroundState.value
        set(value) { foregroundState.value = value }
    private val mutable = MutableStateFlow<List<String>>(emptyList())
    val pending = mutable.asStateFlow()
    fun open(id: String) { mutable.update { if (id in it) it else (it + id).take(20) } }
    fun close(id: String) { mutable.update { it - id } }
}

object FinanceReviewNotifications {
    private const val CHANNEL = "finance_review"
    const val IMMEDIATE_CHANNEL = "finance_receipt_immediate_v15"
    fun cancel(context: Context, id: String) { NotificationManagerCompat.from(context).cancel(id, 0) }
    fun openIntent(context: Context, id: String) = Intent(context, MainActivity::class.java).apply {
        putExtra(FinanceReviewRequests.EXTRA_PROPOSAL, id)
        data = android.net.Uri.parse("lifeos://finance-review/$id")
        flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
    }
    fun post(context: Context, proposal: FinanceProposalEntity, immediate: Boolean = true) {
        val manager = NotificationManagerCompat.from(context)
        val channel = if (immediate) IMMEDIATE_CHANNEL else CHANNEL
        manager.createNotificationChannel(NotificationChannel(channel, if (immediate) "支付后即时复核" else "账目确认",
            if (immediate) NotificationManager.IMPORTANCE_HIGH else NotificationManager.IMPORTANCE_LOW)
            .apply { description = "发现可能的支付后，点按查看并确认"; lockscreenVisibility = Notification.VISIBILITY_PRIVATE })
        if (!manager.areNotificationsEnabled()) return
        val intent = openIntent(context, proposal.id)
        val content = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val amount = proposal.amountMinor?.let { FinanceMoney.display(it, "CNY") }
        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(com.qq.closie.R.drawable.ic_finance_review)
            .setContentTitle(amount?.let { "发现一笔 $it 支付" } ?: "发现一笔支付 · 金额待核对")
            .setContentText("点按核对账目，尚未入账")
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(if (immediate) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_LOW)
            .setContentIntent(content).setAutoCancel(true).setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).build()
        try { manager.notify(proposal.id, 0, notification) } catch (_: SecurityException) { /* Proposal remains in Finance. */ }
    }
}

/** Android adapter only: allowlist -> conservative parser -> proposal. No canonical commands here. */
class FinanceNotificationListener : NotificationListenerService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val receiptOverlay by lazy { com.qq.closie.ui.lifeos.finance.FinanceReceiptOverlay(this, scope) }
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
        val settings = FinanceAutomationSettings(this)
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
                if (FinanceReviewRequests.foreground) FinanceReviewRequests.open(created.id)
                else {
                    val overlayShown = live.enhancedReceipt && receiptOverlay.show(created.id)
                    if (live.backgroundReview || (live.enhancedReceipt && !overlayShown))
                        runCatching { FinanceReviewNotifications.post(applicationContext, created,
                            immediate = !overlayShown && (live.immediateReview || live.enhancedReceipt)) }
                            .onFailure { settings.status("待确认已保存，但后台提醒未能送达；可在账目中查看") }
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { report(DiscoveryResult.SAVE_FAILED); settings.status("最近一次发现未保存，请打开应用检查恢复状态后重试") }
        }
    }
    override fun onListenerConnected() { super.onListenerConnected(); FinanceDiscoveryDiagnostics.connection(true) }
    override fun onListenerDisconnected() { receiptOverlay.close(); super.onListenerDisconnected(); FinanceDiscoveryDiagnostics.connection(false) }
    override fun onDestroy() { receiptOverlay.close(); FinanceDiscoveryDiagnostics.connection(false); scope.cancel(); super.onDestroy() }
}
