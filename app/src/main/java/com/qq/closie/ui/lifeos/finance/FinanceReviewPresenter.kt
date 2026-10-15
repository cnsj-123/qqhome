package com.qq.closie.ui.lifeos.finance

import android.content.Context
import android.provider.Settings
import com.qq.closie.life.finance.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

/** Chooses presentation only. Notification is posted before any optional enhancement. */
internal class FinanceReviewPresenter(private val context: Context, scope: CoroutineScope,
    private val repository: () -> FinanceAutomationRepository) : FinanceReviewDelivery {
    private val settings = FinanceAutomationSettings(context)
    private val overlay = lazy { FinanceReceiptOverlay(context, scope) }

    override suspend fun proposalAvailable(id: String) {
        try {
            val proposal = repository().observe().first().proposals.find { it.id == id &&
                it.status in setOf(FinanceProposalStatus.ACTIVE, FinanceProposalStatus.SNOOZED) } ?: return
            val plan = withContext(Dispatchers.Main.immediate) {
                val current = settings.read()
                if (!current.permits(proposal)) return@withContext null
                val plan = FinanceReviewDeliveryPolicy.plan(FinanceReviewRequests.foreground,
                    current.deliveryMode, current.enhancedReceiptOverlay, Settings.canDrawOverlays(context))
                if (plan.inApp) FinanceReviewRequests.open(id)
                plan.notificationMode?.let { mode ->
                    if (!FinanceReviewNotifications.post(context, proposal, immediate = mode == FinanceReviewDeliveryMode.IMMEDIATE))
                        settings.status("待确认已保存，但系统提醒未能送达；可在账目中查看")
                }
                plan
            } ?: return
            // Success, timeout, replacement and permission failure never remove the notification.
            if (plan.enhancedOverlay) overlay.value.show(id)
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { settings.status("待确认已保存，但复核提醒未完成；可在账目中查看") }
    }
    override fun close() { if (overlay.isInitialized()) overlay.value.close() }
}
