package com.qq.closie.ui.lifeos.finance

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.qq.closie.life.ui.theme.BundledText as Text
import com.qq.closie.ui.lifeos.components.reducedLifeMotion
import com.qq.closie.ui.lifeos.theme.LocalLifeOsColors
import kotlinx.coroutines.launch

/** New-entry paper leaves the real ledger visible above it, unlike a generic full-height sheet. */
@Composable
internal fun FinanceEntryContainer(title: String, busy: Boolean, error: String?, onClose: () -> Unit,
    manualPaper: Boolean, reducedMotion:Boolean=reducedLifeMotion(),onPaperMotion:((Float)->Unit)?=null, actions: @Composable ColumnScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit) {
    if (!manualPaper) { FinanceReceipt(title,busy,error,onClose,actions=actions,content=content); return }
    val colors=LocalLifeOsColors.current
    val reduced=reducedMotion
    val progress=remember {Animatable(0f)}
    ReportFinancePaperMotion(onPaperMotion) {progress.value.coerceIn(0f,1f)}
    val scope=rememberCoroutineScope()
    val close by rememberUpdatedState(onClose)
    val currentBusy by rememberUpdatedState(busy)
    var motion by remember {mutableStateOf<kotlinx.coroutines.Job?>(null)}
    fun dismiss(velocity:Float=0f) {
        if(currentBusy) return
        motion?.cancel()
        motion=scope.launch {
            if(reduced) progress.animateTo(0f,tween(150))
            else progress.animateTo(0f,LedgerMotion.SpringSpatial,initialVelocity=velocity)
            if(currentBusy) progress.animateTo(1f,if(reduced) tween(150) else LedgerMotion.SpringSpatial)
            else close()
        }
    }
    LaunchedEffect(reduced) {progress.animateTo(1f,if(reduced) tween(150) else LedgerMotion.SpringSpatial)}
    Dialog(onDismissRequest={dismiss()},properties=DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=false)) {
        val window=(LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect {window?.setDimAmount(0f)}
        BackHandler {dismiss()}
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val paperHeight=maxHeight*.866f
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha=if(onPaperMotion!=null) 0f else .08f*progress.value.coerceIn(0f,1f))).clickable(enabled=!busy){dismiss()})
            if(!reduced) Canvas(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(paperHeight)) {
                val p=progress.value.coerceIn(0f,1f)
                val front=(p/.8f).coerceIn(0f,1f)
                val lineAlpha=(1f-(p-.8f)/.2f).coerceIn(0f,1f)
                if(front>0f && lineAlpha>0f) {
                    val left=size.width*(1f-front)/2f;val right=size.width-left
                    val path=Path().apply {
                        moveTo(left,0f)
                        manualTearEdge(size.width,8.dp.toPx(),p).filter {it.x in left..right}.forEach {lineTo(it.x,it.y)}
                        lineTo(right,0f)
                    }
                    drawPath(path,colors.inkSecondary.copy(alpha=.5f*lineAlpha),style=Stroke(1.dp.toPx()))
                }
            }
            Surface(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(paperHeight).imePadding()
                .testTag("finance-receipt").semantics {paneTitle=title}.graphicsLayer {
                    // The spatial spring first opens the seam, then takes the exposed paper with it.
                    // Its small physical overshoot becomes the specified ~2dp landing bounce.
                    val unfold=if(reduced) progress.value else ((progress.value-.6f)/.4f).coerceAtLeast(0f)
                    alpha=unfold.coerceIn(0f,1f)
                    rotationX=if(reduced) 0f else -12f*(1f-unfold)
                    translationY=if(reduced) 0f else (1f-unfold)*28.dp.toPx()
                    cameraDistance=12*density
                    shape=if(reduced) ManualReceiptShape else GrowingManualReceiptShape(progress.value)
                    clip=true
                },shape=RectangleShape,color=colors.paper) {
                Column(Modifier.fillMaxSize().padding(horizontal=32.dp)
                    .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets(bottom=24.dp)))) {
                    Row(Modifier.fillMaxWidth().height(52.dp).pointerInput(busy,reduced) {
                        val tracker=VelocityTracker()
                        detectVerticalDragGestures(onDragStart={if(!currentBusy) {tracker.resetTracking();motion?.cancel()}},onDragEnd={
                            if(!currentBusy) {
                                val velocity=tracker.calculateVelocity().y/paperHeight.toPx()
                                if(progress.value<.88f || velocity>.14f) dismiss(-velocity)
                                else {motion=scope.launch {progress.animateTo(1f,if(reduced) tween(150) else LedgerMotion.SpringSpatial,initialVelocity=if(reduced) 0f else -velocity)}}
                            }
                        },onDragCancel={if(!currentBusy) motion=scope.launch {progress.animateTo(1f,if(reduced) tween(150) else LedgerMotion.SpringSpatial)}}) {change,dy->
                            if(!currentBusy) {change.consume();tracker.addPosition(change.uptimeMillis,change.position)
                                scope.launch {progress.snapTo((progress.value-dy/paperHeight.toPx()).coerceIn(0f,1f))}}
                        }
                    },verticalAlignment=Alignment.CenterVertically) {
                        IconButton({dismiss()},Modifier.offset(x=(-12).dp),enabled=!busy) {
                            Icon(androidx.compose.ui.res.painterResource(com.qq.closie.R.drawable.lifeos_finance_entry_back),"收起",Modifier.size(24.dp),tint=colors.inkSecondary)
                        }
                    }
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp),content=content)
                    error?.let {Text(it,style=LedgerText.caption,color=MaterialTheme.colorScheme.error)}
                    Column(Modifier.fillMaxWidth().padding(top=4.dp),verticalArrangement=Arrangement.spacedBy(8.dp),content=actions)
                }
            }
        }
    }
}
