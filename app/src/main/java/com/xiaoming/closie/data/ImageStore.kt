package com.xiaoming.closie.data

import android.content.Context
import android.net.Uri
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/** Copies selected bytes to private storage, retaining PNG/WebP alpha and surviving URI permission loss/restarts. */
object ImageStore {
    fun copyFromUri(context: Context, uri: Uri): String? = runCatching {
        val dir=File(context.filesDir,"closie/images").apply{mkdirs()}; val type=context.contentResolver.getType(uri)
        val ext=when { type?.contains("png")==true->"png";type?.contains("webp")==true->"webp";type?.contains("jpeg")==true||type?.contains("jpg")==true->"jpg";else->"bin" }
        val file=File(dir,"${UUID.randomUUID()}.$ext")
        val input = context.contentResolver.openInputStream(uri) ?: return null
        input.use { source -> file.outputStream().use { source.copyTo(it) } }
        if (!file.exists() || file.length() == 0L) { file.delete(); null } else file.absolutePath
    }.getOrNull()

    /** Downloads a remote image URL into private storage so it survives restarts and offline edits. */
    fun copyFromUrl(context: Context, url: String): String? = runCatching {
        val dir = File(context.filesDir, "closie/images").apply { mkdirs() }
        val ext = when {
            url.contains(".png", ignoreCase = true) || url.contains("png", ignoreCase = true) -> "png"
            url.contains(".webp", ignoreCase = true) || url.contains("webp", ignoreCase = true) -> "webp"
            url.contains(".jpg", ignoreCase = true) || url.contains(".jpeg", ignoreCase = true) || url.contains("jpeg", ignoreCase = true) -> "jpg"
            else -> "bin"
        }
        val file = File(dir, "${UUID.randomUUID()}.$ext")
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 15_000
        conn.instanceFollowRedirects = true
        try {
            conn.inputStream.use { input -> file.outputStream().use { input.copyTo(it) } }
        } finally {
            conn.disconnect()
        }
        if (!file.exists() || file.length() == 0L) { file.delete(); null } else file.absolutePath
    }.getOrNull()

    fun deletePrivatePath(context: Context, path: String?) {
        if (path == null) return
        val root = File(context.filesDir, "closie/images").canonicalFile
        val file = runCatching { File(path).canonicalFile }.getOrNull() ?: return
        if (file.parentFile == root) file.delete()
    }
}
