package com.qq.closie.ui.lifeos.components

import kotlin.math.abs

object PhotoStackInteraction {
    fun releaseStep(distance: Float, threshold: Float, count: Int): Int = when {
        count < 2 || abs(distance) <= threshold -> 0
        distance < 0 -> 1
        else -> -1
    }
    fun nextIndex(index: Int, step: Int, count: Int) = if (count <= 0) 0 else Math.floorMod(index + step, count)
}
