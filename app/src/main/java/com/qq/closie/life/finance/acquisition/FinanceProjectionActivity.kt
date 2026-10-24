package com.qq.closie.life.finance.acquisition

import android.app.Activity
import android.content.*
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.qq.closie.data.backup.RestoreStartupGate
import com.qq.closie.life.data.LifeContainer
import com.qq.closie.life.finance.*
import kotlinx.coroutines.*

/** Consent is single-use and never saved to disk or reused after Activity recreation. */
class FinanceProjectionActivity:ComponentActivity() {
    private var ownsRequest=false
    private val captureOwner=Any()
    private var origin:FinanceProjectionHost?=null
    private var handedToService=false
    private var pending:FinanceProjectionRequest?=null
    private var receiverRegistered=false
    private var screenWasLocked=false
    private val screenReceiver=object:BroadcastReceiver(){override fun onReceive(context:Context?,intent:Intent?){
        screenWasLocked=true;pending?.cancel();FinanceProjectionRequests.pending?.takeIf {it.reservation===captureOwner}?.cancel();finish()
    }}
    private val consent=registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val request=pending;pending=null
        if(result.resultCode==Activity.RESULT_OK && result.data!=null && request!=null && request.current(this)) {
            handedToService=true
            FinanceProjectionRequests.pending=request
            runCatching {startForegroundService(Intent(this,FinanceProjectionService::class.java)
                .putExtra("consent",result.data).putExtra("request",request.id))}
                .onFailure {handedToService=false;FinanceProjectionRequests.pending=null;FinanceProjectionRequests.finish(captureOwner);FinanceAutomationSettings(this).status("系统截图未启动，请重新授权")}
        } else FinanceAutomationSettings(this).status("本次截图授权已取消或失效，未创建账目")
        if(!handedToService)FinanceProjectionRequests.finish(captureOwner)
        finish()
    }
    override fun onCreate(state:Bundle?) {
        super.onCreate(state)
        if(state!=null || FinanceProjectionRequests.inFlight){finish();return}
        origin=FinanceProjectionHosts.take(intent.getStringExtra("originId"))
        if(!com.qq.closie.ui.lifeos.finance.FinanceReceiptOverlay.beginCapture(captureOwner)){origin?.close();finish();return}
        ownsRequest=true;FinanceProjectionRequests.start(captureOwner);FinanceProjectionBubbleService.ensure(this)
        if(android.os.Build.VERSION.SDK_INT>=33)registerReceiver(screenReceiver,IntentFilter(Intent.ACTION_SCREEN_OFF),Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(screenReceiver,IntentFilter(Intent.ACTION_SCREEN_OFF))
        receiverRegistered=true
        lifecycleScope.launch {
            try {
                val fence=RestoreStartupGate.state.value
                if(intent.getBooleanExtra("freshEntry",false))intent.putExtra("restoreGeneration",fence.restoreGeneration)
                check(fence.restoreGeneration==intent.getLongExtra("restoreGeneration",-1) && fence.canAcquireBusinessLease && FinanceScreenshotPreference.enabled(this@FinanceProjectionActivity))
                val preferenceGeneration=FinanceScreenshotPreference.generation
                val token=withContext(Dispatchers.IO){LifeContainer.getInstance(this@FinanceProjectionActivity).financeRepository.economics.token()}
                check(preferenceGeneration==FinanceScreenshotPreference.generation)
                val request=FinanceProjectionRequest(java.util.UUID.randomUUID().toString(),token,fence.restoreGeneration,resources.configuration.orientation,origin,captureOwner)
                check(!screenWasLocked && request.current(this@FinanceProjectionActivity))
                pending=request
                val manager=getSystemService(MediaProjectionManager::class.java)
                // A host-bound capture must not let app sharing select a different application's pixels.
                val captureIntent=if(android.os.Build.VERSION.SDK_INT>=34 && origin!=null)
                    manager.createScreenCaptureIntent(android.media.projection.MediaProjectionConfig.createConfigForDefaultDisplay())
                    else manager.createScreenCaptureIntent()
                consent.launch(captureIntent)
            } catch(e:CancellationException){throw e}
            catch(_:Exception){FinanceAutomationSettings(this@FinanceProjectionActivity).status("恢复、锁屏或权限状态不允许截图");finish()}
        }
    }
    override fun onDestroy(){
        if(receiverRegistered){runCatching {unregisterReceiver(screenReceiver)};receiverRegistered=false}
        if(ownsRequest && !handedToService){origin?.close();FinanceProjectionRequests.finish(captureOwner);com.qq.closie.ui.lifeos.finance.FinanceReceiptOverlay.endCapture(captureOwner)}
        FinanceProjectionBubbleService.ensure(this)
        super.onDestroy()
    }
}
internal data class FinanceProjectionRequest(val id:String,val token:FinanceCommandToken,val restoreGeneration:Long,val orientation:Int,
    val host:FinanceProjectionHost?=null,val reservation:Any=Any()) {
    private val preferenceGeneration=FinanceScreenshotPreference.generation
    private val cancelled=java.util.concurrent.atomic.AtomicBoolean(false)
    fun cancel(){cancelled.set(true)}
    fun current(context:Context)=!cancelled.get() && host?.live!=false && preferenceGeneration==FinanceScreenshotPreference.generation && RestoreStartupGate.isReady &&
        RestoreStartupGate.state.value.restoreGeneration==restoreGeneration &&
        context.resources.configuration.orientation==orientation && FinanceScreenshotPreference.enabled(context) &&
        !context.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked
}
internal object FinanceProjectionRequests {
    var pending:FinanceProjectionRequest?=null
    @Volatile var inFlight=false
    private var owner:Any?=null
    fun start(requestOwner:Any){owner=requestOwner;inFlight=true}
    fun finish(requestOwner:Any){if(owner===requestOwner){owner=null;inFlight=false}}
    fun intent(context:Context)=Intent(context,FinanceProjectionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        .putExtra("restoreGeneration",RestoreStartupGate.state.value.restoreGeneration)
}
