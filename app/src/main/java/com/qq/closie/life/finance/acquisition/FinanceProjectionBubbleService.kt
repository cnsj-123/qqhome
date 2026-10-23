package com.qq.closie.life.finance.acquisition

import android.app.*
import android.content.*
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.*
import android.provider.Settings
import android.view.*
import android.widget.TextView
import com.qq.closie.data.backup.RestoreStartupGate
import kotlinx.coroutines.*

/** Visible entry only, owns no projection. Android may stop it; reopening settings recreates it. */
class FinanceProjectionBubbleService:Service() {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private val windows by lazy {getSystemService(WindowManager::class.java)}
    private var bubble:View?=null
    override fun onBind(intent:Intent?)=null
    override fun onCreate() {
        super.onCreate()
        scope.launch {while(isActive){
            val native=Build.VERSION.SDK_INT>=30 && VisibleDetailRuntime.state.value.connected
            val permitted=!FinanceProjectionRequests.inFlight && Settings.canDrawOverlays(this@FinanceProjectionBubbleService) &&
                !getSystemService(KeyguardManager::class.java).isKeyguardLocked && RestoreStartupGate.isReady
            if(!FinanceScreenshotPreference.enabled(this@FinanceProjectionBubbleService)){stopSelf();break}
            if(native || !permitted)removeBubble() else showBubble()
            delay(500)
        }}
    }
    private fun showBubble() {
        if(bubble!=null)return
        val density=resources.displayMetrics.density
        val view=TextView(this).apply {
            text="截图\n授权";contentDescription="单次财务截图，打开系统授权"
            gravity=Gravity.CENTER;setTextColor(Color.parseColor("#534C91"));textSize=12f
            background=android.graphics.drawable.GradientDrawable().apply {setColor(Color.parseColor("#F6F5F9"));cornerRadius=20*density}
            setOnClickListener {
                removeBubble()
                runCatching {startActivity(FinanceProjectionRequests.intent(this@FinanceProjectionBubbleService))}
            }
        }
        val params=WindowManager.LayoutParams((52*density).toInt(),(52*density).toInt(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,PixelFormat.TRANSLUCENT)
            .apply {gravity=Gravity.END or Gravity.CENTER_VERTICAL}
        runCatching {windows.addView(view,params);bubble=view}.onFailure {stopSelf()}
    }
    private fun removeBubble(){bubble?.let {runCatching {windows.removeViewImmediate(it)}};bubble=null}
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int)=START_NOT_STICKY
    override fun onDestroy(){scope.cancel();removeBubble();super.onDestroy()}
    companion object {
        fun ensure(context:Context) {
            if(FinanceScreenshotPreference.enabled(context) && Settings.canDrawOverlays(context))
                runCatching {context.startService(Intent(context,FinanceProjectionBubbleService::class.java))}
        }
    }
}
