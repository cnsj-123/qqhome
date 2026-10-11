package com.qq.closie.data.appearance

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri

/** Read transient Photo Picker access, downsample before decode; never copy the screenshot. */
internal fun themeFromImage(context: Context, uri: Uri): CustomTheme {
    val resolver = context.contentResolver
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    require(bounds.outWidth > 0 && bounds.outHeight > 0) { "无法读取这张图片，请选择其他照片或截图" }
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 256) sample *= 2
    val options = BitmapFactory.Options().apply { inSampleSize = sample }
    val bitmap = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        ?: throw IllegalArgumentException("图片读取失败，请重新选择")
    return try {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        ImagePalette.extract(pixels)
    } finally { bitmap.recycle() }
}
