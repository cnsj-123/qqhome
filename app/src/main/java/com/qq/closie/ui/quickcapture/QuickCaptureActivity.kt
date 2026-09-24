package com.qq.closie.ui.quickcapture

import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

/**
 * Thin consent gate for a whole quick-capture session: requests the user's screen-capture
 * permission via MediaProjection once and, on grant, hands the token to [QuickCaptureService]
 * which then keeps the session (and the overlay bubble) alive until the user ends it.
 *
 * Android 14+ requires a fresh authorization for each MediaProjection session, but a session now
 * lasts across many bubble taps — the user is **not** re-prompted on every capture.
 */
class QuickCaptureActivity : ComponentActivity() {

    private val consent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        if (result.resultCode == RESULT_OK && data != null) {
            QuickCaptureService.start(this, result.resultCode, data)
        } else {
            // User cancelled consent — fully clear the toggle and the in-process starting flag so
            // Settings never shows ON.
            QuickCaptureService.clearSessionStarting()
            QuickCaptureService.setEnabled(this, false)
            QuickCaptureService.setRunning(this, false)
            QuickCaptureService.setPendingConsent(this, false)
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        consent.launch(manager.createScreenCaptureIntent())
    }
}
