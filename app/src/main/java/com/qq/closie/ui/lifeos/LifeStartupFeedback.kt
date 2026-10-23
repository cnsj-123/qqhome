package com.qq.closie.ui.lifeos

import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.qq.closie.R
import com.qq.closie.life.ui.theme.BundledText as Text
import com.qq.closie.ui.lifeos.components.reducedLifeMotion
import com.qq.closie.ui.lifeos.theme.*

/** Visible while real startup/restore work runs; never holds ready content for an artificial timer. */
@Composable
internal fun LifeStartupFeedback(title:String, detail:String) {
    val reduced = reducedLifeMotion()
    val breath = remember { Animatable(0f) }
    LaunchedEffect(reduced) {
        if (reduced) breath.snapTo(0f)
        else {
            breath.animateTo(1f,tween(90))
            breath.animateTo(0f,tween(100))
        }
    }
    val colors = LocalLifeOsColors.current
    Surface(Modifier.fillMaxSize(), color=colors.paper) {
        Box(Modifier.fillMaxSize(), contentAlignment=Alignment.Center) {
            Column(Modifier.padding(28.dp), horizontalAlignment=Alignment.CenterHorizontally,
                verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(72.dp)) {
                    Image(painterResource(R.drawable.lifeos_launch_cat), "正在陪你等候的猫咪", Modifier.fillMaxSize())
                    Canvas(Modifier.matchParentSize()) {
                        val sx=size.width/132f;val sy=size.height/132f;val blink=breath.value
                        if(blink>0f) for(x in listOf(44f,70f)) {
                            drawOval(colors.paper.copy(alpha=blink),Offset((x-8)*sx,67*sy),Size(16*sx,11*sy))
                            drawLine(colors.muted.copy(alpha=blink),Offset((x-4)*sx,73*sy),Offset((x+4)*sx,73*sy),
                                strokeWidth=1.7f*sx,cap=StrokeCap.Round)
                        }
                    }
                }
                Text("Life OS", style=com.qq.closie.life.ui.theme.LifeType.Wordmark, color=colors.ink)
                Text(title, style=LifeText.body, color=colors.ink)
                if (!reduced) CircularProgressIndicator(Modifier.size(20.dp), color=colors.accent, strokeWidth=2.dp)
                Text(detail, style=LifeText.caption, color=colors.inkSecondary)
            }
        }
    }
}
