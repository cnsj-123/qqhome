package com.xiaoming.closie.ui.quickcapture

import android.content.Context

/**
 * Cleans up stale quick-capture screenshots (cacheDir/capture_*.png) that no longer have an owner.
 * Called once at app startup (after a process kill, where onDestroy never ran and PendingProduct
 * is already gone) and at the start of every capture session. It never touches other cache files.
 */
object QuickCaptureTempFiles {
    fun cleanupStale(context: Context) {
        runCatching {
            context.cacheDir.listFiles()
                ?.filter { it.name.startsWith("capture_") && it.name.endsWith(".png") }
                ?.forEach { it.delete() }
        }
    }
}
