package com.qq.closie.ui.lifeos

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.Build
import android.view.PixelCopy
import android.view.WindowManager
import android.view.inspector.WindowInspector
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.robolectric.util.ReflectionHelpers
import java.util.concurrent.Executor

/** Native host rendering of the real attached windows, not Android device/compositor evidence. */
internal data class NativeAttachedWindowFrame(val bitmap:Bitmap,val windowAttributes:String)

/**
 * Robolectric 4.12.2's hardware PixelCopy path replays each ViewRoot's RenderNode using its cached
 * HardwareRenderer + ImageReader. A software Canvas cannot replay Compose's recorded glass layer.
 *
 * See the pinned dependency's ShadowPixelCopy.java and HardwareRenderingScreenshot.java:
 * https://github.com/robolectric/robolectric/tree/robolectric-4.12.2/shadows/framework/src/main/java/org/robolectric/shadows
 *
 * Window dim and alpha are compositor attributes, not View.draw content. Apply the actual attached
 * root's LayoutParams in z order, so transparent dialogs retain the actual Activity underneath.
 */
internal fun captureNativeAttachedWindows():NativeAttachedWindowFrame {
    check(Build.VERSION.SDK_INT==34) {"This evidence helper is pinned to the SDK34 NATIVE test environment"}
    val windows=WindowInspector.getGlobalWindowViews().filter {it.isAttachedToWindow && it.width>100 && it.height>100}
    check(windows.isNotEmpty()) {"No attached production windows to capture"}
    val base=windows.first()
    val origin=IntArray(2).also(base::getLocationOnScreen)
    val composed=Bitmap.createBitmap(base.width,base.height,Bitmap.Config.ARGB_8888)
    val canvas=Canvas(composed)
    val records=JSONArray()
    val property="robolectric.pixelCopyRenderMode"
    val previous=System.getProperty(property)
    try {
        System.setProperty(property,"hardware")
        windows.forEachIndexed {index,view->
            val attributes=view.layoutParams as? WindowManager.LayoutParams
                ?: error("Attached window root has no WindowManager.LayoutParams: "+view.javaClass.name)
            // Fail explicitly if Robolectric would silently fall back to its software View.draw path.
            assertTrue("Native capture requires a display-list-capable attached root: "+view.javaClass.name,
                ReflectionHelpers.callInstanceMethod<Boolean>(view,"canHaveDisplayList"))
            val position=IntArray(2).also(view::getLocationOnScreen)
            val pixels=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888)
            try {
                var result:PixelCopy.Result?=null
                val request=PixelCopy.Request.Builder.ofWindow(view)
                    // The explicit area also avoids interpreting zero surfaceInsets as an empty crop.
                    .setSourceRect(Rect(0,0,view.width,view.height)).setDestinationBitmap(pixels).build()
                PixelCopy.request(request,Executor {it.run()}) {result=it}
                // The pinned ShadowPixelCopy delivers this main-handler callback synchronously.
                val copied=checkNotNull(result) {"SDK34 native PixelCopy did not deliver its result"}
                assertEquals("Native window PixelCopy must succeed",PixelCopy.SUCCESS,copied.status)
                assertTrue("Native PixelCopy must fill the supplied output",copied.bitmap===pixels)

                val dim=if(index>0 && attributes.flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND!=0)
                    attributes.dimAmount.coerceIn(0f,1f) else 0f
                if(dim>0f) canvas.drawColor(Color.argb((dim*255f).toInt(),0,0,0))
                val paint=Paint().apply {alpha=(attributes.alpha.coerceIn(0f,1f)*255f).toInt()}
                canvas.drawBitmap(pixels,(position[0]-origin[0]).toFloat(),(position[1]-origin[1]).toFloat(),paint)
                records.put(JSONObject().put("index",index).put("root",view.javaClass.name)
                    .put("width",view.width).put("height",view.height).put("screenX",position[0]).put("screenY",position[1])
                    .put("flags",attributes.flags).put("dimAmount",attributes.dimAmount.toDouble())
                    .put("compositedDim",dim.toDouble()).put("windowAlpha",attributes.alpha.toDouble())
                    .put("pixels","ROBOLECTRIC_NATIVE_HARDWARE_RENDERER"))
            } finally {pixels.recycle()}
        }
        return NativeAttachedWindowFrame(composed,records.toString(2))
    } catch(failure:Throwable) {
        composed.recycle()
        throw failure
    } finally {
        if(previous==null) System.clearProperty(property) else System.setProperty(property,previous)
    }
}
