package com.qq.closie.life.finance.acquisition

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.qq.closie.R
import com.qq.closie.ui.lifeos.finance.FinanceReceiptOverlay

/** Historical name retained at call sites. This is a notification entry, NOT a background Service.
 * PendingIntent survives the app leaving foreground; no unrelated foreground-service type is claimed.
 * Quick Settings is always the explicit fallback when notification permission/channel is disabled. */
object FinanceProjectionBubbleService {
    private const val CHANNEL="finance_capture_entry"
    private const val ID=606
    fun ensure(context:Context) {
        val manager=context.getSystemService(NotificationManager::class.java)
        if(!FinanceScreenshotPreference.enabled(context) || FinanceReceiptOverlay.occupied ||
            FinanceCaptureEntryState.nativeVisible.value) {manager.cancel(ID);return}
        manager.createNotificationChannel(NotificationChannel(CHANNEL,"财务截图入口",NotificationManager.IMPORTANCE_LOW))
        val intent=FinanceProjectionRequests.intent(context).putExtra("freshEntry",true)
        val action=PendingIntent.getActivity(context,ID,intent,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification=NotificationCompat.Builder(context,CHANNEL).setSmallIcon(R.drawable.ic_finance_review)
            .setContentTitle("财务单次截图").setContentText("点此逐次授权；也可使用快捷设置“财务单次截图”")
            .setOngoing(true).setContentIntent(action).addAction(0,"截图并授权",action).build()
        if(NotificationManagerCompat.from(context).areNotificationsEnabled())runCatching {manager.notify(ID,notification)}
    }
    fun available(context:Context):Boolean {
        val manager=context.getSystemService(NotificationManager::class.java)
        return NotificationManagerCompat.from(context).areNotificationsEnabled() &&
            manager.getNotificationChannel(CHANNEL)?.importance!=NotificationManager.IMPORTANCE_NONE
    }
}
internal object FinanceCaptureEntryState {
    val nativeVisible=kotlinx.coroutines.flow.MutableStateFlow(false)
}
