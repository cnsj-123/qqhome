package com.xiaoming.closie

import android.app.Application
import com.xiaoming.closie.data.repository.LocalWardrobeRepository
import com.xiaoming.closie.ui.quickcapture.QuickCaptureTempFiles

/**
 * Process-wide owner of the single [LocalWardrobeRepository]. Every screen and background service
 * (quick-capture preview included) resolves its repository through [ClosieApplication], so there is
 * exactly one in-memory source of truth per process. No Hilt, no manual re-instantiation.
 */
class ClosieApplication : Application() {

    val wardrobeRepository by lazy { LocalWardrobeRepository(this) }

    override fun onCreate() {
        super.onCreate()
        // After a process kill the preview's onDestroy never ran; any leftover capture_*.png in
        // cache no longer has an owner (PendingProduct is gone too), so drop them on startup.
        QuickCaptureTempFiles.cleanupStale(this)
    }
}
