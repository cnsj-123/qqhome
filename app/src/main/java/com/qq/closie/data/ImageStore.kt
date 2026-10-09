package com.qq.closie.data

import android.content.Context
import android.net.Uri
import com.qq.closie.data.backup.RestoreStartupGate
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * Copies selected bytes into app-private storage, retaining PNG/WebP alpha and surviving URI
 * permission loss/restarts. Writes go to a temporary file first and are atomically renamed only
 * after a complete, non-empty copy, so a failure never leaves a half-written file behind.
 *
 * ### Why this object owns a business lease
 *
 * Everything here lands under `filesDir/closie/images/…`, and `closie/` is exactly the directory a
 * restore replaces **wholesale** — `RestoreCoordinator` renames the live `closie/` aside and moves a
 * staged generation into its place. A copy that starts before the swap and finishes after it therefore
 * does not write into the wardrobe it read: it writes into a directory that has been moved to
 * `.closie_restore_old_<id>/` and replaced, so the file lands in a generation nobody will ever read, or
 * in a tree the restore is about to delete. Either way the user's photo silently vanishes and the
 * `ClothingImage.localPath` persisted by the caller points at a file that is not in the live tree.
 *
 * The same race runs the other way for [deletePrivatePath]: a delete that resolves its path before the
 * swap and unlinks after it is removing a path in the *old* generation, so the restored image the user
 * just got back is deleted by a stale caller.
 *
 * So every method that touches `closie/images` holds [RestoreStartupGate.withBusinessAccess] /
 * [RestoreStartupGate.withBusinessAccessSuspending] for the whole of the operation that names a live
 * path. While the lease is held `beginRestore()` refuses, and while a restore owns the gate no lease can
 * be taken — the two can never overlap, which is the only form of correctness available here given that
 * a restore swaps the directory rather than cooperating with its writers.
 *
 * ### Why here, and not at each call site
 *
 * The alternative — sprinkling `RestoreStartupGate.requireReady()` through `EditorScreen`,
 * `OotdScreen`, `OutfitStudioScreen`, `CapturePreviewActivity`, … — is both larger and weaker: every new
 * call site is a fresh chance to forget, and a bare check would still leave the copy itself straddling
 * the swap. Collecting the lease in the *owner of the storage* means the existing callers become safe
 * without being touched, and the invariant is stated once, in the file that knows the path layout.
 *
 * ### Consequence, accepted deliberately
 *
 * While an image copy or delete is in flight, a user-initiated restore is refused (reported to the user
 * as a restore that could not start because the app is busy) rather than allowed to proceed and lose or
 * resurrect an image. These operations are short — one local file copy, or one bounded download — so the
 * refusal window is small, and it is the correct direction to fail in.
 *
 * ### What a refusal looks like to a caller, per method
 *
 * The lease refuses by throwing [com.qq.closie.data.backup.RestoreRecoveryPendingException], and this
 * object is careful about **where** that exception surfaces, because these methods are called from system
 * callbacks (`ActivityResultLauncher`, quick capture) with no exception handling anywhere above them:
 *
 * | method | old contract | on a restore in progress |
 * |---|---|---|
 * | `copyFromUri` / `copyOotdFromUri` / `copyOutfitFromUri` / `copyFromFile` / `copyFromUrl` | `String?`, `null` on any failure | **`null`**, and nothing published |
 * | `deletePrivatePath` | `Unit` | **no-op**, and nothing deleted |
 *
 * In both cases the gate is still honoured — no write and no delete touches `closie/` while a restore
 * owns it — but the refusal is reported in the shape the method already used. A *new* throwing contract
 * would have converted a routine refusal into an unrelated crash, which is a worse outcome than a silent
 * no-op for an operation whose failure already meant "nothing happened".
 */
object ImageStore {
    private fun imagesRoot(context: Context) = File(context.filesDir, "closie/images")
    private fun itemsDir(context: Context) = imagesRoot(context)
    private fun ootdDir(context: Context) = File(context.filesDir, "closie/images/ootd")
    private fun outfitDir(context: Context) = File(context.filesDir, "closie/images/outfit")

    private fun extensionFor(type: String?): String = when {
        type?.contains("png") == true -> "png"
        type?.contains("webp") == true -> "webp"
        type?.contains("jpeg") == true || type?.contains("jpg") == true -> "jpg"
        else -> "bin"
    }

    /**
     * The local half of a copy: writes an already-available byte source into [dir] under a fresh id.
     *
     * Ungated on purpose — every *caller* holds the lease. It is private, so the only callers are the
     * gated methods below, and gating it again would nest leases for no gain (a nested lease is a counter
     * increment, not a new guarantee).
     */
    private fun publishInto(dir: File, ext: String, write: (tmp: File) -> Unit): String? = runCatching {
        dir.mkdirs()
        val id = UUID.randomUUID().toString()
        val tmp = File(dir, ".$id.tmp")
        val final = File(dir, "$id.$ext")
        try {
            write(tmp)
            if (!tmp.exists() || tmp.length() == 0L) throw IllegalStateException("图片为空")
            if (!tmp.renameTo(final)) throw IllegalStateException("图片保存失败")
            final.absolutePath
        } catch (e: Exception) {
            tmp.delete()
            final.delete()
            throw e
        }
    }.getOrNull()

    /**
     * The three local-copy entry points, all of which keep the **original nullable contract**.
     *
     * ### Why the gate refusal is swallowed into `null` here and not allowed to throw
     *
     * These methods used to report every failure as `null`, and every caller in the app is written
     * against that: `ImageStore.copyFromUri(context, uri)?.let { path -> … }`, in an
     * `ActivityResultLauncher` callback and in a quick-capture preview. Introducing a *throwing* gate
     * refusal would have turned "the picker returned while a restore was running" into an uncaught
     * exception inside a system callback — a crash, where the previous behaviour was a silent no-op.
     * That is exactly the sort of incidental contract change a restore fix must not cause.
     *
     * So the refusal is folded into the existing vocabulary: **the copy is refused and `null` is
     * returned**, meaning "no image was written", which is already a value every caller handles. Note
     * what is *not* relaxed: nothing is published into `filesDir/closie` while a restore owns the gate,
     * because the lease is still taken and still throws inside the `runCatching`. The refusal is not
     * removed, only reported in the shape this API already uses.
     */
    fun copyFromUri(context: Context, uri: Uri): String? = copyFromUriTo(context, uri, itemsDir(context))
    fun copyOotdFromUri(context: Context, uri: Uri): String? = copyFromUriTo(context, uri, ootdDir(context))
    fun copyOutfitFromUri(context: Context, uri: Uri): String? = copyFromUriTo(context, uri, outfitDir(context))

    private fun copyFromUriTo(context: Context, uri: Uri, dir: File): String? =
        runCatching {
            RestoreStartupGate.withBusinessAccess {
                publishInto(dir, extensionFor(context.contentResolver.getType(uri))) { tmp ->
                    val input = context.contentResolver.openInputStream(uri)
                        ?: throw IllegalStateException("无法读取图片")
                    input.use { source -> tmp.outputStream().use { target -> source.copyTo(target) } }
                }
            }
        }.getOrNull()

    /** Copies an existing local file (e.g. a just-captured screenshot) into private item storage. */
    fun copyFromFile(context: Context, file: File): String? {
        if (!file.exists() || !file.isFile || file.length() == 0L) return null
        // The lease starts after the cheap checks and before the first touch of `closie/`, and the
        // refusal inside it is reported as `null` — see [copyFromUri].
        return runCatching {
            RestoreStartupGate.withBusinessAccess {
                publishInto(itemsDir(context), file.extension.ifBlank { "bin" }) { tmp ->
                    file.copyTo(tmp, overwrite = true)
                }
            }
        }.getOrNull()
    }

    /**
     * Downloads a remote image URL into private storage so it survives restarts and offline edits.
     *
     * ### Two-phase: the network is *not* inside the lease
     *
     * The obvious implementation holds the lease for the whole method, including the `HttpURLConnection`
     * read — which would mean a 15-second read timeout keeps `beginRestore()` refused for up to 15
     * seconds, for a download that has not yet touched `closie/` at all. That is a real cost for no
     * benefit: the download writes to the app **cache**, which a restore does not swap.
     *
     * So the work is split by what it touches:
     *
     *  1. **outside the lease** — download into `cacheDir/image-download/…`, a directory no restore moves
     *     and no other component reads. Long, network-bound, and free to run concurrently with a restore.
     *  2. **inside the lease** — re-check nothing, take the lease, and publish the bytes into
     *     `closie/images/` by the same temp-then-rename path every other copy uses.
     *
     * The property that matters is the one this preserves: **nothing is ever published into
     * `filesDir/closie` outside the lease.** The cache file is scratch, and it is deleted on every exit
     * path — including the failure paths, where the download threw before any lease was taken.
     *
     * Note what step 2 deliberately does *not* do: it does not re-validate a "still in the same
     * generation" token, because there is nothing to re-validate. The bytes came from the network and are
     * generation-independent; the only thing that must be true is that the *publish* cannot straddle a
     * swap, and the lease is exactly that guarantee.
     */
    fun copyFromUrl(context: Context, url: String): String? {
        val cacheDir = File(context.cacheDir, "image-download")
        val downloaded = runCatching {
            cacheDir.mkdirs()
            val ext = when {
                url.contains(".png", ignoreCase = true) || url.contains("png", ignoreCase = true) -> "png"
                url.contains(".webp", ignoreCase = true) || url.contains("webp", ignoreCase = true) -> "webp"
                url.contains(".jpg", ignoreCase = true) || url.contains(".jpeg", ignoreCase = true) || url.contains("jpeg", ignoreCase = true) -> "jpg"
                else -> "bin"
            }
            val tmp = File(cacheDir, "${UUID.randomUUID()}.$ext.tmp")
            try {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 15_000
                conn.readTimeout = 15_000
                conn.instanceFollowRedirects = true
                try {
                    val code = conn.responseCode
                    if (code !in 200..299) throw IllegalStateException("图片下载失败（HTTP $code）")
                    val contentType = conn.contentType
                    if (contentType != null && !contentType.startsWith("image/", ignoreCase = true)) {
                        throw IllegalStateException("图片下载失败（非图片内容）")
                    }
                    conn.inputStream.use { input -> tmp.outputStream().use { target -> input.copyTo(target) } }
                } finally {
                    conn.disconnect()
                }
                if (!tmp.exists() || tmp.length() == 0L) throw IllegalStateException("图片为空")
                tmp to ext
            } catch (e: Exception) {
                tmp.delete()
                throw e
            }
        }.getOrNull() ?: return null

        val (staged, ext) = downloaded
        try {
            // The lease is taken only now, for the publish alone. A refusal is reported as `null`, the
            // same as any other failure in this method — see [copyFromUri] for why the gate refusal is
            // not allowed to become a new throwing contract on this API.
            return runCatching {
                RestoreStartupGate.withBusinessAccess {
                    publishInto(itemsDir(context), ext) { tmp -> staged.copyTo(tmp, overwrite = true) }
                }
            }.getOrNull()
        } finally {
            staged.delete()
        }
    }

    /**
     * Deletes a private image only if it lives inside the app's own `closie/images` tree.
     *
     * Leased because the containment check and the unlink are two steps over a directory a restore can
     * replace between them — see the class doc. The lease is taken before the canonical-path resolution,
     * not after, since it is the resolution that reads the directory the swap moves.
     *
     * ### A refusal is a no-op, not an exception
     *
     * This returns `Unit`, and its callers are `onDispose` blocks, `DisposableEffect` cleanups and orphan
     * sweeps in three screens — none of which has a `try`/`catch`, and several of which run while a screen
     * is being torn down, where an exception has nowhere useful to go. Turning a gate refusal into a throw
     * here would be a new crash contract on a method whose whole job is best-effort cleanup.
     *
     * So the refusal is absorbed: **no delete happens**, which is the correct outcome anyway. A delete
     * during a restore is the one thing that must not happen — it would unlink a path in the generation
     * being replaced (or the restored one) — and "did nothing" is indistinguishable from "there was
     * nothing to delete" for every caller. The lease is still the mechanism that refuses; the caller just
     * does not have to know a gate exists.
     */
    fun deletePrivatePath(context: Context, path: String?) {
        if (path == null) return
        runCatching {
            RestoreStartupGate.withBusinessAccess {
                val root = imagesRoot(context).canonicalFile
                val file = runCatching { File(path).canonicalFile }.getOrNull() ?: return@withBusinessAccess
                val rootPrefix = root.path + File.separator
                if (file.path.startsWith(rootPrefix)) file.delete()
            }
        }
    }
}
