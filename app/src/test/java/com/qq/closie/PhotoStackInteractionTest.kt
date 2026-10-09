package com.qq.closie

import com.qq.closie.ui.lifeos.components.PhotoStackInteraction
import org.junit.Assert.assertEquals
import org.junit.Test

class PhotoStackInteractionTest {
    @Test
    fun `short drags cancel while threshold crossings cycle in either direction`() {
        assertEquals(0, PhotoStackInteraction.releaseStep(-47f, 48f, 3))
        assertEquals(0, PhotoStackInteraction.releaseStep(48f, 48f, 3))
        assertEquals(1, PhotoStackInteraction.releaseStep(-49f, 48f, 3))
        assertEquals(-1, PhotoStackInteraction.releaseStep(49f, 48f, 3))
        assertEquals(0, PhotoStackInteraction.nextIndex(2, 1, 3))
        assertEquals(2, PhotoStackInteraction.nextIndex(0, -1, 3))
        assertEquals(0, PhotoStackInteraction.releaseStep(-100f, 48f, 1))
        assertEquals(0, PhotoStackInteraction.nextIndex(5, 1, 0))
    }
}
