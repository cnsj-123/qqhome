package com.qq.closie.life.finance.acquisition

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.TileService

/** Explicit external-page entry also works when Accessibility is disabled. */
class FinanceCaptureTileService:TileService() {
    override fun onClick() {
        super.onClick()
        if(isLocked){unlockAndRun {launchConsent()}}else launchConsent()
    }
    private fun launchConsent() {
        if(!FinanceScreenshotPreference.enabled(this)) {
            com.qq.closie.life.finance.FinanceAutomationSettings(this).status("请先在账目自动发现中开启主动截图")
            return
        }
        val intent=FinanceProjectionRequests.intent(this)
        if(Build.VERSION.SDK_INT>=34)startActivityAndCollapse(PendingIntent.getActivity(this,605,intent,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        else { @Suppress("DEPRECATION") startActivityAndCollapse(intent) }
    }
}
