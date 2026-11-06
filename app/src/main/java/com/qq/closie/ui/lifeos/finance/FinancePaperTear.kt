package com.qq.closie.ui.lifeos.finance

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.qq.closie.ui.lifeos.components.reducedLifeMotion
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.collect

@Composable
internal fun ReportFinancePaperMotion(onMotion:((Float)->Unit)?,read:()->Float) {
    if(onMotion==null) return
    val owner by rememberUpdatedState(onMotion)
    val currentRead by rememberUpdatedState(read)
    LaunchedEffect(Unit) {snapshotFlow {currentRead()}.collect {owner?.invoke(it)}}
    DisposableEffect(Unit) {onDispose {owner?.invoke(0f)}}
}

/** The opened edge is exactly the existing manual receipt silhouette. Only the tear front moves. */
internal fun manualTearEdge(width:Float,depth:Float,progress:Float):List<Offset> {
    val reach=(progress.coerceIn(0f,1f)/.8f).coerceAtMost(1f)*1.15f
    return (0..22).map {tooth->
        val x=width*tooth/22f
        val edge=if(tooth==0) .7f else when(tooth%5) {0->.3f;1->.9f;2->.1f;3->.6f;else->.4f}
        val opened=((reach-abs(tooth/22f-.5f)*2f)/.15f).coerceIn(0f,1f)
        Offset(x,depth.coerceAtLeast(0f)*edge*opened)
    }
}

internal class GrowingManualReceiptShape(private val progress:Float):Shape {
    override fun createOutline(size:Size,layoutDirection:LayoutDirection,density:Density):Outline {
        val depth=(8f*density.density).coerceAtMost(size.height/4)
        return Outline.Generic(Path().apply {
            manualTearEdge(size.width,depth,progress).forEachIndexed {index,point->
                if(index==0) moveTo(point.x,point.y) else lineTo(point.x,point.y)
            }
            lineTo(size.width,size.height);lineTo(0f,size.height);close()
        })
    }
}

internal data class FinancePaperBackdrop(val scale:Float,val shade:Float,val blur:Float)

internal fun financePaperBackdrop(progress:Float,reduced:Boolean,api:Int,lowRam:Boolean):FinancePaperBackdrop {
    val p=progress.coerceIn(0f,1f)
    return FinancePaperBackdrop(if(reduced) 1f else 1f-.03f*p,.08f*p,
        if(!reduced && api>=31 && !lowRam) 2f*p else 0f)
}

/** Operates on the mounted native Scaffold display list. No bitmap, capture, or second content tree. */
@Composable
internal fun Modifier.financePaperBackdrop(progress:()->Float):Modifier {
    val context=LocalContext.current
    val reduced=reducedLifeMotion()
    val lowRam=remember(context) {(context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)?.isLowRamDevice==true}
    val blurAllowed=Build.VERSION.SDK_INT>=31 && !lowRam && !reduced
    // Cached subpixel radii avoid allocating a RenderEffect on each animation frame.
    val effects:List<RenderEffect?> = remember(blurAllowed) {
        if(blurAllowed) (0..16).map {step->if(step==0) null else
            android.graphics.RenderEffect.createBlurEffect(step/8f,step/8f,android.graphics.Shader.TileMode.CLAMP).asComposeRenderEffect()}
        else emptyList()
    }
    return graphicsLayer {
        val visual=financePaperBackdrop(progress(),reduced,Build.VERSION.SDK_INT,lowRam)
        scaleX=visual.scale;scaleY=visual.scale
        renderEffect=if(visual.blur>0f) effects[(visual.blur*8f).roundToInt().coerceIn(1,16)] else null
    }.drawWithContent {
        drawContent()
        val shade=financePaperBackdrop(progress(),reduced,Build.VERSION.SDK_INT,lowRam).shade
        if(shade>0f) drawRect(Color.Black.copy(alpha=shade))
    }
}
