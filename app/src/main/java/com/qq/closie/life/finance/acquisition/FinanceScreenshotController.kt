package com.qq.closie.life.finance.acquisition

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.content.ComponentCallbacks
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
import kotlinx.coroutines.channels.awaitClose
import kotlin.coroutines.resume
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.collect
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
    private var recognition: View? = null
    private var capture: Job? = null
    private var disposed = false
    private var connected = false
    private val hostWindows=FinanceHostWindows(service)
    private var hostSession:FinanceHostSession?=null
    val hasHostSession:Boolean get()=hostSession!=null
    fun onConnected() {if(!disposed){connected=true;update()}}
    private val screenReceiver=object:BroadcastReceiver(){override fun onReceive(context:Context?,intent:Intent?){invalidate();removeBubble()}}
    private val configuration=object:ComponentCallbacks {
        override fun onLowMemory(){}
        override fun onConfigurationChanged(config:android.content.res.Configuration){invalidate();removeBubble();update()}
    }
    init {
        if(Build.VERSION.SDK_INT>=33)service.registerReceiver(screenReceiver,IntentFilter(Intent.ACTION_SCREEN_OFF),Context.RECEIVER_NOT_EXPORTED)
        else service.registerReceiver(screenReceiver,IntentFilter(Intent.ACTION_SCREEN_OFF))
        service.registerComponentCallbacks(configuration)
    }
    @Volatile private var eventSerial = 0L
    @Volatile private var requestGeneration = 0L
    private var presentedPage: FinanceCapturePage? = null
    private val restoreObserver = scope.launch {
        RestoreStartupGate.state.map { it.status to it.restoreGeneration }.distinctUntilChanged().collect {
            invalidate()
            update()
        }
    }
    private fun invalidate() {
        hostSession?.cancel();hostSession=null
        requestGeneration++
        capture?.cancel()
        presentedPage = null
        overlay.close();removeRecognition()
    }
    fun hostEvent(event:android.view.accessibility.AccessibilityEvent,snapshot:FinanceHostSnapshot) {
        val session=hostSession ?: return
        val editing=presentedPage!=null
        session.observe(event,snapshot,editing)
        if(!(if(editing)session.editorCurrent(snapshot) else session.captureCurrent(snapshot))) {
            eventSerial++;invalidate()
        }
    }
    private val occupancyObserver=scope.launch {FinanceReceiptOverlay.occupancyChanges.collect {update()}}

    private var x = 0
    private var y = 280

    fun update() {
        if (disposed) return
        if(!FinanceReceiptOverlay.occupied && capture?.isActive!=true && presentedPage!=null){
            hostSession?.cancel();hostSession=null;presentedPage=null
        }
        if (!connected || !RestoreStartupGate.isReady || !FinanceScreenshotPreference.enabled(service) || locked() || !Settings.canDrawOverlays(service)) {
            invalidate(); removeBubble(); return
        }
        if (Build.VERSION.SDK_INT < 30 || FinanceProjectionRequests.inFlight || FinanceReviewRequests.foreground || capture?.isActive == true || FinanceReceiptOverlay.occupied) {
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
        try { windows.addView(view, params); bubble = view;FinanceCaptureEntryState.nativeVisible.value=true;FinanceProjectionBubbleService.ensure(service) }
        catch (_: Exception) { status("悬浮截图入口未能显示，请检查悬浮权限") }
    }

    private fun locked() = service.getSystemService(KeyguardManager::class.java).isKeyguardLocked
    private fun target(): FinanceCapturePage? {
        if(locked())return null
        val session=hostWindows.bindActive() ?: return null
        hostSession=session
        return FinanceCapturePage(session.host.packageName,session.host.id,session.orientation,eventSerial)
    }
    private fun valid(expected:FinanceCapturePage,editing:Boolean=false):Boolean {
        val session=hostSession ?: return false
        if(disposed || !connected || locked() || !FinanceScreenshotPreference.enabled(service) ||
            !RestoreStartupGate.isReady || !Settings.canDrawOverlays(service) || expected.pageGeneration!=eventSerial)return false
        val snapshot=hostWindows.snapshot()
        return if(editing)session.editorCurrent(snapshot) else session.captureCurrent(snapshot)
    }

    private fun request() {
        if (disposed || capture?.isActive == true || Build.VERSION.SDK_INT < 30) return
        // Bind host identity at the click, before hiding any surface or suspending.
        val page = target() ?: return status("请停在需要识别的外部页面，再点击截图")
        if(hostSession?.host?.identity==null) {
            status("当前页无法可靠绑定交易身份，改用逐次系统授权；结果进入待确认")
            runCatching {service.startActivity(FinanceProjectionRequests.intent(service))}
                .onFailure {status("请使用快捷设置“财务单次截图”")}
            return
        }
        val fence = RestoreStartupGate.state.value
        if (!fence.canAcquireBusinessLease || !valid(page) || !FinanceReceiptOverlay.beginCapture()) return
        val preferenceGeneration = FinanceScreenshotPreference.generation
        val generation = ++requestGeneration
        capture = scope.launch {
            val current = { isActive && preferenceGeneration == FinanceScreenshotPreference.generation && generation == requestGeneration &&
                RestoreStartupGate.state.value.restoreGeneration == fence.restoreGeneration && valid(page) }
            val storageCurrent = { isActive && !disposed && preferenceGeneration == FinanceScreenshotPreference.generation && generation == requestGeneration &&
                page.pageGeneration == eventSerial && RestoreStartupGate.isReady &&
                RestoreStartupGate.state.value.restoreGeneration == fence.restoreGeneration &&
                FinanceScreenshotPreference.enabled(service) }
            try {
                val container = LifeContainer.getInstance(service)
                runFinanceCapture(
                    page, generation, fence.restoreGeneration, current, storageCurrent,
                    token = { withContext(Dispatchers.IO) { container.financeRepository.economics.token() } },
                    hide = { removeBubble(); overlay.close(); hideOtherReceipt();FinanceAutomationSettings(service).status("正在完成本次截图") },
                    capture = { request -> withTimeout(4000) { screenshot(request.page) }.also {if(current())showRecognition()} },
                    recognize = { frame ->
                        if (blackFrame(frame)) {
                            frame.recycle()
                            error("当前画面不可采集，请手动录入")
                        }
                        var handedToEngine=false
                        val raw = try {withTimeout(12000) {
                            handedToEngine=true;OcrEngine.recognizeText(frame, releaseAfterRecognition = true).getOrThrow()
                        }} finally {if(!handedToEngine)frame.recycle()}
                        withContext(Dispatchers.Default) { FinanceLocalOcrParser.parse(raw) }
                    },
                    releaseUnused = { it.recycle() },
                    store = { request, fields, stillCurrent -> withContext(Dispatchers.IO) {
                        container.financeRepository.media.createScreenshotCandidate(request.token, fields, stillCurrent)
                    } },
                    present = { request, id, canPresent ->
                        removeRecognition()
                        if (canPresent()) {
                            presentedPage = request.page
                            if (!overlay.show(id, userInitiated = true, expectedEpoch = request.token.restoreEpoch,
                                    canContinue = { preferenceGeneration == FinanceScreenshotPreference.generation && presentedPage == request.page && generation == requestGeneration &&
                                        RestoreStartupGate.state.value.restoreGeneration == request.restoreGeneration && valid(request.page,editing=true) },
                                    canPresent = { hostSession?.host?.identity!=null && preferenceGeneration == FinanceScreenshotPreference.generation && presentedPage == request.page && generation == requestGeneration &&
                                        RestoreStartupGate.state.value.restoreGeneration == request.restoreGeneration && valid(request.page) })) {
                                val proposal = container.financeAutomationRepository.observe().first().proposals.firstOrNull { it.id == id }
                                proposal?.let { FinanceReviewNotifications.post(service, it, immediate = true) }
                                status("小票已保存在待确认中，本次未显示悬浮窗")
                            }
                        }
                    }
                )
            } catch (e: TimeoutCancellationException) { status("截图或识别超时，未自动记账，请重试") }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { status(e.message ?: "截图识别未完成，未自动记账") }
            finally { removeRecognition();FinanceReceiptOverlay.endCapture(); capture = null; update() }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @android.annotation.TargetApi(30)
    private suspend fun screenshot(target: FinanceCapturePage): Bitmap = suspendCancellableCoroutine { continuation ->
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
    private fun showRecognition() {
        removeRecognition()
        FinanceAutomationSettings(service).status("正在本地识别本次截图")
        val density=service.resources.displayMetrics.density
        val view=TextView(service).apply {
            text="正在本地识别…";contentDescription=text;gravity=Gravity.CENTER;textSize=13f
            setTextColor(Color.parseColor("#534C91"))
            background=GradientDrawable().apply {setColor(Color.parseColor("#F6F5F9"));cornerRadius=12*density}
        }
        val params=WindowManager.LayoutParams((150*density).toInt(),(48*density).toInt(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT).apply {gravity=Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL;y=(48*density).toInt()}
        runCatching {windows.addView(view,params);recognition=view}
    }
    private fun removeRecognition(){recognition?.let {runCatching {windows.removeViewImmediate(it)}};recognition=null}
    private fun status(message: String) {
        FinanceAutomationSettings(service).status(message)
        Toast.makeText(service, message, Toast.LENGTH_LONG).show()
    }
    private fun removeBubble() { bubble?.let { runCatching { windows.removeViewImmediate(it) } }; bubble = null;FinanceCaptureEntryState.nativeVisible.value=false;FinanceProjectionBubbleService.ensure(service) }
    fun close(permanent: Boolean = true) {
        connected=false;disposed=permanent;invalidate();removeBubble()
        if(permanent) {
            restoreObserver.cancel();occupancyObserver.cancel()
            runCatching {service.unregisterReceiver(screenReceiver)}
            service.unregisterComponentCallbacks(configuration)
        }
    }
}

object FinanceScreenshotPreference {
    private val revisions=java.util.concurrent.atomic.AtomicLong(0)
    internal val generation:Long get()=revisions.get()
    private const val KEY = "explicit_screenshot_enabled"
    fun observe(context:Context)=kotlinx.coroutines.flow.callbackFlow {
        val prefs=context.applicationContext.getSharedPreferences("finance_discovery_device",Context.MODE_PRIVATE)
        val listener=android.content.SharedPreferences.OnSharedPreferenceChangeListener {_,key->if(key==null || key==KEY)trySend(enabled(context))}
        prefs.registerOnSharedPreferenceChangeListener(listener);trySend(enabled(context))
        awaitClose {prefs.unregisterOnSharedPreferenceChangeListener(listener)}
    }.distinctUntilChanged()
    fun enabled(context: Context) = context.getSharedPreferences("finance_discovery_device", Context.MODE_PRIVATE).getBoolean(KEY, false)
    fun set(context: Context, enabled: Boolean) {
        revisions.incrementAndGet()
        context.getSharedPreferences("finance_discovery_device", Context.MODE_PRIVATE).edit().putBoolean(KEY, enabled).apply()
        FinanceProjectionBubbleService.ensure(context)
    }
}
