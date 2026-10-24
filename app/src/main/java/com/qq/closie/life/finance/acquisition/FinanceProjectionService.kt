package com.qq.closie.life.finance.acquisition

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.graphics.*
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.*
import android.os.*
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.qq.closie.R
import com.qq.closie.data.ocr.OcrEngine
import com.qq.closie.life.data.LifeContainer
import com.qq.closie.life.finance.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlin.coroutines.resume
import kotlin.math.roundToInt

/** One consent, one virtual display, one frame; no recording, file or retained projection token. */
class FinanceProjectionService:Service() {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private var task:Job?=null
    private var projection:MediaProjection?=null
    private var display:VirtualDisplay?=null
    private var reader:ImageReader?=null
    private var captured=false
    private var owned:FinanceProjectionRequest?=null
    private var handedToEditor=false
    private var screenReceiver:BroadcastReceiver?=null
    override fun onBind(intent:Intent?)=null
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        if(task!=null){stopSelf(startId);return START_NOT_STICKY}
        val request=FinanceProjectionRequests.pending
        if(request?.id==intent?.getStringExtra("request")){FinanceProjectionRequests.pending=null;owned=request}
        @Suppress("DEPRECATION") val consent=if(Build.VERSION.SDK_INT>=33)intent?.getParcelableExtra("consent",Intent::class.java)
            else intent?.getParcelableExtra<Intent>("consent")
        if(request==null || consent==null || request.id!=intent?.getStringExtra("request") || !request.current(this)) {
            owned?.let {it.host?.close();it.review?.close();com.qq.closie.ui.lifeos.finance.FinanceReceiptOverlay.endCapture(it.reservation)}
            owned?.let {FinanceProjectionRequests.finish(it.reservation)};stopSelf();return START_NOT_STICKY
        }
        try {
        val manager=getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("finance_once_capture","财务单次截图",NotificationManager.IMPORTANCE_LOW))
        val notification=NotificationCompat.Builder(this,"finance_once_capture").setSmallIcon(R.drawable.ic_finance_review)
            .setContentTitle("正在完成本次截图识别").setContentText("一次采集后立即结束，不会自动记账").setOngoing(true).build()
            if(Build.VERSION.SDK_INT>=29)startForeground(604,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            else startForeground(604,notification)
        } catch(_:Exception) {
            request.cancel();FinanceProjectionRequests.finish(request.reservation)
            request.host?.close();com.qq.closie.ui.lifeos.finance.FinanceReceiptOverlay.endCapture(request.reservation)
            FinanceAutomationSettings(this).status("本次系统截图授权或前台服务权限已失效，请重新授权")
            stopSelf();return START_NOT_STICKY
        }
        screenReceiver=object:BroadcastReceiver(){override fun onReceive(context:Context?,intent:Intent?){request.cancel();task?.cancel()}}.also {
            if(Build.VERSION.SDK_INT>=33)registerReceiver(it,IntentFilter(Intent.ACTION_SCREEN_OFF),Context.RECEIVER_NOT_EXPORTED)
            else registerReceiver(it,IntentFilter(Intent.ACTION_SCREEN_OFF))
        }
        task=scope.launch {
            try {
                val token=request.token // Never obtain a new token after consent, frame or OCR.
                request.host?.awaitReturn()
                request.review?.awaitReady()
                check(request.current(this@FinanceProjectionService))
                val mp=getSystemService(MediaProjectionManager::class.java).getMediaProjection(Activity.RESULT_OK,consent)
                projection=mp
                mp.registerCallback(object:MediaProjection.Callback() {
                    override fun onStop(){if(!captured)task?.cancel()}
                    override fun onCapturedContentVisibilityChanged(visible:Boolean){if(!visible && !captured)task?.cancel()}
                    override fun onCapturedContentResize(width:Int,height:Int){if(!request.current(this@FinanceProjectionService))task?.cancel()}
                },Handler(Looper.getMainLooper()))
                val frame=withTimeout(6000){frame(mp,request)}
                var handedToOcr=false
                val raw=try {
                    check(request.current(this@FinanceProjectionService))
                    captured=true;releaseProjection();request.review?.recognizing()
                    withTimeout(12000){handedToOcr=true;OcrEngine.recognizeText(frame,releaseAfterRecognition=true).getOrThrow()}
                } finally {if(!handedToOcr)frame.recycle()}
                val fields=withContext(Dispatchers.Default){FinanceLocalOcrParser.parse(raw)}
                check(request.current(this@FinanceProjectionService))
                check(request.host?.captureCurrent()!=false)
                check(request.review?.captureCurrent()!=false)
                val container=LifeContainer.getInstance(this@FinanceProjectionService)
                val id=withContext(Dispatchers.IO){container.financeRepository.media.createScreenshotCandidate(token,fields){
                    request.current(this@FinanceProjectionService) && request.host?.captureCurrent()!=false && request.review?.captureCurrent()!=false
                }}
                val appContext=applicationContext
                handedToEditor=if(request.host!=null)request.host.present(id,token.restoreEpoch){request.current(appContext)}
                    else request.review?.present(id,token.restoreEpoch){request.current(appContext)}==true
                if(!handedToEditor) {
                    val proposal=container.financeAutomationRepository.observe().first().proposals.single {it.id==id}
                    val notified=FinanceReviewNotifications.post(this@FinanceProjectionService,proposal,true)
                    if(!notified)android.widget.Toast.makeText(this@FinanceProjectionService,"小票已放入账目待确认，请打开核对",android.widget.Toast.LENGTH_LONG).show()
                }
                FinanceAutomationSettings(this@FinanceProjectionService).status(if(handedToEditor)"单次截图已结束，请在小票核对后保存" else "小票已在待确认中，本次复核页面已关闭")
            } catch(e:TimeoutCancellationException){FinanceAutomationSettings(this@FinanceProjectionService).status("本次截图或识别超时，未自动记账")}
            catch(e:CancellationException){FinanceAutomationSettings(this@FinanceProjectionService).status("本次截图已失效，未自动记账");throw e}
            catch(e:Exception){FinanceAutomationSettings(this@FinanceProjectionService).status(e.message ?: "本次系统截图未完成")}
            finally {request.onCancel=null;if(!handedToEditor){request.host?.close();request.review?.close()};FinanceProjectionRequests.finish(request.reservation);com.qq.closie.ui.lifeos.finance.FinanceReceiptOverlay.endCapture(request.reservation);FinanceProjectionBubbleService.ensure(this@FinanceProjectionService);releaseProjection();stopForeground(STOP_FOREGROUND_REMOVE);stopSelf()}
        }
        request.onCancel={task?.cancel()}
        return START_NOT_STICKY
    }
    private suspend fun frame(mp:MediaProjection,request:FinanceProjectionRequest):Bitmap=suspendCancellableCoroutine { continuation ->
        val wm=getSystemService(WindowManager::class.java)
        val bounds=if(Build.VERSION.SDK_INT>=30)wm.maximumWindowMetrics.bounds
            else android.util.DisplayMetrics().let { @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(it);Rect(0,0,it.widthPixels,it.heightPixels)}
        val ratio=minOf(1f,2048f/maxOf(bounds.width(),bounds.height()))
        val width=(bounds.width()*ratio).roundToInt().coerceAtLeast(1)
        val height=(bounds.height()*ratio).roundToInt().coerceAtLeast(1)
        val images=ImageReader.newInstance(width,height,PixelFormat.RGBA_8888,2);reader=images
        images.setOnImageAvailableListener({ source ->
            val image=source.acquireLatestImage()
            if(image!=null)image.use {
                if(!continuation.isActive)return@setOnImageAvailableListener
                var padded:Bitmap?=null;var cropped:Bitmap?=null
                try {
                    check(request.current(this))
                    check(request.host?.captureCurrent()!=false && request.review?.captureCurrent()!=false)
                    val plane=image.planes[0];val paddedWidth=plane.rowStride/plane.pixelStride
                    padded=Bitmap.createBitmap(paddedWidth,height,Bitmap.Config.ARGB_8888)
                    padded!!.copyPixelsFromBuffer(plane.buffer)
                    cropped=Bitmap.createBitmap(padded!!,0,0,width,height)
                    if(cropped===padded)padded=null
                    var visible=0
                    for(x in 0 until 10)for(y in 0 until 10){
                        val color=cropped!!.getPixel(x*(width-1)/9,y*(height-1)/9)
                        if(Color.red(color)>12 || Color.green(color)>12 || Color.blue(color)>12)visible++
                    }
                    check(visible>=3){"页面受保护或无可识别画面，请手动录入"}
                    captured=true;images.setOnImageAvailableListener(null,null)
                    val result=requireNotNull(cropped);cropped=null
                    @OptIn(ExperimentalCoroutinesApi::class)
                    continuation.resume(result){result.recycle()}
                } catch(e:Exception){cropped?.recycle();if(continuation.isActive)continuation.resumeWith(Result.failure(e))}
                finally {padded?.recycle()}
            }
        },Handler(Looper.getMainLooper()))
        display=mp.createVirtualDisplay("FinanceSingleFrame",width,height,resources.configuration.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,images.surface,null,null)
        continuation.invokeOnCancellation {releaseProjection()}
    }
    private fun releaseProjection() {
        reader?.setOnImageAvailableListener(null,null);display?.release();display=null;reader?.close();reader=null
        val previous=projection;projection=null;previous?.stop()
    }
    override fun onConfigurationChanged(config:android.content.res.Configuration){super.onConfigurationChanged(config);task?.cancel()}
    override fun onDestroy(){owned?.let {it.onCancel=null;if(!handedToEditor){it.host?.close();it.review?.close()};com.qq.closie.ui.lifeos.finance.FinanceReceiptOverlay.endCapture(it.reservation);FinanceProjectionRequests.finish(it.reservation)};FinanceProjectionBubbleService.ensure(this);screenReceiver?.let {runCatching {unregisterReceiver(it)}};screenReceiver=null;scope.cancel();releaseProjection();super.onDestroy()}
}
