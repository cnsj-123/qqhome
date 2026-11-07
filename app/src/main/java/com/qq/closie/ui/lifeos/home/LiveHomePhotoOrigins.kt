package com.qq.closie.ui.lifeos.home

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.findRootCoordinates
import com.qq.closie.ui.lifeos.components.LifeSurfaceOrigin

/** Source nodes own these handles. Return geometry is read from the current, clipped layout. */
internal class LiveHomePhotoOrigins {
    private data class Source(
        val photoId: String,
        val coordinates: LayoutCoordinates,
        val orientation: Int,
        val rotation: Float
    )

    private val sources = linkedMapOf<String, Source>()

    fun update(key: String, photoId: String, coordinates: LayoutCoordinates?, orientation: Int, rotation: Float = 0f) {
        if (coordinates == null) sources.remove(key)
        else sources[key] = Source(photoId, coordinates, orientation, rotation)
    }

    fun current(photoId: String, orientation: Int, preferredKey: String? = null): LifeSurfaceOrigin? {
        val preferred = preferredKey?.let(sources::get)
        val candidates = listOfNotNull(preferred) + sources.values.filter { it !== preferred }
        for (source in candidates) {
            val coordinates = source.coordinates
            if (source.photoId != photoId || source.orientation != orientation || !coordinates.isAttached) continue
            val root = coordinates.findRootCoordinates()
            val visibleBounds = root.localBoundingBoxOf(coordinates, clipBounds = true)
            val width = coordinates.size.width.toFloat()
            val height = coordinates.size.height.toFloat()
            if (width <= 0f || height <= 0f || visibleBounds.isEmpty) continue
            val center = Offset(width / 2f, height / 2f)
            if (!visibleBounds.contains(root.localPositionOf(coordinates, center))) continue
            val point = coordinates.localToWindow(center)
            if (!point.x.isFinite() || !point.y.isFinite()) continue
            return LifeSurfaceOrigin(
                Rect(point.x - width / 2f, point.y - height / 2f, point.x + width / 2f, point.y + height / 2f),
                source.rotation, source.orientation
            )
        }
        return null
    }
}
