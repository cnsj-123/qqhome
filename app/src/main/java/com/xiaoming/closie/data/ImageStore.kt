package com.xiaoming.closie.data

import android.content.Context
import android.net.Uri
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * Copies selected bytes into app-private storage, retaining PNG/WebP alpha and surviving URI
 * permission loss/restarts. Writes go to a temporary file first and are atomically renamed only
 * after a complete, non-empty copy, so a failure never leaves a half-written file behind.
 */
object ImageStore {
    private fun imagesRoot(context: Context) = File(context.filesDir, "closie/images")
    private fun itemsDir(context: Context) = imagesRoot(context)
    private fun ootdDir(context: Context) = File(context.filesDir, "closie/images/ootd")
    private fun outfitDir(context: Context) = File(context.filesDir, "closie/images/outfit")

    private fun copyFromUriTo(context: Context, uri: Uri, dir: File): String? = runCatching {
        dir.mkdirs()
        val type = context.contentResolver.getType(uri)
        val ext = when {
            type?.contains("png") == true -> "png"
            type?.contains("webp") == true -> "webp"
            type?.contains("jpeg") == true || type?.contains("jpg") == true -> "jpg"
            else -> "bin"
        }
        val id = UUID.randomUUID().toString()
        val tmp = File(dir, ".$id.tmp")
        val final = File(dir, "$id.$ext")
        try {
            val input = context.contentResolver.openInputStream(uri)
                ?: throw IllegalStateException("无法读取图片")
            input.use { source -> tmp.outputStream().use { target -> source.copyTo(target) } }
            if (!tmp.exists() || tmp.length() == 0L) throw IllegalStateException("图片为空")
            if (!tmp.renameTo(final)) throw IllegalStateException("图片保存失败")
            final.absolutePath
        } catch (e: Exception) {
            tmp.delete()
            final.delete()
            throw e
        }
    }.getOrNull()

    fun copyFromUri(context: Context, uri: Uri): String? = copyFromUriTo(context, uri, itemsDir(context))
    fun copyOotdFromUri(context: Context, uri: Uri): String? = copyFromUriTo(context, uri, ootdDir(context))
    fun copyOutfitFromUri(context: Context, uri: Uri): String? = copyFromUriTo(context, uri, outfitDir(context))

    /** Copies an existing local file (e.g. a just-captured screenshot) into private item storage. */
    fun copyFromFile(context: Context, file: File): String? {
        if (!file.exists() || !file.isFile || file.length() == 0L) return null
        val dir = itemsDir(context).apply { mkdirs() }
        val ext = file.extension.ifBlank { "bin" }
        val final = File(dir, "${UUID.randomUUID()}.$ext")
        return runCatching { file.copyTo(final, overwrite = true); final.absolutePath }.getOrNull()
    }

    /** Downloads a remote image URL into private storage so it survives restarts and offline edits. */
    fun copyFromUrl(context: Context, url: String): String? = runCatching {
        val dir = itemsDir(context).apply { mkdirs() }
        val ext = when {
            url.contains(".png", ignoreCase = true) || url.contains("png", ignoreCase = true) -> "png"
            url.contains(".webp", ignoreCase = true) || url.contains("webp", ignoreCase = true) -> "webp"
            url.contains(".jpg", ignoreCase = true) || url.contains(".jpeg", ignoreCase = true) || url.contains("jpeg", ignoreCase = true) -> "jpg"
            else -> "bin"
        }
        val id = UUID.randomUUID().toString()
        val tmp = File(dir, ".$id.tmp")
        val final = File(dir, "$id.$ext")
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
            if (!tmp.renameTo(final)) throw IllegalStateException("图片保存失败")
            final.absolutePath
        } catch (e: Exception) {
            tmp.delete()
            final.delete()
            throw e
        }
    }.getOrNull()

    /** Deletes a private image only if it lives inside the app's own `closie/images` tree. */
    fun deletePrivatePath(context: Context, path: String?) {
        if (path == null) return
        val root = imagesRoot(context).canonicalFile
        val file = runCatching { File(path).canonicalFile }.getOrNull() ?: return
        val rootPrefix = root.path + File.separator
        if (file.path.startsWith(rootPrefix)) file.delete()
    }
}
