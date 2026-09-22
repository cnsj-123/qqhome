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
        val file=File(dir,"${UUID.randomUUID()}.$ext"); context.contentResolver.openInputStream(uri)?.use{input->file.outputStream().use{input.copyTo(it)}};file.absolutePath
    }.getOrNull()
}
