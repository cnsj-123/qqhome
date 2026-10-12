package com.qq.closie.life.finance

/** Device presentation preferences only; not canonical Finance data. */
enum class FinanceReviewDeliveryMode(val label: String, val description: String) {
    INBOX_ONLY("收进待确认", "不主动系统提醒"),
    NORMAL("普通通知", "在通知中心保留复核提醒"),
    IMMEDIATE("即时提醒", "尽快弹出支付复核提醒，服从系统渠道设置");

    companion object {
        // V14's background=true posted a LOW channel; false only kept the proposal inbox.
        fun fromV14Background(enabled: Boolean) = if (enabled) NORMAL else INBOX_ONLY
    }
}

data class FinanceReviewDeliveryPlan(val inApp: Boolean, val notificationMode: FinanceReviewDeliveryMode?,
    val enhancedOverlay: Boolean)

object FinanceReviewDeliveryPolicy {
    fun plan(foreground: Boolean, mode: FinanceReviewDeliveryMode, enhanced: Boolean, overlayPermission: Boolean): FinanceReviewDeliveryPlan {
        if (foreground) return FinanceReviewDeliveryPlan(true, null, false)
        return FinanceReviewDeliveryPlan(false, mode.takeUnless { it == FinanceReviewDeliveryMode.INBOX_ONLY },
            mode == FinanceReviewDeliveryMode.IMMEDIATE && enhanced && overlayPermission)
    }
}

/** Acquisition reports an available ID; this port knows no concrete review surface or confirmation. */
interface FinanceReviewDelivery {
    suspend fun proposalAvailable(id: String)
    /** Called by the acquisition adapter's main-thread lifecycle callbacks. */
    fun close()
}
