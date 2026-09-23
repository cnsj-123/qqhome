package com.xiaoming.closie.ui.quickcapture

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.xiaoming.closie.MainActivity
import com.xiaoming.closie.R
import com.xiaoming.closie.data.ocr.OcrEngine
import java.io.File
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * One service drives the whole quick-capture session: a `mediaProjection` foreground service plus
 * the draggable overlay bubble, a persistent VirtualDisplay + ImageReader, the capture requests,
 * the notification and the teardown. There is intentionally **no** second foreground service.
 *
 * A session performs exactly one MediaProjection consent (handled by [QuickCaptureActivity]); the
 * VirtualDisplay stays alive for the whole session and every bubble tap only grabs the latest
 * frame via [ImageReader.OnImageAvailableListener] — never a fixed sleep, and never continuous OCR.
 *
 * Ordering matters on Android 14+ / 16: the service enters the `mediaProjection` foreground state
 * *before* calling `getMediaProjection(...)`.
 */
class QuickCaptureService : Service() {

    private val serviceJob = SupervisorJob()
    private val scope = CoroutineScope(serviceJob + Dispatchers.IO)
    private val handler = Handler(Looper.getMainLooper())

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private lateinit var windowManager: WindowManager
    private var bubbleView: View? = null
    private var menuView: View? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var paused = false

    @Volatile
    private var captureRequested = false
    @Volatile
    private var busy = false
    @Volatile
    private var sessionActive = false

    private var downRawX = 0f
    private var downRawY = 0f
    private var downX = 0
    private var downY = 0
    private var moved = false
    private val longPress = Runnable { showMenu() }
    private val captureTimeout = Runnable {
        captureRequested = false
        Toast.makeText(this, "未获取到画面，请重试", Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val CHANNEL_ID = "closie_capture"
        private const val NOTIFICATION_ID = 1002
        private const val PREF = "closie_quick_capture"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_RUNNING = "running"
        private const val KEY_PENDING_CONSENT = "pending_consent"
        private const val CAPTURE_TIMEOUT_MS = 5_000L

        // In-process truth for the capture session lifecycle. SharedPreferences can't tell whether
        // the service survived; these naturally reset to false after process death.
        @Volatile
        private var sessionStartingInProcess = false

        @Volatile
        private var sessionActiveInProcess = false

        fun isSessionStarting(): Boolean = sessionStartingInProcess

        fun isSessionActive(): Boolean = sessionActiveInProcess

        fun clearSessionStarting() {
            sessionStartingInProcess = false
        }

        const val ACTION_START = "com.xiaoming.closie.quickcapture.START"
        const val ACTION_CAPTURE = "com.xiaoming.closie.quickcapture.CAPTURE"
        const val ACTION_STOP_CAPTURE = "com.xiaoming.closie.quickcapture.STOP_CAPTURE"
        const val ACTION_END_SESSION = "com.xiaoming.closie.quickcapture.END_SESSION"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"

        fun isEnabled(context: Context): Boolean =
            context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

        fun setEnabled(context: Context, enabled: Boolean) {
            context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, enabled).apply()
        }

        fun isRunning(context: Context): Boolean =
            context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean(KEY_RUNNING, false)

        fun setRunning(context: Context, running: Boolean) {
            context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putBoolean(KEY_RUNNING, running).apply()
        }

        fun hasPendingConsent(context: Context): Boolean =
            context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean(KEY_PENDING_CONSENT, false)

        fun setPendingConsent(context: Context, pending: Boolean) {
            context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putBoolean(KEY_PENDING_CONSENT, pending).apply()
        }

        /** Starts the capture session with a freshly granted MediaProjection token. */
        fun start(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, QuickCaptureService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, data)
            sessionStartingInProcess = true
            try {
                context.startForegroundService(intent)
            } catch (e: Exception) {
                sessionStartingInProcess = false
                setEnabled(context, false)
                setRunning(context, false)
                setPendingConsent(context, false)
            }
        }

        /** Requests a single frame capture from an already-running session. */
        fun requestCapture(context: Context) {
            context.startService(Intent(context, QuickCaptureService::class.java).setAction(ACTION_CAPTURE))
        }

        /** Ends the whole session (bubble + projection + notification) and clears the pref. */
        fun endSession(context: Context) {
            context.startService(Intent(context, QuickCaptureService::class.java).setAction(ACTION_END_SESSION))
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                @Suppress("DEPRECATION")
                val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                else intent.getParcelableExtra(EXTRA_RESULT_DATA)
                if (resultCode == 0 || data == null) {
                    teardown()
                    return START_NOT_STICKY
                }
                startSession(resultCode, data)
            }
            ACTION_CAPTURE -> requestFrame()
            ACTION_STOP_CAPTURE -> teardown()
            ACTION_END_SESSION -> teardown()
        }
        return START_NOT_STICKY
    }

    private fun startSession(resultCode: Int, data: Intent) {
        QuickCaptureTempFiles.cleanupStale(this)
        if (!Settings.canDrawOverlays(this)) {
            teardown()
            return
        }
        // Android 14+ / 16: enter the mediaProjection foreground state BEFORE acquiring the token.
        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } catch (e: Exception) {
            teardown()
            return
        }
        sessionActive = true

        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val proj = runCatching { manager.getMediaProjection(resultCode, data) }.getOrNull()
        if (proj == null) {
            teardown()
            return
        }
        projection = proj
        proj.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                // The user stopped screen sharing from the system UI.
                teardown()
            }
        }, handler)

        val (width, height) = screenSize()
        val reader = runCatching { ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2) }.getOrNull()
        if (reader == null) {
            teardown()
            return
        }
        imageReader = reader
        reader.setOnImageAvailableListener({ r -> onFrameAvailable(r) }, handler)
        val display = runCatching {
            proj.createVirtualDisplay(
                "closie-capture",
                width, height, resources.displayMetrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface, null, handler
            )
        }.getOrNull()
        if (display == null) {
            teardown()
            return
        }
        virtualDisplay = display

        // Only mark the session running once projection + display + bubble are all ready.
        if (!addBubble()) return
        sessionStartingInProcess = false
        sessionActiveInProcess = true
        setRunning(this, true)
    }

    private fun onFrameAvailable(reader: ImageReader) {
        if (!captureRequested || busy) {
            // Keep the reader drained without doing any work.
            runCatching { reader.acquireLatestImage()?.close() }
            return
        }
        val image = reader.acquireLatestImage() ?: return
        captureRequested = false
        busy = true
        handler.removeCallbacks(captureTimeout)
        processImage(image)
    }

    private fun processImage(image: Image) {
        scope.launch {
            var bitmap: Bitmap? = null
            var path: String? = null
            try {
                bitmap = runCatching { imageToBitmap(image) }.getOrNull()
                if (bitmap == null) return@launch
                path = saveBitmap(bitmap)
                val text = OcrEngine.recognizeText(bitmap).getOrDefault("")
                // Never pop the preview after the session has ended (e.g. capture then immediate
                // stop). The coroutine may also be cancelled here by teardown().
                if (!sessionActive) {
                    runCatching { File(path).delete() }
                    return@launch
                }
                val intent = Intent(this@QuickCaptureService, CapturePreviewActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(CapturePreviewActivity.EXTRA_IMAGE_PATH, path)
                    .putExtra(CapturePreviewActivity.EXTRA_OCR_TEXT, text)
                val launched = runCatching { startActivity(intent); true }.getOrDefault(false)
                if (!launched) runCatching { File(path).delete() }
            } finally {
                runCatching { image.close() }
                bitmap?.let { runCatching { it.recycle() } }
                // If the session ended mid-flight (or the coroutine was cancelled), clean the
                // screenshot we may have already written.
                if (!sessionActive && path != null) runCatching { File(path).delete() }
                busy = false
            }
        }
    }

    private fun requestFrame() {
        if (paused) {
            Toast.makeText(this, "采集已暂停", Toast.LENGTH_SHORT).show()
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            setEnabled(this, false)
            teardown()
            return
        }
        if (captureRequested || busy) return
        captureRequested = true
        handler.removeCallbacks(captureTimeout)
        handler.postDelayed(captureTimeout, CAPTURE_TIMEOUT_MS)
    }

    private fun buildNotification(): android.app.Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "屏幕采集", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, QuickCaptureService::class.java).setAction(ACTION_STOP_CAPTURE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Closie 快速采集中")
            .setContentText("点悬浮球采集商品页，点停止结束")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(0, "停止", stop)
            .build()
    }

    private fun addBubble(): Boolean {
        if (!Settings.canDrawOverlays(this)) {
            teardown()
            return false
        }
        val size = dp(48)
        val bubble = TextView(this)
        bubble.text = "◆"
        bubble.textSize = 15f
        bubble.setTextColor(Color.parseColor("#F7F6F2"))
        bubble.gravity = Gravity.CENTER
        bubble.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor("#713D4B"))
        }
        bubbleParams = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(16)
            y = dp(320)
        }
        bubble.setOnTouchListener { _, event -> onBubbleTouch(event); true }
        val added = runCatching {
            windowManager.addView(bubble, bubbleParams)
            true
        }.getOrDefault(false)
        if (!added) {
            // Overlay may have been revoked between the check and addView.
            bubbleView = null
            teardown()
            return false
        }
        bubbleView = bubble
        return true
    }

    private fun onBubbleTouch(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = event.rawX
                downRawY = event.rawY
                bubbleParams?.let { downX = it.x; downY = it.y }
                moved = false
                handler.removeCallbacks(longPress)
                handler.postDelayed(longPress, 550)
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downRawX
                val dy = event.rawY - downRawY
                if (abs(dx) > dp(6) || abs(dy) > dp(6)) moved = true
                if (moved) {
                    handler.removeCallbacks(longPress)
                    bubbleParams?.let { p ->
                        p.x = downX + dx.toInt()
                        p.y = downY + dy.toInt()
                        bubbleView?.let { v -> runCatching { windowManager.updateViewLayout(v, p) } }
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(longPress)
                dismissMenu()
                if (moved) dockToEdge() else onTap()
            }
        }
    }

    private fun onTap() {
        if (paused) {
            Toast.makeText(this, "采集已暂停", Toast.LENGTH_SHORT).show()
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            setEnabled(this, false)
            teardown()
            return
        }
        requestFrame()
    }

    private fun dockToEdge() {
        val p = bubbleParams ?: return
        val displayWidth = screenWidth()
        val center = p.x + dp(48) / 2
        p.x = if (center < displayWidth / 2) dp(4) else displayWidth - dp(48) - dp(4)
        bubbleView?.let { v -> runCatching { windowManager.updateViewLayout(v, p) } }
    }

    private fun screenWidth(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            windowManager.currentWindowMetrics.bounds.width()
        } else {
            val dm = android.util.DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getMetrics(dm)
            dm.widthPixels
        }

    private fun showMenu() {
        if (menuView != null) return
        val menu = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#FFFFFF"))
            elevation = 8f
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        menu.addView(menuItem("暂停采集") {
            paused = true; dismissMenu()
            Toast.makeText(this, "已暂停采集", Toast.LENGTH_SHORT).show()
        })
        menu.addView(menuItem("继续采集") { paused = false; dismissMenu() })
        menu.addView(menuItem("结束快速采集") {
            dismissMenu()
            setEnabled(this, false)
            teardown()
        })
        val mp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            val p = bubbleParams
            x = p?.x ?: dp(16)
            y = (p?.y ?: dp(320)) + dp(56)
        }
        runCatching { windowManager.addView(menu, mp) }
        menuView = menu
    }

    private fun menuItem(label: String, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            textSize = 14f
            setTextColor(Color.parseColor("#171717"))
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setOnClickListener { onClick() }
        }

    private fun dismissMenu() {
        menuView?.let { runCatching { windowManager.removeView(it) } }
        menuView = null
    }

    private fun removeBubble() {
        handler.removeCallbacks(longPress)
        bubbleView?.let { runCatching { windowManager.removeView(it) } }
        bubbleView = null
        dismissMenu()
    }

    /** Idempotent full teardown — safe to call twice and safe after any partial failure. */
    private fun teardown() {
        handler.removeCallbacks(captureTimeout)
        sessionActive = false
        sessionStartingInProcess = false
        sessionActiveInProcess = false
        serviceJob.cancel()
        removeBubble()
        runCatching { virtualDisplay?.release() }
        runCatching { imageReader?.close() }
        runCatching { projection?.stop() }
        virtualDisplay = null
        imageReader = null
        projection = null
        captureRequested = false
        busy = false
        paused = false
        // Clear every quick-capture pref so no failure path leaves a phantom ON toggle.
        setEnabled(this, false)
        setRunning(this, false)
        setPendingConsent(this, false)
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }

    private fun imageToBitmap(image: Image): Bitmap {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * image.width
        val bitmapWidth = image.width + rowPadding / pixelStride
        val padded = Bitmap.createBitmap(bitmapWidth, image.height, Bitmap.Config.ARGB_8888)
        padded.copyPixelsFromBuffer(buffer)
        val result = Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
        if (padded !== result) padded.recycle()
        return result
    }

    private fun saveBitmap(bitmap: Bitmap): String {
        val file = File(cacheDir, "capture_${System.currentTimeMillis()}.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 90, it) }
        return file.absolutePath
    }

    private fun screenSize(): Pair<Int, Int> {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = wm.currentWindowMetrics.bounds
            b.width() to b.height()
        } else {
            val dm = android.util.DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(dm)
            dm.widthPixels to dm.heightPixels
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        teardown()
        super.onDestroy()
    }
}
