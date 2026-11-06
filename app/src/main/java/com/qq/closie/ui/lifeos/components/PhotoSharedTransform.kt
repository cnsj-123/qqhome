package com.qq.closie.ui.lifeos.components

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect

/** Move the actual image, independently of the glass card. A single scale preserves its ratio
 * even when a portrait source is opening inside a wide glass surface. */
internal data class PhotoSharedTransform(val translation: Offset, val scale: Float, val rotation: Float)

internal fun photoSharedTransform(source: LifeSurfaceOrigin?, target: Rect, progress: Float): PhotoSharedTransform {
    val p = progress.coerceIn(0f, 1f)
    if (source == null || target.width <= 0f || target.height <= 0f) return PhotoSharedTransform(Offset.Zero, 1f, 0f)
    val startScale = minOf(source.rect.width / target.width, source.rect.height / target.height)
    return PhotoSharedTransform((source.rect.center - target.center) * (1f - p),
        startScale + (1f - startScale) * p, source.rotation * (1f - p))
}
