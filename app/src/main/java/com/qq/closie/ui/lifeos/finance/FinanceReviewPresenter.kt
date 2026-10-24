package com.qq.closie.ui.lifeos.finance

import com.qq.closie.life.finance.acquisition.VisibleDetailRuntime
import android.content.Context
import android.provider.Settings
import com.qq.closie.data.backup.RestoreStartupGate
import com.qq.closie.life.data.LifeContainer
import com.qq.closie.life.finance.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

/** Notification precedes optional enhancement; deferred callbacks never own canonical data. */
internal class FinanceReviewPresenter(private val context: Context, private val scope: CoroutineScope,
    private val repository: () -> FinanceAutomationRepository) : FinanceReviewDelivery {
    private val settings = FinanceAutomationSettings(context)
    private val overlay = lazy { FinanceReceiptOverlay(context, scope) }

    override suspend fun proposalAvailable(id: String, canContinue: () -> Boolean, canPresent: () -> Boolean) {
        try {
            val proposal = repository().observe().first().proposals.find { it.id == id &&
                it.status in setOf(FinanceProposalStatus.ACTIVE, FinanceProposalStatus.SNOOZED) } ?: return
            val plan = withContext(Dispatchers.Main.immediate) {
                val current = settings.read()
                if (!current.permits(proposal)) return@withContext null
                val plan = FinanceReviewDeliveryPolicy.plan(FinanceReviewRequests.foreground,
                    current.deliveryMode, current.enhancedReceiptOverlay, Settings.canDrawOverlays(context))
                if (plan.inApp && canPresent()) FinanceReviewRequests.open(id)
                plan.notificationMode?.let { mode ->
                    if (!FinanceReviewNotifications.post(context, proposal, immediate = mode == FinanceReviewDeliveryMode.IMMEDIATE))
                        settings.status("待确认已保留，但系统通知未能送达；可在账目中查看")
                }
                plan
            } ?: return
            if (plan.enhancedOverlay) {
                val epoch = withContext(Dispatchers.IO) {
                    LifeContainer.getInstance(context).financeRepository.economics.token().restoreEpoch
                }
                withContext(Dispatchers.Main.immediate) {
                    VisibleDetailRuntime.report(VisibleDetailRuntime.Phase.DELIVERY_REQUESTED)
                    val eligible = { scope.isActive && RestoreStartupGate.isReady && canPresent() }
                    val shown = eligible() && overlay.value.show(id, expectedEpoch = epoch, canContinue = {scope.isActive && RestoreStartupGate.isReady && canContinue()}, canPresent = eligible)
                    VisibleDetailRuntime.report(if (shown) VisibleDetailRuntime.Phase.OVERLAY_ATTACHED else VisibleDetailRuntime.Phase.DELIVERY_DEFERRED)
                    if (!shown && FinanceReceiptOverlay.occupied && eligible()) {
                        FinanceReceiptQueue.pending.defer(id, this@FinanceReviewPresenter, eligible) {
                            overlay.value.show(id, expectedEpoch = epoch, canContinue = {scope.isActive && RestoreStartupGate.isReady && canContinue()}, canPresent = eligible)
                        }
                        settings.status("新的小票已排队，当前编辑完成后继续")
                    } else if (!shown) settings.status("小票未能显示，待确认已保留，可在账目中查看")
                }
            }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { settings.status("待确认已保留，但提醒显示未完成，可在账目中查看") }
    }
    override fun close() {
        FinanceReceiptQueue.pending.removeOwner(this)
        if (overlay.isInitialized()) overlay.value.close()
    }
}
