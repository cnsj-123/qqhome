package com.qq.closie.ui.lifeos.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qq.closie.life.ui.theme.BundledText as Text
import com.qq.closie.ui.lifeos.components.LifeSurfaceOrigin
import com.qq.closie.ui.lifeos.theme.LocalLifeOsColors

/** One visual envelope for real backup/export actions; all command callbacks stay with their owner. */
@Composable
internal fun SettingsPaperPrompt(paper:Boolean,origin:LifeSurfaceOrigin?=null,busy:Boolean=false,
    onDismissRequest:()->Unit,title:@Composable ()->Unit,text:@Composable ()->Unit,
    confirmButton:@Composable (close:()->Unit,canConfirm:Boolean)->Unit,dismissButton:(@Composable (close:()->Unit)->Unit)?=null) {
    if(!paper) {
        val legacyDismiss:(@Composable ()->Unit)?=if(dismissButton==null) null else ({dismissButton(onDismissRequest)})
        AlertDialog(onDismissRequest=onDismissRequest,title=title,text=text,
            confirmButton={confirmButton(onDismissRequest,!busy)},dismissButton=legacyDismiss)
        return
    }
    val colors=LocalLifeOsColors.current
    SettingsOriginDialog(origin,onDismissRequest,dismissEnabled=!busy) {close->
        val canConfirm=!busy && LocalSettingsDialogDismissal.current?.closing!=true
        Column(Modifier.fillMaxWidth().heightIn(max=(androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp*.88f).dp)
            .padding(start=34.dp,end=34.dp,top=28.dp,bottom=24.dp)) {
            CompositionLocalProvider(LocalTextStyle provides SettingsText.title.copy(fontSize=20.sp,lineHeight=26.sp),LocalContentColor provides colors.ink,content=title)
            Column(Modifier.weight(1f,fill=false).verticalScroll(rememberScrollState()).padding(top=18.dp,bottom=24.dp),
                verticalArrangement=Arrangement.spacedBy(12.dp)) {
                CompositionLocalProvider(LocalTextStyle provides SettingsText.caption,LocalContentColor provides colors.inkSecondary,content=text)
            }
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                if(dismissButton!=null) Box(Modifier.weight(1f).height(50.dp).border(.5.dp,colors.inkSecondary.copy(alpha=.22f),RoundedCornerShape(12.dp)),
                    contentAlignment=Alignment.Center,propagateMinConstraints=true,content={dismissButton(close)})
                Box(Modifier.weight(1f).height(50.dp).background(colors.accent,RoundedCornerShape(12.dp)),
                    contentAlignment=Alignment.Center,propagateMinConstraints=true) {
                    MaterialTheme(colorScheme=MaterialTheme.colorScheme.copy(primary=colors.onAccent)) {confirmButton(close,canConfirm)}
                }
            }
        }
    }
}

/** The source form uses a single ruled line per password, with the label alongside the secret. */
@Composable
internal fun SettingsPasswordLine(value:String,onValueChange:(String)->Unit,label:String,tag:String,
    enabled:Boolean,last:Boolean=false) {
    val colors=LocalLifeOsColors.current
    val focus=LocalFocusManager.current
    Column(Modifier.fillMaxWidth().height(65.dp)) {
        Row(Modifier.fillMaxWidth().weight(1f),verticalAlignment=Alignment.CenterVertically) {
            Text(label,Modifier.width(104.dp),style=SettingsText.caption,color=colors.inkSecondary)
            BasicTextField(value,onValueChange,Modifier.weight(1f).testTag(tag).semantics {contentDescription=label},
                enabled=enabled,singleLine=true,textStyle=SettingsText.body.copy(color=colors.inkSecondary,letterSpacing=3.sp),
                visualTransformation=PasswordVisualTransformation(),
                keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password,imeAction=if(last) ImeAction.Done else ImeAction.Next),
                keyboardActions=KeyboardActions(onNext={focus.moveFocus(FocusDirection.Next)},onDone={focus.clearFocus()}))
        }
        HorizontalDivider(thickness=.5.dp,color=colors.inkSecondary.copy(alpha=.22f))
    }
}
