package com.qq.closie

import com.qq.closie.ui.lifeos.components.PhotoStackLayout
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

class PhotoStackLayoutTest {
    @Test fun rotatedPaperStaysInsideTodayAtAllPhoneWidths() {
        for (width in listOf(260f, 300f, 330f, 352f, 600f)) {
            val height = width * .84f
            for (depth in 0..3) for (id in listOf("portrait", "landscape", "panorama", "Aa", "BB")) {
                val p = PhotoStackLayout.pose(id, depth, width, height)
                val a = Math.toRadians(p.rotation.toDouble())
                val rx = (p.width * abs(cos(a)) + p.height * abs(sin(a))) / 2
                val ry = (p.width * abs(sin(a)) + p.height * abs(cos(a))) / 2
                assertTrue(p.centerX - rx >= 0 && p.centerX + rx <= width)
                assertTrue(p.centerY - ry >= 0 && p.centerY + ry <= height)
                assertEquals(p, PhotoStackLayout.pose(id, depth, width, height))
            }
        }
    }
    @Test fun posesDoNotFormACenteredFanOrAnEqualOffsetQueue() {
        val poses = (0..3).map { PhotoStackLayout.pose("same", it, 330f, 277.2f) }
        assertEquals(4, poses.map { it.centerX to it.centerY }.distinct().size)
        assertEquals(4, poses.map { it.width to it.height }.distinct().size)
        assertTrue(poses[0].width > poses[1].width * 1.5f)
        assertTrue(poses.any { it.rotation > 0 } && poses.any { it.rotation < 0 })
    }
}
