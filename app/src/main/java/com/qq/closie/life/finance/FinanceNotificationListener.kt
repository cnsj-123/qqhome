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
    val alipay: Boolean = true, val backgroundReview: Boolean = false, val lastStatus: String = "")
class FinanceAutomationSettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("finance_discovery_device", Context.MODE_PRIVATE)
    fun read() = FinanceDiscoverySettings(prefs.getBoolean("enabled", false), prefs.getBoolean("wechat", true),
        prefs.getBoolean("alipay", true), prefs.getBoolean("background", false), prefs.getString("status", "").orEmpty())
    fun save(value: FinanceDiscoverySettings) {
        prefs.edit().putBoolean("enabled", value.enabled).putBoolean("wechat", value.wechat)
            .putBoolean("alipay", value.alipay).putBoolean("background", value.backgroundReview).apply()
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
    @Volatile var foreground = false
    private val mutable = MutableStateFlow<List<String>>(emptyList())
    val pending = mutable.asStateFlow()
    fun open(id: String) { mutable.update { if (id in it) it else (it + id).take(20) } }
    fun close(id: String) { mutable.update { it - id } }
}

object FinanceReviewNotifications {
    private const val CHANNEL = "finance_review"
    fun cancel(context: Context, id: String) { NotificationManagerCompat.from(context).cancel(id, 0) }
    fun post(context: Context, proposal: FinanceProposalEntity) {
        val manager = NotificationManagerCompat.from(context)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "账目确认", NotificationManager.IMPORTANCE_LOW)
            .apply { description = "发现可能的支付后，点按查看并确认"; lockscreenVisibility = Notification.VISIBILITY_PRIVATE })
        if (!manager.areNotificationsEnabled()) return
        val intent = Intent(context, MainActivity::class.java).apply {
            putExtra(FinanceReviewRequests.EXTRA_PROPOSAL, proposal.id)
            data = android.net.Uri.parse("lifeos://finance-review/${proposal.id}")
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val content = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val amount = proposal.amountMinor?.let { " " + FinanceMoney.display(it, "CNY") }.orEmpty()
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(com.qq.closie.R.drawable.ic_finance_review)
            .setContentTitle("发现一笔可能的支付$amount")
            .setContentText("点按查看并确认；尚未记入账目")
            .setContentIntent(content).setAutoCancel(true).setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).build()
        try { manager.notify(proposal.id, 0, notification) } catch (_: SecurityException) { /* Proposal remains in Finance. */ }
    }
}

/** Android adapter only: allowlist -> conservative parser -> proposal. No canonical commands here. */
class FinanceNotificationListener : NotificationListenerService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        if (sbn.packageName !in FinanceNotificationParser.packages) return
        val settings = FinanceAutomationSettings(this)
        val current = settings.read()
        if (!current.enabled || (sbn.packageName == "com.tencent.mm" && !current.wechat) ||
            (sbn.packageName == "com.eg.android.AlipayGphone" && !current.alipay)) return
        if (sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        @Suppress("DEPRECATION")
        val installed = runCatching { packageManager.getApplicationInfo(sbn.packageName, 0).enabled }.getOrDefault(false)
        if (!installed) return
        val extra = sbn.notification.extras
        val title = extra.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val body = (extra.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extra.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
        val timestamp = sbn.notification.`when`.takeIf { it > 0 } ?: sbn.postTime
        val key = MessageDigest.getInstance("SHA-256").digest(
            "${sbn.packageName}|${sbn.key}|$timestamp|$title|$body".toByteArray(Charsets.UTF_8)
        ).joinToString("") { "%02x".format(it) }
        val proposal = FinanceNotificationParser.parse(sbn.packageName, key, title, body, timestamp) ?: return
        scope.launch {
            try {
                // Recheck switches after dispatch; disabling stops queued discoveries too.
                val live = settings.read()
                if (!live.enabled || (proposal.source == FinanceSource.WECHAT && !live.wechat) ||
                    (proposal.source == FinanceSource.ALIPAY && !live.alipay)) return@launch
                val created = LifeContainer.getInstance(applicationContext).financeAutomationRepository.discover(proposal) ?: return@launch
                settings.status("最近已发现一条待确认记录")
                if (FinanceReviewRequests.foreground) FinanceReviewRequests.open(created.id)
                else if (live.backgroundReview) FinanceReviewNotifications.post(applicationContext, created)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { settings.status("最近一次发现未保存，请打开应用检查恢复状态后重试") }
        }
    }
    override fun onListenerConnected() { FinanceAutomationSettings(this).status("通知访问已连接") }
    override fun onListenerDisconnected() { FinanceAutomationSettings(this).status("通知访问已断开，可在系统设置中重新连接") }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
