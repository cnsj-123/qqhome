package com.qq.closie.ui.lifeos.finance

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.qq.closie.ClosieApplication
import com.qq.closie.data.appearance.ThemePalette
import com.qq.closie.life.data.LifeContainer
import com.qq.closie.life.finance.*
import com.qq.closie.life.ui.theme.LifeType
import com.qq.closie.life.ui.theme.applyLifeTypography
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** Optional display adapter owned by the existing listener's lifecycle. Input is a proposal ID only.
 * No new service, acquisition APIs, DAO access or confirmation business logic. */
internal class FinanceReceiptOverlay(private val context: Context, private val scope: CoroutineScope) {
    private val windows = context.getSystemService(WindowManager::class.java)
    private var window: View? = null
    private var observer: Job? = null
    private var timeout: Job? = null

    suspend fun show(id: String): Boolean {
        if (!Settings.canDrawOverlays(context) || context.getSystemService(KeyguardManager::class.java).isKeyguardLocked) return false
        return try {
            val container = LifeContainer.getInstance(context)
            val automation = container.financeAutomationRepository.observe().first()
            val proposal = automation.proposals.find { it.id == id } ?: return false
            val snapshot = container.financeRepository.observeSnapshot().first()
            val model = FinanceReviewReceiptModel.from(proposal, snapshot.copy(v4 = (snapshot.v4 ?: FinanceV4Snapshot()).copy(proposals = automation.proposals)),
                automation.tags.filter { it.proposalId == id }.map { it.name }, FinanceProposalPrivacy.userDescriptions(automation.rules.flatMap { it.actions }))
            val tokens = ThemePalette.resolve((context.applicationContext as ClosieApplication).appearanceRepository.read())
            withContext(Dispatchers.Main.immediate) {
                val settings = FinanceAutomationSettings(context).read()
                if (settings.deliveryMode != FinanceReviewDeliveryMode.IMMEDIATE || !settings.enhancedReceiptOverlay || !settings.permits(proposal) || FinanceReviewRequests.foreground ||
                    !Settings.canDrawOverlays(context)) return@withContext false
                close()
                val density = context.resources.displayMetrics.density
                fun dp(value: Int) = (value * density).toInt()
                val body = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(20), dp(16), dp(20), dp(16))
                    background = GradientDrawable().apply { setColor(Color.parseColor(tokens.card)); cornerRadius = dp(24).toFloat() }
                }
                fun text(value: String, style: androidx.compose.ui.text.TextStyle = LifeType.Body) = TextView(context).apply {
                    text = value; applyLifeTypography(style)
                    setTextColor(Color.parseColor(tokens.ink)); setPadding(0, dp(5), 0, dp(5))
                }.also(body::addView)
                text("Life OS · 消费小票", LifeType.SectionTitle)
                text(model.amountMinor?.let { FinanceMoney.display(it, "CNY") } ?: "金额待核对", LifeType.Money)
                text("${model.source} · ${if (model.direction == FinanceProposalKind.INCOME) "收入" else "支出"} · ${FinanceTime.input(model.occurredAt)}", LifeType.Caption)
                text("账户：${model.accountName ?: "待选择"}")
                text("分类：${model.categoryPath ?: "待选择"}")
                text("说明：${model.safeDescription}")
                text("性质：${model.nature.label} · 尚未入账", LifeType.Caption)
                val message = text(model.ambiguityReason ?: "核对后确认，原始通知不保存。", LifeType.Caption)
                val actions = LinearLayout(context)
                body.addView(actions)
                var busy = false
                val confirm = Button(context).apply { text = if (model.directlyConfirmable) "确认" else "需要完善" }
                val later = Button(context).apply { text = "稍后" }
                confirm.applyLifeTypography(LifeType.Action)
                later.applyLifeTypography(LifeType.Action)
                confirm.setTextColor(Color.parseColor(tokens.onAccent))
                confirm.background = GradientDrawable().apply { setColor(Color.parseColor(tokens.accent)); cornerRadius = dp(12).toFloat() }
                later.setTextColor(Color.parseColor(tokens.ink))
                later.background = GradientDrawable().apply { setColor(Color.parseColor(tokens.paperSecondary)); cornerRadius = dp(12).toFloat() }
                actions.addView(later, LinearLayout.LayoutParams(0, dp(52), 1f))
                actions.addView(confirm, LinearLayout.LayoutParams(0, dp(52), 1f))
                later.setOnClickListener {
                    if (!busy) { busy = true; later.isEnabled = false; confirm.isEnabled = false
                        scope.launch {
                            try { container.financeAutomationRepository.dismiss(id, model.revision, FinanceProposalStatus.SNOOZED)
                                withContext(Dispatchers.Main.immediate) { close() }
                            } catch (e: CancellationException) { throw e }
                            catch (_: Exception) { withContext(Dispatchers.Main.immediate) {
                                busy = false; later.isEnabled = true; confirm.isEnabled = true; message.text = "状态已改变，请打开应用核对"
                            } }
                        }
                    }
                }
                confirm.setOnClickListener {
                    if (busy) return@setOnClickListener
                    if (!model.directlyConfirmable) {
                        // Activity launch is only the user's explicit tap, never discovery itself.
                        try { context.startActivity(FinanceReviewNotifications.openIntent(context, id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); close() }
                        catch (_: Exception) { message.text = "请点按账目复核通知，或打开 Life OS" }
                    } else {
                        busy = true; later.isEnabled = false; confirm.isEnabled = false
                        scope.launch {
                            try {
                                container.financeAutomationRepository.confirm(id, model.revision, requireNotNull(model.confirmation))
                                FinanceReviewNotifications.cancel(context, id)
                                withContext(Dispatchers.Main.immediate) { close() }
                            } catch (e: CancellationException) { throw e }
                            catch (_: Exception) { withContext(Dispatchers.Main.immediate) {
                                busy = false; later.isEnabled = true; confirm.isEnabled = true; message.text = "确认未完成，请打开应用核对"
                            } }
                        }
                    }
                }
                val scroll = ScrollView(context).apply { addView(body) }
                val params = WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT,
                    (context.resources.displayMetrics.heightPixels * .5f).toInt(), WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_SECURE, PixelFormat.TRANSLUCENT).apply { gravity = Gravity.BOTTOM }
                windows.addView(scroll, params)
                window = scroll
                observer = scope.launch(Dispatchers.Main.immediate) {
                    combine(container.financeAutomationRepository.observe(), FinanceReviewRequests.visible,
                        FinanceAutomationSettings(context).observe()) { data, foreground, settings ->
                        foreground || settings.deliveryMode != FinanceReviewDeliveryMode.IMMEDIATE || !settings.enhancedReceiptOverlay || !settings.permits(proposal) ||
                            data.proposals.none { it.id == id && it.revision == model.revision &&
                                it.status in setOf(FinanceProposalStatus.ACTIVE, FinanceProposalStatus.SNOOZED) }
                    }.catch { emit(true) }.first { it }
                    close()
                }
                timeout = scope.launch(Dispatchers.Main.immediate) { delay(60_000); close() }
                true
            }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { withContext(Dispatchers.Main.immediate) { close() }; false }
    }

    /** Listener callbacks and all window mutations run on the main thread. */
    fun close() {
        observer?.cancel(); observer = null
        timeout?.cancel(); timeout = null
        window?.let { runCatching { windows.removeViewImmediate(it) } }
        window = null
    }
}
