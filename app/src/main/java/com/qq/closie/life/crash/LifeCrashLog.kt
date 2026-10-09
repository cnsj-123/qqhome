package com.qq.closie.life.crash

import android.content.Context
import android.os.Build
import android.os.Looper
import com.qq.closie.BuildConfig
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Debug-only local crash log — the v0.2.0 "真机稳定性" floor.
 *
 * Why: v0.1 shipped with zero crash visibility. CI passing does not prove a vivo build behaves,
 * so when a real device throws we need the stack *before* we can fix it. This writes one file,
 * `filesDir/debug/last_crash.txt`, on the next handler in the chain — nothing leaves the device.
 *
 * Hard rules (all deliberate):
 *  - [BuildConfig.DEBUG] only. The install call itself is guarded, and [install] re-checks, so a
 *    release build can never grow this handler even if a future call site forgets the guard.
 *  - We never *replace and drop* the system handler: after writing we always forward to
 *    [previous], so the normal crash flow (dialog, ActivityManager record, process death)
 *    still happens exactly as before.
 *  - Re-install is a no-op (checked by handler type), so a redundant [install] cannot stack
 *    two writers and double-write one file.
 *  - Failures inside the writer are swallowed — throwing from an uncaught-exception handler
 *    would lose the original crash.
 *  - No third-party SDK, no network, no business content — only time / OS / app version /
 *    thread / exception class + message / full stack trace.
 */
object LifeCrashLog {

    private const val DIR_NAME = "debug"
    private const val FILE_NAME = "last_crash.txt"

    /** Crash time formatter — fixed pattern, device locale-independent output. */
    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    /** Where the log lives. Kept public so the Profile debug rows can read/clear the same file. */
    fun file(context: Context): File =
        File(File(context.applicationContext.filesDir, DIR_NAME), FILE_NAME)

    /** The last crash, or null when there is none / the file is unreadable. */
    fun read(context: Context): String? =
        file(context)
            .takeIf { it.isFile }
            ?.let { runCatching { it.readText() }.getOrNull() }
            ?.takeIf { it.isNotBlank() }

    fun clear(context: Context) {
        file(context).delete()
    }

    /** Installs the writer chain. Call once from [com.qq.closie.ClosieApplication.onCreate]. */
    fun install(context: Context) {
        // Double guard: even if a release call site forgets its own `if (BuildConfig.DEBUG)`,
        // this object must stay a debug-only citizen.
        if (!BuildConfig.DEBUG) return

        val appContext = context.applicationContext
        val current = Thread.getDefaultUncaughtExceptionHandler()
        if (current is Writer) return // already installed — never stack a second writer

        Thread.setDefaultUncaughtExceptionHandler(Writer(appContext, previous = current))
    }

    private class Writer(
        private val appContext: Context,
        private val previous: Thread.UncaughtExceptionHandler?
    ) : Thread.UncaughtExceptionHandler {

        override fun uncaughtException(thread: Thread, throwable: Throwable) {
            try {
                write(thread, throwable)
            } catch (_: Throwable) {
                // The log is best-effort. Losing it must never mask the real crash.
            } finally {
                // ALWAYS hand the crash back: previous handles the platform's normal death
                // sequence (crash dialog, logcat record). We only added a file write before it.
                previous?.uncaughtException(thread, throwable)
            }
        }

        private fun write(thread: Thread, throwable: Throwable) {
            val out = file(appContext)
            out.parentFile?.mkdirs()

            val stack = StringWriter().also { throwable.printStackTrace(PrintWriter(it)) }
            val version = runCatching {
                appContext.packageManager
                    .getPackageInfo(appContext.packageName, 0)
            }.getOrNull()

            val text = buildString {
                appendLine("Life OS crash log")
                appendLine("Time: ${timeFormat.format(Date())}")
                appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                appendLine(
                    "App: ${version?.versionName ?: "?"} " +
                        "(versionCode ${version?.longVersionCode ?: "?"}, debug)"
                )
                appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
                appendLine("Thread: ${thread.name} (main=${thread == Looper.getMainLooper().thread})")
                appendLine()
                append(stack.toString())
            }

            out.writeText(text) // one file, latest crash wins — "last_crash" by name and nature
        }
    }
}
