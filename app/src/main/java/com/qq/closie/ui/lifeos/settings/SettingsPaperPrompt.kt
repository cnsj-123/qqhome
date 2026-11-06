package com.qq.closie.ui.lifeos.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.qq.closie.ui.lifeos.components.LifeSurfaceOrigin
import com.qq.closie.ui.lifeos.theme.LocalLifeOsColors

/** One visual envelope for real backup/export actions; all command callbacks stay with their owner. */
@Composable
internal fun SettingsPaperPrompt(paper:Boolean,origin:LifeSurfaceOrigin?=null,busy:Boolean=false,
    onDismissRequest:()->Unit,title:@Composable ()->Unit,text:@Composable ()->Unit,
    confirmButton:@Composable ()->Unit,dismissButton:(@Composable ()->Unit)?=null) {
    if(!paper) {AlertDialog(onDismissRequest=onDismissRequest,title=title,text=text,confirmButton=confirmButton,dismissButton=dismissButton);return}
    val colors=LocalLifeOsColors.current
    SettingsOriginDialog(origin,onDismissRequest,dismissEnabled=!busy) {
        Column(Modifier.fillMaxWidth().heightIn(max=(androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp*.88f).dp)
            .padding(start=24.dp,end=24.dp,top=38.dp,bottom=32.dp)) {
            CompositionLocalProvider(LocalTextStyle provides SettingsText.title,LocalContentColor provides colors.ink,content=title)
            Column(Modifier.weight(1f,fill=false).verticalScroll(rememberScrollState()).padding(top=18.dp,bottom=24.dp),
                verticalArrangement=Arrangement.spacedBy(12.dp)) {
                CompositionLocalProvider(LocalTextStyle provides SettingsText.caption,LocalContentColor provides colors.inkSecondary,content=text)
            }
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                if(dismissButton!=null) Box(Modifier.weight(1f).height(46.dp).background(colors.paperTertiary,RoundedCornerShape(12.dp)),
                    contentAlignment=Alignment.Center,propagateMinConstraints=true,content={dismissButton()})
                Box(Modifier.weight(1f).height(46.dp).background(colors.accent,RoundedCornerShape(12.dp)),
                    contentAlignment=Alignment.Center,propagateMinConstraints=true) {
                    MaterialTheme(colorScheme=MaterialTheme.colorScheme.copy(primary=colors.onAccent)) {confirmButton()}
                }
            }
        }
    }
}
