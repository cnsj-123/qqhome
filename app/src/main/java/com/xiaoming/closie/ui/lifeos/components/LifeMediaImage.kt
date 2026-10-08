package com.xiaoming.closie.ui.lifeos.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import com.xiaoming.closie.ui.lifeos.media.LifeMediaUi
import com.xiaoming.closie.ui.lifeos.media.MediaSourceState
import com.xiaoming.closie.ui.lifeos.theme.LifeText
import com.xiaoming.closie.ui.lifeos.theme.LocalLifeOsColors

@Composable
fun LifeMediaImage(photo: LifeMediaUi, modifier: Modifier = Modifier, contentScale: ContentScale = ContentScale.Crop) {
    val colors = LocalLifeOsColors.current
    @Composable fun Placeholder(text: String) {
        Box(Modifier.fillMaxSize().background(colors.paperSecondary).padding(12.dp), contentAlignment = Alignment.Center) {
            Text(text, style = LifeText.caption, color = colors.muted)
        }
    }
    val source = photo.uri.takeIf { photo.sourceState == MediaSourceState.AVAILABLE }
    SubcomposeAsyncImage(
        model = source,
        contentDescription = photo.title ?: photo.caption ?: "生活照片",
        modifier = modifier, contentScale = contentScale,
        loading = { Placeholder("照片载入中") },
        error = { Placeholder(if (photo.sourceState == MediaSourceState.PENDING_RESTORE) "原件待恢复" else "原件暂不可用") }
    )
}
