package com.qq.closie.ui.lifeos.settings

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.qq.closie.life.ui.theme.BundledText as Text
import com.qq.closie.ui.lifeos.components.reducedLifeMotion
import com.qq.closie.ui.lifeos.theme.LocalLifeOsColors
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** Safety time uses the monotonic frame clock, never the user's animation-duration multiplier. */
internal suspend fun awaitRestoreHold(onProgress:suspend (Float)->Unit) {
    val started=withFrameNanos {it}
    var elapsed=0L
    while(elapsed<600_000_000L) {
        elapsed=(withFrameNanos {it}-started).coerceAtLeast(0L)
        onProgress((elapsed/600_000_000f).coerceIn(0f,1f))
    }
}

/** Only a successful stored-bytes verification may advance [event]. No timer represents backup work. */
@Composable
internal fun SettingsBackupVerifiedSeal(event:Long,verified:Boolean) {
    if(!verified) return
    val colors=LocalLifeOsColors.current
    val reduced=reducedLifeMotion()
    val phase=remember {Animatable(1f)}
    val stamp=remember {Animatable(1f)}
    var consumed by rememberSaveable {mutableLongStateOf(0L)}
    var celebrating by remember {mutableStateOf(false)}
    LaunchedEffect(event,reduced) {
        if(event==0L || event<=consumed) return@LaunchedEffect
        consumed=event
        celebrating=true
        phase.snapTo(0f)
        try {coroutineScope {
            val stamping=if(!reduced) launch {
                stamp.snapTo(.72f)
                stamp.animateTo(1.08f,spring(dampingRatio=.5f,stiffness=350f,visibilityThreshold=.02f))
                stamp.animateTo(1f,spring(dampingRatio=.75f,stiffness=500f))
            } else null
            phase.animateTo(1f,tween(if(reduced) 150 else 660,easing=LinearEasing))
            stamping?.cancel()
            stamp.snapTo(1f)
        }} finally {celebrating=false}
    }
    Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        if(celebrating && !reduced) Row(horizontalArrangement=Arrangement.spacedBy(5.dp)) {
            listOf("创建","校验","封存").forEachIndexed {index,label->
                Text(label,Modifier.graphicsLayer {alpha=((phase.value-index*.16f)/.16f).coerceIn(0f,1f)},
                    style=SettingsText.tiny,color=colors.accentDeep)
            }
        }
        Surface(Modifier.size(42.dp).testTag("backup-verified-seal").graphicsLayer {
            alpha=if(celebrating) ((phase.value-.38f)/.16f).coerceIn(0f,1f) else 1f
            scaleX=if(reduced || !celebrating) 1f else stamp.value
            scaleY=scaleX
            rotationZ=if(reduced) 0f else -5f
        },shape=CircleShape,color=colors.paper,border=BorderStroke(1.dp,colors.accentDeep)) {
            Box(contentAlignment=Alignment.Center) {Text("已验证",style=SettingsText.tiny,color=colors.accentDeep)}
        }
    }
}

/** A 600ms deliberate hold. Touch and accessibility use the same gate and never bypass validation. */
@Composable
internal fun SettingsHoldToRestore(enabled:Boolean,confirmationKey:Any,onConfirm:()->Unit) {
    val colors=LocalLifeOsColors.current
    val dismissal=LocalSettingsDialogDismissal.current
    val interactive=enabled && dismissal?.closing!=true
    val fill=remember(confirmationKey) {Animatable(0f)}
    val scope=rememberCoroutineScope()
    val currentEnabled by rememberUpdatedState(interactive)
    val confirm by rememberUpdatedState(onConfirm)
    var active by remember(confirmationKey) {mutableStateOf<Job?>(null)}
    var completed by remember(confirmationKey) {mutableStateOf(false)}
    fun begin(accessible:Boolean=false):Job? {
        if(!currentEnabled || dismissal?.closing==true || active?.isActive==true || completed) return null
        completed=false
        return scope.launch {
            awaitRestoreHold {fill.snapTo(it)}
            if(currentEnabled && dismissal?.closing!=true) {completed=true;confirm()}
            // Accessibility has no physical release event. Keep a refused owner start retryable.
            if(accessible) {completed=false;fill.animateTo(0f,spring(dampingRatio=.8f,stiffness=300f))}
        }.also {active=it}
    }
    fun reset() {
        active?.cancel()
        completed=false
        scope.launch {fill.animateTo(0f,spring(dampingRatio=.8f,stiffness=300f))}
    }
    DisposableEffect(interactive,confirmationKey) {onDispose {active?.cancel()}}
    DisposableEffect(dismissal,confirmationKey) {
        val unregister=dismissal?.register {active?.cancel();completed=false}
        onDispose {unregister?.invoke()}
    }
    LaunchedEffect(interactive,confirmationKey) {if(!interactive) {active?.cancel();fill.snapTo(0f);completed=false}}
    Box(Modifier.fillMaxWidth().heightIn(min=46.dp).clip(RoundedCornerShape(12.dp))
        .background(colors.accent).drawBehind {
            drawRect(colors.accentDeep,size=Size(size.width*fill.value,size.height))
        }.testTag("backup-restore-hold").semantics {
            role=Role.Button
            contentDescription="按住确认恢复"
            stateDescription=if(interactive) "按住 600 毫秒，松手取消" else "暂不可恢复"
            progressBarRangeInfo=ProgressBarRangeInfo(fill.value,0f..1f)
            if(!interactive) disabled()
            onClick(label="持续确认 600 毫秒后恢复") {
                if(currentEnabled && dismissal?.closing!=true) {begin(accessible=true);true} else false
            }
        }.pointerInput(interactive,confirmationKey) {
            detectTapGestures(onPress={
                if(currentEnabled) {
                    val hold=begin()
                    try {tryAwaitRelease()} finally {
                        hold?.cancel()
                        reset()
                    }
                }
            })
        },contentAlignment=Alignment.Center) {
        Text("按住确认恢复",style=SettingsText.body,color=colors.onAccent)
    }
}
