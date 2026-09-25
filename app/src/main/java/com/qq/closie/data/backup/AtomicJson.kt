package com.qq.closie.data.backup

import android.util.AtomicFile
import com.google.gson.Gson
import java.io.File
import java.io.FileNotFoundException
import java.io.OutputStreamWriter

/**
 * The one atomic-JSON read/write helper in the restore protocol.
 *
 * Used for exactly two pieces of durable state: the restore intent marker and the pre-restore
 * Life OS database snapshot. Building it on [AtomicFile] means a crash mid-write can never leave a
 * half-written file that a later reader would mistake for "nothing to recover".
 *
 * ### Why the write path is shaped the way it is
 *
 * [AtomicFile.startWrite] hands back a `FileOutputStream` whose lifecycle belongs to [AtomicFile]:
 * the commit is [AtomicFile.finishWrite] and the abort is [AtomicFile.failWrite], and *both of those
 * close the stream themselves*. The tempting shape
 *
 * ```kotlin
 * val out = af.startWrite()
 * out.bufferedWriter().use { it.write(json) }   // <-- WRONG
 * af.finishWrite(out)
 * ```
 *
 * closes the stream early (the `use` block closes the writer, which closes the stream underneath),
 * so `finishWrite` is then operating on an already-closed stream at exactly the moment it is supposed
 * to make the write durable. The whole point of [AtomicFile] is the rename-on-finish commit; a closed
 * stream turns that commit into best-effort behaviour, which is the one property this file exists to
 * provide. So: write through an [OutputStreamWriter], `flush()` it, and let `finishWrite`/`failWrite`
 * close everything.
 */
internal object AtomicJson {

    // Not `private`: it is read from the `inline` reified [read] below, whose body is inlined at call
    // sites — Kotlin only allows that for declarations whose effective visibility is not narrower than
    // the inline function's, and this object is internal, not public.
    internal val gson = Gson()

    /**
     * The outcome of reading a durable JSON file.
     *
     * Deliberately three-valued, because the three cases demand three different behaviours and the
     * previous `T?` return collapsed two of them:
     *
     *  - [Missing]  — there is genuinely no marker. This is the *only* input that may be read as
     *    "nothing to recover", and therefore the only one that may be treated as a clean slate.
     *  - [Success]  — a valid record; act on it.
     *  - [Corrupt]  — something is on disk and it cannot be interpreted. A corrupt marker is **not**
     *    "no restore": it is the user's only record of an interrupted restore, and deleting it (or
     *    ignoring it and letting the app open the Closet anyway) is how a half-restored install
     *    becomes an unrecoverable one. Recovery must keep the evidence and retry.
     */
    sealed interface ReadResult<out T> {
        /** No file at all. Safe to treat as "nothing to recover". */
        data object Missing : ReadResult<Nothing>

        /** The file is present and parsed. */
        data class Success<T>(val value: T) : ReadResult<T>

        /** The file is present but unreadable or unparseable. Recovery must preserve it and retry. */
        data class Corrupt(val cause: Throwable) : ReadResult<Nothing>
    }

    /**
     * Writes [value] as JSON, atomically.
     *
     * The JSON is materialised *before* the stream is opened, so a serialisation failure cannot leave
     * a started (and therefore half-committed) `AtomicFile` write behind.
     */
    fun <T> write(file: File, value: T) {
        val json = gson.toJson(value)
        val af = AtomicFile(file)
        val out = af.startWrite()
        try {
            // No `use`: closing this writer early would close `out`, and `finishWrite` below is what
            // performs the durable commit. See the class comment.
            val writer = OutputStreamWriter(out, Charsets.UTF_8)
            writer.write(json)
            writer.flush()
            af.finishWrite(out)
        } catch (t: Throwable) {
            af.failWrite(out)
            throw t
        }
    }

    /**
     * Reads and parses the file, reporting [ReadResult.Corrupt] rather than throwing, so every caller
     * is forced to decide what a corrupt record means instead of letting it fall out of a `try`.
     *
     * Absence is detected by [AtomicFile.readFully] itself, which throws `FileNotFoundException` when
     * there is nothing to read — mapped to [ReadResult.Missing] below. There is deliberately **no**
     * `File.exists()` pre-check: `AtomicFile` owns its own recovery-file semantics (it consults the
     * `.new` sidecar it writes), so a plain existence probe would both duplicate the decision and get
     * it wrong, and a `File.exists()` result is stale the moment it is returned anyway. The distinction
     * that matters is preserved by catching precisely one exception type, so anything else — including
     * an unreadable or truncated marker — is still reported as [ReadResult.Corrupt].
     */
    inline fun <reified T> read(file: File): ReadResult<T> {
        val bytes = try {
            AtomicFile(file).readFully()
        } catch (e: FileNotFoundException) {
            return ReadResult.Missing
        } catch (e: Throwable) {
            return ReadResult.Corrupt(e)
        }
        return try {
            val parsed = gson.fromJson(String(bytes, Charsets.UTF_8), T::class.java)
                ?: return ReadResult.Corrupt(IllegalStateException("文件内容为空: ${file.absolutePath}"))
            ReadResult.Success(parsed)
        } catch (e: Throwable) {
            ReadResult.Corrupt(e)
        }
    }

    /**
     * Deletes the file if present.
     *
     * Unlike [write] this is not "atomic" in any meaningful sense — deletion is a single unlink — so it
     * simply reports success. Only the *ordinary* path uses this: recovery never clears durable evidence
     * through here, because it must be able to tell whether the delete actually happened (it goes
     * through `RestoreFs.cleanupChecked`, which re-reads the filesystem and throws on failure).
     */
    fun clear(file: File) {
        runCatching { AtomicFile(file).delete() }
    }
}
