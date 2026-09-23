package com.qq.closie

import android.app.Application
import com.qq.closie.data.repository.LocalWardrobeRepository
import com.qq.closie.life.data.LifeContainer
import com.qq.closie.ui.quickcapture.QuickCaptureTempFiles

/**
 * Process-wide owner of the single [LocalWardrobeRepository] and the single [LifeContainer].
 * Every screen and background service (quick-capture preview included) resolves its repository
 * through [ClosieApplication], so there is exactly one in-memory source of truth per process.
 * No Hilt, no manual re-instantiation.
 */
class ClosieApplication : Application() {

    val wardrobeRepository by lazy { LocalWardrobeRepository(this) }

    /** Life OS layer: Room database + repositories. Opened lazily on first access. */
    val lifeContainer by lazy { LifeContainer.getInstance(this) }

    override fun onCreate() {
        super.onCreate()
        // After a process kill the preview's onDestroy never ran; any leftover capture_*.png in
        // cache no longer has an owner (PendingProduct is gone too), so drop them on startup.
        QuickCaptureTempFiles.cleanupStale(this)
    }
}
