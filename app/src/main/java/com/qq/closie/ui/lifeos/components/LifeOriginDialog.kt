package com.qq.closie.ui.lifeos.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.qq.closie.ui.lifeos.theme.LocalLifeOsColors
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

data class LifeSurfaceOrigin(val rect:Rect,val rotation:Float,val orientation:Int) {
    fun usable(orientation:Int,viewport:Rect)=this.orientation==orientation &&
        rect.left.isFinite() && rect.top.isFinite() && rect.right.isFinite() && rect.bottom.isFinite() &&
        rotation.isFinite() && rect.width>0f && rect.height>0f && viewport.contains(rect.center)
}
internal fun interpolateLifeSurface(from:Rect,to:Rect,progress:Float):Rect {
    val p=progress.coerceIn(0f,1f)
    fun mix(a:Float,b:Float)=a+(b-a)*p
    return Rect(mix(from.left,to.left),mix(from.top,to.top),mix(from.right,to.right),mix(from.bottom,to.bottom))
}
internal fun Modifier.lifeSurfaceOrigin(orientation:Int,rotation:Float=0f,onPosition:(LifeSurfaceOrigin)->Unit)=
    onGloballyPositioned { coordinates ->
        if(coordinates.isAttached) {
            val w=coordinates.size.width.toFloat();val h=coordinates.size.height.toFloat()
            val point=coordinates.localToWindow(androidx.compose.ui.geometry.Offset(w/2,h/2))
            onPosition(LifeSurfaceOrigin(Rect(point.x-w/2,point.y-h/2,point.x+w/2,point.y+h/2),rotation,orientation))
        }
    }

/** One surface moves; the source is hidden by its caller until the return animation finishes.
 * Offscreen/rotated sources use a short fade instead of flying toward stale coordinates.
 */
@Composable
internal fun LifeOriginDialog(origin:LifeSurfaceOrigin?,onDismiss:()->Unit,
    widthFraction:Float=.92f,heightFraction:Float=.80f,corner:Dp=26.dp,
    content:@Composable (close:()->Unit)->Unit) {
    val reduced=reducedLifeMotion()
    val reveal=remember {Animatable(if(reduced)1f else 0f)}
    val scope=rememberCoroutineScope()
    val config=LocalConfiguration.current
    val density=LocalDensity.current
    var closing by remember {mutableStateOf(false)}
    val colors=LocalLifeOsColors.current
    LaunchedEffect(reduced) {
        if(reduced)reveal.snapTo(1f) else reveal.animateTo(1f,tween(260))
    }
    fun close() {
        if(closing)return
        closing=true
        scope.launch {
            if(!reduced)reveal.animateTo(0f,tween(220))
            onDismiss()
        }
    }
    Dialog(onDismissRequest=::close,properties=DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=false)) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val width=constraints.maxWidth.toFloat();val height=constraints.maxHeight.toFloat()
            val target=Rect(width*(1-widthFraction)/2,height*(1-heightFraction)/2,
                width*(1+widthFraction)/2,height*(1+heightFraction)/2)
            val source=origin?.takeIf {it.usable(config.orientation,Rect(0f,0f,width,height))}
            val progress=if(reduced)1f else reveal.value
            val bounds=interpolateLifeSurface(source?.rect ?: target,target,progress)
            Surface(Modifier.offset {IntOffset(bounds.left.roundToInt(),bounds.top.roundToInt())}
                .size(with(density){bounds.width.toDp()},with(density){bounds.height.toDp()})
                .graphicsLayer {
                    rotationZ=(source?.rotation ?: 0f)*(1-progress)
                    alpha=if(source!=null)1f else progress
                    clip=true
                },shape=RoundedCornerShape(corner),color=colors.card) {
                content(::close)
            }
        }
    }
}
