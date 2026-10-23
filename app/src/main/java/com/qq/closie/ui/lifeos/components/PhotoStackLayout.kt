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

    fun pose(id: String, depth: Int, width: Float, height: Float): Pose {
        require(width > 0f && height > 0f)
        val base = slots[Math.floorMod(depth, slots.size)]
        val jitter = Math.floorMod(id.hashCode(), 101) / 100f - .5f
        val angle = base.rotation + jitter * 3f
        val radians = Math.toRadians(angle.toDouble())
        val c = abs(cos(radians)).toFloat()
        val s = abs(sin(radians)).toFloat()
        val margin = minOf(12f, width * .04f, height * .04f)
        val paperWidth = width * base.width
        val paperHeight = height * base.height
        val scale = minOf(1f, (width - margin * 2) / (paperWidth * c + paperHeight * s),
            (height - margin * 2) / (paperWidth * s + paperHeight * c))
        val w = paperWidth * scale
        val h = paperHeight * scale
        val rx = (w * c + h * s) / 2
        val ry = (w * s + h * c) / 2
        return Pose((width * (base.centerX + jitter * .025f)).coerceIn(margin + rx, width - margin - rx),
            (height * base.centerY).coerceIn(margin + ry, height - margin - ry), w, h, angle)
    }
}
