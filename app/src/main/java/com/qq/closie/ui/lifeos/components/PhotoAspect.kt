package com.qq.closie.ui.lifeos.components

import android.content.ContentResolver
import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import com.qq.closie.ui.lifeos.media.LifeMediaUi
import com.qq.closie.ui.lifeos.media.MediaSourceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Older references do not have stored dimensions. Read only headers, without copying originals. */
@Composable
internal fun rememberPhotoAspects(photos: List<LifeMediaUi>): State<Map<String, Float>> {
    val resolver = LocalContext.current.contentResolver
    return produceState(emptyMap(), resolver, photos) {
        value = withContext(Dispatchers.IO) {
            buildMap {
                photos.forEach { photo ->
                    ensureActive()
                    val ratio = photo.aspectRatio?.takeIf { it.isFinite() && it > 0f }
                        ?: photo.uri?.takeIf { photo.sourceState == MediaSourceState.AVAILABLE }
                            ?.let { readPhotoAspect(resolver, Uri.parse(it)) }
                    if (ratio != null) put(photo.id, ratio)
                }
            }
        }
    }
}

private fun readPhotoAspect(resolver: ContentResolver, uri: Uri): Float? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    try {
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    } catch (_: java.io.IOException) {
        return null
    } catch (_: SecurityException) {
        return null
    } catch (_: IllegalArgumentException) {
        return null
    }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    val orientation = try {
        resolver.openInputStream(uri)?.use {
            ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }
    } catch (_: java.io.IOException) {
        null
    } catch (_: SecurityException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }
    return if ((orientation ?: ExifInterface.ORIENTATION_NORMAL) in ExifInterface.ORIENTATION_TRANSPOSE..ExifInterface.ORIENTATION_ROTATE_270)
        bounds.outHeight.toFloat() / bounds.outWidth else bounds.outWidth.toFloat() / bounds.outHeight
}
