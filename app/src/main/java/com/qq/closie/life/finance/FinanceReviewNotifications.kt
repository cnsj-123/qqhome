package com.qq.closie.life.finance

import android.app.*
import android.content.*
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.qq.closie.MainActivity
import kotlinx.coroutines.flow.*

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
    fun post(context: Context, proposal: FinanceProposalEntity, immediate: Boolean = true): Boolean {
        val manager = NotificationManagerCompat.from(context)
        val channel = if (immediate) IMMEDIATE_CHANNEL else CHANNEL
        manager.createNotificationChannel(NotificationChannel(channel, if (immediate) "支付后即时复核" else "账目确认",
            if (immediate) NotificationManager.IMPORTANCE_HIGH else NotificationManager.IMPORTANCE_LOW)
            .apply { description = "发现可能的支付后，点按查看并确认"; lockscreenVisibility = Notification.VISIBILITY_PRIVATE })
        if (!manager.areNotificationsEnabled() || manager.getNotificationChannel(channel)?.importance == NotificationManager.IMPORTANCE_NONE) return false
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
        return try { manager.notify(proposal.id, 0, notification); true }
        catch (_: SecurityException) { false } // Proposal remains in Finance.
    }
}

