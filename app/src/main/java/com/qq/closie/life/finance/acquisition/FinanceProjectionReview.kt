package com.qq.closie.life.finance.acquisition

import android.view.Gravity
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.qq.closie.ui.lifeos.finance.FinanceReceiptOverlay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout

/** No accessibility fallback: the user's transparent Activity owns the paper, never a late overlay.
 * Its window blocks interaction with the backdrop. Leaving this Activity terminates acquisition. */
internal class FinanceProjectionReview(private val activity:ComponentActivity,
    private val paperFactory:((FrameLayout,()->Unit)->FinanceReceiptOverlay)?=null) {
    private var closed=false
    var consentPending=false
    var onTerminate:()->Unit={}
    private var paper:FinanceReceiptOverlay?=null
    private val root=FrameLayout(activity).apply {setOnClickListener {close()}}
    val live:Boolean get()=!closed && !activity.isFinishing && !activity.isDestroyed
    fun captureCurrent()=live && activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
    init {
        activity.setContentView(root)
        activity.lifecycle.addObserver(object:DefaultLifecycleObserver {
            override fun onStop(owner:LifecycleOwner){if(!consentPending)close()}
            override fun onDestroy(owner:LifecycleOwner){close()}
        })
    }
    suspend fun awaitReady() {
        withTimeout(3000){activity.lifecycle.currentStateFlow.first {check(live);it.isAtLeast(Lifecycle.State.RESUMED)}}
    }
    fun recognizing() {
        if(!live)return
        activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        root.addView(LinearLayout(activity).apply {
            orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER
            addView(ProgressBar(activity));addView(TextView(activity).apply {text="正在本地识别…";gravity=Gravity.CENTER})
        },FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,FrameLayout.LayoutParams.WRAP_CONTENT,Gravity.CENTER))
    }
    suspend fun present(id:String,epoch:Long,current:()->Boolean):Boolean {
        if(!live || !current())return false
        awaitReady();if(!live || !current())return false
        root.removeAllViews()
        activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        val surface=paperFactory?.invoke(root,::close) ?: FinanceReceiptOverlay(activity,activity.lifecycleScope,
            onClosed=::close,embeddedHost=root)
        paper=surface
        val permitted={live && current() && activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)}
        return surface.show(id,true,epoch,canContinue=permitted,canPresent=permitted)
    }
    fun back(){paper?.back() ?: close()}
    fun close() {
        if(closed)return
        closed=true;onTerminate();val surface=paper;paper=null;surface?.close();root.removeAllViews();activity.finish()
    }
}
