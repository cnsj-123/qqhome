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
    private var handedToService=false
    private var pending:FinanceProjectionRequest?=null
    private val consent=registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val request=pending;pending=null
        if(result.resultCode==Activity.RESULT_OK && result.data!=null && request!=null && request.current(this)) {
            handedToService=true
            FinanceProjectionRequests.pending=request
            runCatching {startForegroundService(Intent(this,FinanceProjectionService::class.java)
                .putExtra("consent",result.data).putExtra("request",request.id))}
                .onFailure {handedToService=false;FinanceProjectionRequests.pending=null;FinanceProjectionRequests.inFlight=false;FinanceAutomationSettings(this).status("系统截图未启动，请重新授权")}
        } else FinanceAutomationSettings(this).status("本次截图授权已取消或失效，未创建账目")
        if(!handedToService)FinanceProjectionRequests.inFlight=false
        finish()
    }
    override fun onCreate(state:Bundle?) {
        super.onCreate(state)
        if(state!=null || FinanceProjectionRequests.inFlight){finish();return}
        if(!com.qq.closie.ui.lifeos.finance.FinanceReceiptOverlay.beginCapture()){finish();return}
        ownsRequest=true;FinanceProjectionRequests.inFlight=true
        lifecycleScope.launch {
            try {
                val fence=RestoreStartupGate.state.value
                check(fence.restoreGeneration==intent.getLongExtra("restoreGeneration",-1) && fence.canAcquireBusinessLease && FinanceScreenshotPreference.enabled(this@FinanceProjectionActivity))
                val token=withContext(Dispatchers.IO){LifeContainer.getInstance(this@FinanceProjectionActivity).financeRepository.economics.token()}
                val request=FinanceProjectionRequest(java.util.UUID.randomUUID().toString(),token,fence.restoreGeneration,resources.configuration.orientation)
                check(request.current(this@FinanceProjectionActivity))
                pending=request
                consent.launch(getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent())
            } catch(e:CancellationException){throw e}
            catch(_:Exception){FinanceAutomationSettings(this@FinanceProjectionActivity).status("恢复、锁屏或权限状态不允许截图");finish()}
        }
    }
    override fun onDestroy(){
        if(ownsRequest && !handedToService){FinanceProjectionRequests.inFlight=false;com.qq.closie.ui.lifeos.finance.FinanceReceiptOverlay.endCapture()}
        super.onDestroy()
    }
}
internal data class FinanceProjectionRequest(val id:String,val token:FinanceCommandToken,val restoreGeneration:Long,val orientation:Int) {
    private val preferenceGeneration=FinanceScreenshotPreference.generation
    private val cancelled=java.util.concurrent.atomic.AtomicBoolean(false)
    fun cancel(){cancelled.set(true)}
    fun current(context:Context)=!cancelled.get() && preferenceGeneration==FinanceScreenshotPreference.generation && RestoreStartupGate.isReady &&
        RestoreStartupGate.state.value.restoreGeneration==restoreGeneration &&
        context.resources.configuration.orientation==orientation && FinanceScreenshotPreference.enabled(context) &&
        !context.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked
}
internal object FinanceProjectionRequests {
    var pending:FinanceProjectionRequest?=null
    @Volatile var inFlight=false
    fun intent(context:Context)=Intent(context,FinanceProjectionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        .putExtra("restoreGeneration",RestoreStartupGate.state.value.restoreGeneration)
}
