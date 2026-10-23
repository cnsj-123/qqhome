package com.qq.closie.ui.lifeos

import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
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
        else breath.animateTo(1f, infiniteRepeatable(tween(1100, easing=FastOutSlowInEasing), RepeatMode.Reverse))
    }
    val colors = LocalLifeOsColors.current
    Surface(Modifier.fillMaxSize(), color=colors.paper) {
        Box(Modifier.fillMaxSize(), contentAlignment=Alignment.Center) {
            Column(Modifier.padding(28.dp), horizontalAlignment=Alignment.CenterHorizontally,
                verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Image(painterResource(R.drawable.lifeos_launch_cat), "正在陪你等候的猫咪",
                    Modifier.size(72.dp).graphicsLayer {
                        scaleX=1f+breath.value*.035f; scaleY=1f+breath.value*.035f
                        rotationZ=-2f+breath.value*4f
                    })
                Text("Life OS", style=com.qq.closie.life.ui.theme.LifeType.Wordmark, color=colors.ink)
                Text(title, style=LifeText.body, color=colors.ink)
                if (reduced) LinearProgressIndicator(Modifier.width(120.dp), color=colors.accent)
                else CircularProgressIndicator(Modifier.size(20.dp), color=colors.accent, strokeWidth=2.dp)
                Text(detail, style=LifeText.caption, color=colors.inkSecondary)
            }
        }
    }
}
