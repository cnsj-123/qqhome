package com.qq.closie.ui.lifeos.components
import androidx.compose.ui.geometry.Rect
import org.junit.Test
import org.junit.Assert.*
class LifeSurfaceTransitionTest {
    @Test fun sourceTargetAndReverseStayExact() {
        val a=Rect(20f,80f,200f,200f);val b=Rect(10f,40f,380f,760f)
        assertEquals(a,interpolateLifeSurface(a,b,-1f));assertEquals(b,interpolateLifeSurface(a,b,2f))
        assertEquals(Rect(15f,60f,290f,480f),interpolateLifeSurface(a,b,.5f))
        assertEquals(a,interpolateLifeSurface(a,b,0f))
    }
    @Test fun rotatedOffscreenAndEmptyOriginsCannotUseOldGeometry() {
        val v=Rect(0f,0f,400f,800f)
        assertTrue(LifeSurfaceOrigin(Rect(20f,80f,200f,200f),-12f,1).usable(1,v))
        assertFalse(LifeSurfaceOrigin(Rect(20f,80f,200f,200f),-12f,1).usable(2,v))
        assertFalse(LifeSurfaceOrigin(Rect(20f,900f,200f,1100f),0f,1).usable(1,v))
        assertFalse(LifeSurfaceOrigin(Rect(20f,80f,20f,200f),0f,1).usable(1,v))
    }
}
