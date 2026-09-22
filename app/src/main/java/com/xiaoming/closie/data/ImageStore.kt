package com.xiaoming.closie.data

import android.content.Context
import android.net.Uri
import java.io.File
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

    fun deletePrivatePath(context: Context, path: String?) {
        if (path == null) return
        val root = File(context.filesDir, "closie/images").canonicalFile
        val file = runCatching { File(path).canonicalFile }.getOrNull() ?: return
        if (file.parentFile == root) file.delete()
    }
}
