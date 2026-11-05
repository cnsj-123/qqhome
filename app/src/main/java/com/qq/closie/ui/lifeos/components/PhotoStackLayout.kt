package com.qq.closie.ui.lifeos.components

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** Normalized paper poses, bounded after rotation (including a shadow allowance).
 * The composition is deliberately asymmetric; identity only adds a small stable variation.
 */
object PhotoStackLayout {
    data class Pose(val centerX: Float, val centerY: Float, val width: Float,
        val height: Float, val rotation: Float)
    private val slots = listOf(
        Pose(.40f, .57f, .65f, .73f, -12f),
        Pose(.69f, .68f, .36f, .57f, 18f),
        Pose(.72f, .52f, .34f, .65f, -43f),
        Pose(.59f, .35f, .29f, .65f, 9f)
    )

    fun pose(id: String, depth: Int, width: Float, height: Float, aspectRatio: Float? = null): Pose {
        require(width > 0f && height > 0f)
        val slot = slots[Math.floorMod(depth, slots.size)]
        val base = if (depth < slots.size) slot else {
            val spread = Math.floorMod(depth * 73, 101) / 100f
            slot.copy(centerX = .51f + spread * .33f, centerY = .27f + (1f - spread) * .48f,
                width = minOf(slot.width, .31f), height = minOf(slot.height, .60f),
                rotation = (spread - .5f) * 70f)
        }
        val jitter = Math.floorMod(id.hashCode(), 101) / 100f - .5f
        val angle = base.rotation + jitter * 3f
        val radians = Math.toRadians(angle.toDouble())
        val c = abs(cos(radians)).toFloat()
        val s = abs(sin(radians)).toFloat()
        val margin = minOf(12f, width * .04f, height * .04f)
        val frame = minOf(14f, width * .07f, height * .07f)
        val maxImageWidth = (width * base.width - frame).coerceAtLeast(width * .04f)
        val maxImageHeight = (height * base.height - frame).coerceAtLeast(height * .04f)
        val ratio = aspectRatio?.takeIf { it.isFinite() && it > 0f }
        val paperWidth = if (ratio == null) width * base.width else minOf(maxImageWidth, maxImageHeight * ratio) + frame
        val paperHeight = if (ratio == null) height * base.height else minOf(maxImageHeight, maxImageWidth / ratio) + frame
        val scale = minOf(1f, (width - margin * 2) / (paperWidth * c + paperHeight * s),
            (height - margin * 2) / (paperWidth * s + paperHeight * c))
        val w = paperWidth * scale
        val h = paperHeight * scale
        val rx = (w * c + h * s) / 2
        val ry = (w * s + h * c) / 2
        fun clampCenter(preferred: Float, extent: Float, radius: Float): Float {
            val inset = (margin + radius).coerceAtMost(extent / 2)
            return preferred.coerceIn(inset, extent - inset)
        }
        return Pose(clampCenter(width * (base.centerX + jitter * .025f), width, rx),
            clampCenter(height * base.centerY, height, ry), w, h, angle)
    }

    fun interpolate(from: Pose, to: Pose, progress: Float): Pose {
        val p = progress.coerceIn(0f, 1f)
        fun mix(a: Float, b: Float) = a + (b - a) * p
        return Pose(mix(from.centerX, to.centerX), mix(from.centerY, to.centerY),
            mix(from.width, to.width), mix(from.height, to.height), mix(from.rotation, to.rotation))
    }
}
