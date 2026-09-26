package com.qq.closie

import android.app.Application
import com.qq.closie.data.backup.RecoveryOutcome
import com.qq.closie.data.backup.RestoreRecoveryManager
import com.qq.closie.data.backup.RestoreRecoveryPendingException
import com.qq.closie.data.repository.LocalWardrobeRepository
import com.qq.closie.data.repository.WardrobeRepository
import com.qq.closie.life.crash.LifeCrashLog
import com.qq.closie.life.data.LifeContainer
import com.qq.closie.ui.quickcapture.QuickCaptureTempFiles

/**
 * Process-wide owner of the single [LocalWardrobeRepository] and the single [LifeContainer].
 * Every screen and background service (quick-capture preview included) resolves its repository
 * through [ClosieApplication], so there is exactly one in-memory source of truth per process.
 * No Hilt, no manual re-instantiation.
 */
class ClosieApplication : Application() {

    /**
     * The wardrobe repository, gated on restore recovery.
     *
     * ### The startup refusal is handled, not propagated
     *
     * [LocalWardrobeRepository] throws when the recovery gate is blocked, and this property is on a
     * hot path that has **no exception handling anywhere above it**: `MainActivity.onCreate` reads it
     * inside `setContent { … LifeShellNavHost(wardrobeRepository = app.wardrobeRepository …) }`, a
     * `catch (e: Throwable)` is not available inside a composable lambda, and the crash would land in
     * the Compose recomposer rather than anywhere a caller could recover from.
     *
     * That was not theoretical: with the gate BLOCKED, `LocalWardrobeRepository(this)` throws, the
     * exception escapes `onCreate`, and the process dies during startup — the exact failure mode the
     * gate exists to *prevent* (a half-restored Closet must be reported, not crash). The refusal is
     * correct; letting it surface as a stack trace from a property delegate is not.
     *
     * So the constructor failure is converted into the same class of `Throwable` the gate raises and
     * rethrown with a message that names what the user can do about it. It is still thrown — the app
     * must not show a half-restored wardrobe — but the exception now originates at a point where the
     * cause is legible and `LifeCrashLog` (debug builds) records *why*, instead of an anonymous NPE-like
     * failure in the middle of composition.
     *
     * ### Why not return a stub repository instead
     *
     * A stub that reports an empty wardrobe would silently present "you have no clothes" to a user whose
     * data may well be intact on disk, and the first thing they would do is start re-adding items into a
     * tree that a recovery is about to replace. Failing loudly with a legible cause is the honest
     * behaviour, and it is the one the gate's own documentation promises.
     */
    val wardrobeRepository: WardrobeRepository by lazy {
        try {
            LocalWardrobeRepository(this)
        } catch (e: RestoreRecoveryPendingException) {
            // The refusal is preserved, and re-thrown carrying the original as its cause — the gate's
            // own message is machine-facing ("恢复尚未完成…"), and the cause is what keeps it.
            //
            // The declared type is the `WardrobeRepository` interface, not `LocalWardrobeRepository`:
            // the implementation is `internal`, and a public property cannot expose an internal type.
            // Callers only ever need the interface, and the test seams that need the concrete class
            // construct it directly.
            throw RestoreRecoveryPendingException(e)
        }
    }

    /** Life OS layer: Room database + repositories. Opened lazily on first access. */
    val lifeContainer by lazy { LifeContainer.getInstance(this) }

    override fun onCreate() {
        super.onCreate()
        // Debug builds only: local crash journal at filesDir/debug/last_crash.txt. The real
        // device had a crash CI could not see — this is the v0.2.0 floor under that gap.
        // Release builds must never install it (guarded again inside LifeCrashLog.install).
        if (BuildConfig.DEBUG) {
            LifeCrashLog.install(this)
        }
        // After a process kill the preview's onDestroy never ran; any leftover capture_*.png in
        // cache no longer has an owner (PendingProduct is gone too), so drop them on startup.
        QuickCaptureTempFiles.cleanupStale(this)
        // Finish any restore a previous process was killed in the middle of. This runs here, and only
        // here, because the Application is the one component every entry point shares: a restore must
        // not stay half-applied just because the user reopened the app through the quick-capture
        // notification rather than the main screen.
        //
        // This is a **barrier**. When a marker exists, recovery runs to completion — filesystem *and*
        // database — before this returns, and resolves `RestoreStartupGate` accordingly. Nothing may
        // read the user's data until then: the previous design finished the filesystem half
        // synchronously and left the database half to a background coroutine, which left a window in
        // which the Closet and media were on the user's version while the database still held the
        // backup's rows, and in which a later snapshot replay could overwrite a fresh user write.
        //
        // The startup invariants this depends on, recorded so they stay true:
        //
        //  1. Nothing reads `closie/` before this returns. Both the wardrobe repository and the Life
        //     container are `by lazy`, and `onCreate` touches neither — the only mention of
        //     `lifeContainer` is inside the `lifeDatabase` *lambda*, which is not evaluated unless a
        //     marker turns out to exist.
        //  2. An **ordinary launch does not open the database at all**. The lambda is evaluated only
        //     on the marker-present path, so a normal start does not build Room just to discover there
        //     was nothing to recover. Recovery takes the cost only when there is genuinely something to
        //     recover, and then it takes it on `Dispatchers.IO` (replaying a snapshot runs
        //     `database.withTransaction`, and the production database is built without
        //     `allowMainThreadQueries()`).
        //
        // Note the direction of the dependency: recovery needs a `LifeDatabase`, and it gets one via
        // `lifeContainer.lifeDatabase`, which is a raw accessor with no gate on it. The gate is on the
        // *repositories* — so recovery can always obtain the database it needs, while business code
        // cannot obtain repositories until recovery is done. That is what avoids a cycle.
        val outcome = RestoreRecoveryManager.recoverOnStartup(
            context = this,
            lifeDatabase = { lifeContainer.lifeDatabase }
        )
        if (outcome is RecoveryOutcome.RetryRequired) {
            // Deliberately not fatal here. Note `RetryRequired` does **not** by itself mean the gate is
            // blocked — the manager has already decided that, based on whether the marker reached a
            // terminal state. When the durable data is consistent and only cleanup is outstanding the gate
            // is open and this is pure logging; when it is not, `RestoreStartupGate` is blocked and the
            // wardrobe repository refuses to open, surfacing the failure at the point of use with a
            // message that explains what happened, instead of taking down every entry point including
            // quick capture.
            android.util.Log.w(
                "ClosieApplication",
                "上次恢复未全部完成（gate=${if (com.qq.closie.data.backup.RestoreStartupGate.isReady) "READY：数据一致，仅剩清理" else "BLOCKED：数据可能不一致"}）",
                outcome.error
            )
        }
    }
}
