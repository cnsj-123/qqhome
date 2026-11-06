package com.qq.closie.ui.lifeos.components

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.*
import org.junit.Test

class PhotoSharedTransformTest {
    @Test fun aWideSourceOpensIntoAPortraitViewportWithOneScaleAndContinuousRotation() {
        val origin=LifeSurfaceOrigin(Rect(20f,40f,220f,140f),-11f,1)
        val target=Rect(50f,200f,350f,650f)
        val start=photoSharedTransform(origin,target,0f)
        val middle=photoSharedTransform(origin,target,.5f)
        val end=photoSharedTransform(origin,target,1f)
        assertEquals(100f/450f,start.scale,.0001f)
        assertEquals(-5.5f,middle.rotation,.0001f)
        assertEquals((start.scale+1f)/2,middle.scale,.0001f)
        assertEquals(1f,end.scale,0f);assertEquals(0f,end.translation.getDistance(),0f);assertEquals(0f,end.rotation,0f)
    }

    @Test fun aMissingOriginNeverInventsAZeroScaleOrAnOffscreenSource() {
        val transform=photoSharedTransform(null,Rect(20f,20f,300f,400f),0f)
        assertEquals(1f,transform.scale,0f);assertEquals(0f,transform.translation.getDistance(),0f)
    }
}
