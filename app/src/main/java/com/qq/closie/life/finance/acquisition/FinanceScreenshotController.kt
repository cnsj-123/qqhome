package com.qq.closie.life.finance.acquisition

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.Settings
import android.view.*
import android.widget.TextView
import android.widget.Toast
import com.qq.closie.data.backup.RestoreStartupGate
import com.qq.closie.data.ocr.OcrEngine
import com.qq.closie.life.data.LifeContainer
import com.qq.closie.life.finance.*
import com.qq.closie.ui.lifeos.finance.FinanceReceiptOverlay
import kotlinx.coroutines.*
import kotlin.coroutines.resume
import kotlinx.coroutines.flow.first
import kotlin.coroutines.resumeWithException
import kotlin.math.abs

/** Explicit one-tap capture. No timer, MediaProjection session, image file or retained OCR text. */
internal class FinanceScreenshotController(
    private val service: AccessibilityService, private val scope: CoroutineScope,
    private val hideOtherReceipt: () -> Unit
) {
    private val windows = service.getSystemService(WindowManager::class.java)
    private val overlay = FinanceReceiptOverlay(service, scope)
    private var bubble: View? = null
    private var capture: Job? = null
    private var disposed = false
    @Volatile private var eventSerial = 0L
    @Volatile private var activeRequestSerial: Long? = null
    fun hostEvent(packageName: String) { if (packageName != service.packageName) eventSerial++ }
    private var x = 0
    private var y = 280
    private data class Target(val packageName: String, val windowId: Int, val orientation: Int, val serial: Long)

    fun update() {
        if (disposed) return
        if (!FinanceScreenshotPreference.enabled(service) || locked() || !Settings.canDrawOverlays(service)) {
            capture?.cancel(); removeBubble(); overlay.close(); return
        }
        if (Build.VERSION.SDK_INT < 30 || FinanceReviewRequests.foreground || capture?.isActive == true) {
            removeBubble(); return
        }
        if (bubble != null) return
        val density = service.resources.displayMetrics.density
        val params = WindowManager.LayoutParams((52 * density).toInt(), (52 * density).toInt(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.END; x = this@FinanceScreenshotController.x; y = this@FinanceScreenshotController.y }
        val view = TextView(service).apply {
            text = "截图\n识别"; contentDescription = "单次截图识别账单，拖动可移动"
            gravity = Gravity.CENTER; textSize = 12f; setTextColor(Color.parseColor("#534C91"))
            background = GradientDrawable().apply { setColor(Color.parseColor("#F6F5F9")); cornerRadius = 20 * density }
        }
        view.setOnClickListener { request() }
        var downX = 0f; var downY = 0f; var originX = 0; var originY = 0; var moved = false
        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = event.rawX; downY = event.rawY; originX = params.x; originY = params.y; moved = false; true }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX; val dy = event.rawY - downY
                    if (abs(dx) + abs(dy) > ViewConfiguration.get(service).scaledTouchSlop) moved = true
                    if (moved) {
                        params.x = (originX - dx.toInt()).coerceAtLeast(0)
                        params.y = (originY + dy.toInt()).coerceIn(0, (service.resources.displayMetrics.heightPixels - 60 * density).toInt().coerceAtLeast(0))
                        x = params.x; y = params.y
                        runCatching { windows.updateViewLayout(view, params) }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> { if (!moved) { view.performClick() }; true }
                MotionEvent.ACTION_CANCEL -> { moved = true; true }
                else -> false
            }
        }
        try { windows.addView(view, params); bubble = view }
        catch (_: Exception) { status("悬浮截图入口未能显示，请检查悬浮权限") }
    }

    private fun locked() = service.getSystemService(KeyguardManager::class.java).isKeyguardLocked
    private fun target(): Target? {
        if (locked()) return null
        val root = service.rootInActiveWindow ?: return null
        return try {
            val packageName = root.packageName?.toString() ?: return null
            if (packageName == service.packageName || !root.isVisibleToUser) return null
            val window = root.window ?: return null
            try {
                if (!window.isActive || !window.isFocused || window.type != android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION) null
                else Target(packageName, window.id, service.resources.configuration.orientation, eventSerial)
            } finally { @Suppress("DEPRECATION") window.recycle() }
        } finally { @Suppress("DEPRECATION") root.recycle() }
    }
    private fun valid(expected: Target) = !disposed && FinanceScreenshotPreference.enabled(service) &&
        RestoreStartupGate.isReady && Settings.canDrawOverlays(service) && target() == expected

    private fun request() {
        if (capture?.isActive == true || Build.VERSION.SDK_INT < 30) return
        capture = scope.launch {
            removeBubble(); overlay.close(); hideOtherReceipt()
            try {
                delay(180) // Only our surfaces leave; this delay never repeats or samples the host.
                val target = target() ?: error("请停留在要识别的外部页面，解锁后再试")
                check(valid(target)) { "当前无法截图，请检查授权和恢复状态" }
                activeRequestSerial = target.serial
                var bitmap: Bitmap? = null
                var handedToOcr = false
                val fields = try {
                    val frame = withTimeout(4000) { screenshot(target) }
                    bitmap = frame
                    check(valid(target)) { "页面已切换，本次截图已放弃" }
                    check(!blackFrame(frame)) { "当前画面不可采集或受到保护，请手动录入" }
                    handedToOcr = true
                    val raw = withTimeout(12000) { OcrEngine.recognizeText(frame, releaseAfterRecognition = true).getOrThrow() }
                    withContext(Dispatchers.Default) { FinanceLocalOcrParser.parse(raw) }
                } finally {
                    if (!handedToOcr) bitmap?.recycle()
                }
                check(valid(target)) { "页面已切换或授权失效，识别结果已放弃" }
                check(fields.amountMinor != null || fields.occurredAt != null || fields.suggestedKind != null) {
                    "未读到可核对的账单字段，请展开详情后重试或手动录入"
                }
                val container = LifeContainer.getInstance(service)
                val token = withContext(Dispatchers.IO) { container.financeRepository.economics.token() }
                val id = withContext(Dispatchers.IO) {
                    container.financeRepository.media.createScreenshotCandidate(token, fields) {
                        !disposed && activeRequestSerial == eventSerial && RestoreStartupGate.isReady && FinanceScreenshotPreference.enabled(service)
                    }
                }
                if (valid(target)) {
                    if (!overlay.show(id, userInitiated = true)) {
                        val proposal = container.financeAutomationRepository.observe().first().proposals.firstOrNull { it.id == id }
                        proposal?.let { FinanceReviewNotifications.post(service, it, immediate = true) }
                        status("小票已保留在待确认中，本次未能显示悬浮窗口")
                    }
                }
            } catch (e: TimeoutCancellationException) { status("截图或识别超时，未自动记账，请重试") }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { status(e.message ?: "截图识别未完成，未自动记账") }
            finally { activeRequestSerial = null; capture = null; update() }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @android.annotation.TargetApi(30)
    private suspend fun screenshot(target: Target): Bitmap = suspendCancellableCoroutine { continuation ->
        val callback = object : AccessibilityService.TakeScreenshotCallback {
            override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                val buffer = result.hardwareBuffer
                var image: Bitmap? = null
                try {
                    val hardware = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace) ?: error("截图为空")
                    try { image = hardware.copy(Bitmap.Config.ARGB_8888, false) ?: error("截图转换失败") }
                    finally { hardware.recycle() }
                    if (continuation.isActive) {
                        val completed = requireNotNull(image)
                        continuation.resume(completed) { completed.recycle() }
                    }
                    else image?.recycle()
                } catch (e: Exception) { image?.recycle(); if (continuation.isActive) continuation.resumeWithException(e) }
                finally { buffer.close() }
            }
            override fun onFailure(errorCode: Int) {
                if (continuation.isActive) continuation.resumeWithException(IllegalStateException(
                    "系统拒绝截图（$errorCode），可能是受保护页面、请求过快或截图授权失效"))
            }
        }
        try {
            if (Build.VERSION.SDK_INT >= 34) service.takeScreenshotOfWindow(target.windowId, service.mainExecutor, callback)
            else service.takeScreenshot(Display.DEFAULT_DISPLAY, service.mainExecutor, callback)
        } catch (e: Exception) { if (continuation.isActive) continuation.resumeWithException(e) }
    }

    private fun blackFrame(bitmap: Bitmap): Boolean {
        var nonBlack = 0
        for (x in 0 until 12) for (y in 0 until 12) {
            val color = bitmap.getPixel(x * (bitmap.width - 1) / 11, y * (bitmap.height - 1) / 11)
            if (Color.alpha(color) > 0 && (Color.red(color) > 12 || Color.green(color) > 12 || Color.blue(color) > 12)) nonBlack++
        }
        return nonBlack < 3
    }
    private fun status(message: String) {
        FinanceAutomationSettings(service).status(message)
        Toast.makeText(service, message, Toast.LENGTH_LONG).show()
    }
    private fun removeBubble() { bubble?.let { runCatching { windows.removeViewImmediate(it) } }; bubble = null }
    fun close(permanent: Boolean = true) { disposed = permanent; capture?.cancel(); removeBubble(); overlay.close() }
}

object FinanceScreenshotPreference {
    private const val KEY = "explicit_screenshot_enabled"
    fun enabled(context: Context) = context.getSharedPreferences("finance_discovery_device", Context.MODE_PRIVATE).getBoolean(KEY, false)
    fun set(context: Context, enabled: Boolean) {
        context.getSharedPreferences("finance_discovery_device", Context.MODE_PRIVATE).edit().putBoolean(KEY, enabled).apply()
    }
}
