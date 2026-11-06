package com.qq.closie.ui.lifeos.finance

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.qq.closie.life.ui.theme.BundledText as Text
import com.qq.closie.ui.lifeos.components.reducedLifeMotion
import com.qq.closie.ui.lifeos.theme.LocalLifeOsColors
import kotlinx.coroutines.launch

/** New-entry paper leaves the real ledger visible above it, unlike a generic full-height sheet. */
@Composable
internal fun FinanceEntryContainer(title: String, busy: Boolean, error: String?, onClose: () -> Unit,
    manualPaper: Boolean, actions: @Composable ColumnScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit) {
    if (!manualPaper) { FinanceReceipt(title,busy,error,onClose,actions=actions,content=content); return }
    val colors=LocalLifeOsColors.current
    val reduced=reducedLifeMotion()
    val progress=remember {Animatable(if(reduced) 1f else 0f)}
    val scope=rememberCoroutineScope()
    val close by rememberUpdatedState(onClose)
    val currentBusy by rememberUpdatedState(busy)
    var motion by remember {mutableStateOf<kotlinx.coroutines.Job?>(null)}
    fun dismiss(velocity:Float=0f) {
        if(currentBusy) return
        motion?.cancel()
        motion=scope.launch {
            if(!reduced) progress.animateTo(0f,LedgerMotion.SpringSpatial,initialVelocity=velocity)
            close()
        }
    }
    LaunchedEffect(Unit) {if(!reduced) progress.animateTo(1f,LedgerMotion.SpringSpatial)}
    Dialog(onDismissRequest={dismiss()},properties=DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=false)) {
        BackHandler {dismiss()}
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val paperHeight=maxHeight*.866f
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha=.10f*progress.value)).clickable(enabled=!busy){dismiss()})
            Surface(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(paperHeight).imePadding()
                .testTag("finance-receipt").semantics {paneTitle=title}.graphicsLayer {
                    alpha=if(reduced) 1f else progress.value.coerceIn(0f,1f)
                    rotationX=if(reduced) 0f else -12f*(1f-progress.value)
                    translationY=if(reduced) 0f else (1f-progress.value)*28.dp.toPx()
                    cameraDistance=12*density
                },shape=ManualReceiptShape,color=colors.paper) {
                Column(Modifier.fillMaxSize().padding(horizontal=32.dp).navigationBarsPadding()) {
                    Row(Modifier.fillMaxWidth().height(52.dp).pointerInput(busy,reduced) {
                        val tracker=VelocityTracker()
                        detectVerticalDragGestures(onDragStart={tracker.resetTracking();motion?.cancel()},onDragEnd={
                            val velocity=tracker.calculateVelocity().y/paperHeight.toPx()
                            if(progress.value<.88f || velocity>.14f) dismiss(-velocity)
                            else {motion=scope.launch {progress.animateTo(1f,LedgerMotion.SpringSpatial,initialVelocity=-velocity)}}
                        },onDragCancel={motion=scope.launch {progress.animateTo(1f,LedgerMotion.SpringSpatial)}}) {change,dy->
                            if(!currentBusy) {change.consume();tracker.addPosition(change.uptimeMillis,change.position)
                                scope.launch {progress.snapTo((progress.value-dy/paperHeight.toPx()).coerceIn(0f,1f))}}
                        }
                    },verticalAlignment=Alignment.CenterVertically) {
                        IconButton({dismiss()},Modifier.offset(x=(-12).dp),enabled=!busy) {Icon(Icons.AutoMirrored.Outlined.ArrowBack,"收起",Modifier.size(20.dp),tint=colors.inkSecondary)}
                    }
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp),content=content)
                    error?.let {Text(it,style=LedgerText.caption,color=MaterialTheme.colorScheme.error)}
                    Column(Modifier.fillMaxWidth().padding(top=4.dp,bottom=12.dp),verticalArrangement=Arrangement.spacedBy(8.dp),content=actions)
                }
            }
        }
    }
}
