package com.qq.closie.ui.lifeos.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.qq.closie.ui.lifeos.media.LifeMediaUi
import com.qq.closie.ui.lifeos.theme.*

/** A native dialog window over Home. Back dismisses this window before the root/navigation stack. */
@Composable
fun LifePhotoViewer(photos: List<LifeMediaUi>, initialPhotoId: String, onDismiss: () -> Unit) {
    if (photos.isEmpty()) return
    var index by rememberSaveable(initialPhotoId) { mutableIntStateOf(photos.indexOfFirst { it.id == initialPhotoId }.coerceAtLeast(0)) }
    val current = PhotoStackInteraction.nextIndex(index, 0, photos.size)
    val photo = photos[current]
    val colors = LocalLifeOsColors.current
    val config = LocalConfiguration.current
    val reduced = reducedLifeMotion()
    val reveal = remember { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(reduced) {
        if (reduced) reveal.snapTo(1f) else reveal.animateTo(1f, spring(dampingRatio = 1f, stiffness = 500f))
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            Modifier.width((config.screenWidthDp * .90f).dp).height((config.screenHeightDp * .78f).dp)
                .graphicsLayer { alpha = reveal.value; scaleX = .97f + .03f * reveal.value; scaleY = scaleX },
            shape = RoundedCornerShape(16.dp), color = colors.card, border = BorderStroke(1.dp, colors.line)
        ) {
            Column(Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${current + 1} / ${photos.size}", Modifier.weight(1f), style = LifeText.caption, color = colors.muted)
                    IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, "关闭照片浮层") }
                }
                LifeMediaImage(photo, Modifier.weight(1f).fillMaxWidth().lifeHorizontalSwipe(
                    onLeft = { index = PhotoStackInteraction.nextIndex(current, 1, photos.size) },
                    onRight = { index = PhotoStackInteraction.nextIndex(current, -1, photos.size) }
                ), ContentScale.Fit)
                Text(photo.caption ?: photo.title ?: "生活照片", Modifier.padding(top = 12.dp), style = LifeText.body, color = colors.ink, maxLines = 3)
                // Future playback adapter will consume pairedVideoUri/mediaKind; Task 1 never simulates motion.
                if (photo.motionAvailable) Text("动态组件已关联 · 播放将在后续接入", style = LifeText.caption, color = colors.muted)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = { index = PhotoStackInteraction.nextIndex(current, -1, photos.size) }, enabled = photos.size > 1) { Text("上一张") }
                    TextButton(onClick = { index = PhotoStackInteraction.nextIndex(current, 1, photos.size) }, enabled = photos.size > 1) { Text("下一张") }
                }
            }
        }
    }
}
