package com.qq.closie.ui.lifeos.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import com.qq.closie.data.backup.BackupRestorePreview
import com.qq.closie.life.ui.theme.BundledText as Text
import com.qq.closie.ui.lifeos.components.LifeSurfaceOrigin

/** Both deliberate confirmation methods stay bound to the inspected bytes and the selected source. */
@Composable
internal fun SettingsRestorePreviewPrompt(
    preview:BackupRestorePreview,
    confirmationKey:Any,
    enabled:Boolean,
    busy:Boolean,
    origin:LifeSurfaceOrigin?=null,
    message:String?=null,
    onDismissRequest:()->Unit,
    onConfirm:(BackupRestorePreview)->Unit
) {
    var clickConfirmation by remember(confirmationKey,preview) {mutableStateOf(false)}
    val currentEnabled by rememberUpdatedState(enabled && !busy)
    val confirm by rememberUpdatedState(onConfirm)
    SettingsPaperPrompt(paper=true,origin=origin,busy=busy,onDismissRequest=onDismissRequest,
        title={Text("确认恢复范围")},
        text={
            val dismissal=LocalSettingsDialogDismissal.current
            val interactive=currentEnabled && dismissal?.closing!=true
            Column {
                Text(if(preview.includesLifeOs) "衣橱及 Life OS 数据 · 账目记录 ${preview.entries ?: 0} 条" else "旧版备份 · 仅覆盖衣橱数据")
                Text("确认后会替换上述范围的当前数据。系统权限与云授权需重新确认。")
                TextButton(onClick={
                    if(currentEnabled && dismissal?.closing!=true) clickConfirmation=!clickConfirmation
                },enabled=interactive) {
                    Text(if(clickConfirmation) "改用按住确认" else "使用点击确认")
                }
                if(clickConfirmation) Text("请核对恢复范围，再点击下方「确认恢复」。")
                message?.let {Text(it,color=MaterialTheme.colorScheme.error)}
            }
        },
        confirmButton={_,canConfirm->
            val dismissal=LocalSettingsDialogDismissal.current
            val interactive=currentEnabled && canConfirm && dismissal?.closing!=true
            val confirmCurrent:()->Unit={
                if(currentEnabled && dismissal?.closing!=true) confirm(preview)
            }
            if(clickConfirmation) TextButton(onClick=confirmCurrent,enabled=interactive) {Text("确认恢复")}
            else SettingsHoldToRestore(interactive,confirmationKey to preview,confirmCurrent)
        },
        dismissButton={close->TextButton(onClick=close,enabled=!busy) {Text("取消")}}
    )
}
