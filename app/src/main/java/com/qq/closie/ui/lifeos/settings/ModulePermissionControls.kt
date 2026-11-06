package com.qq.closie.ui.lifeos.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.animateFloat
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.material.icons.outlined.Check
import com.qq.closie.ui.lifeos.components.lifeSurfaceOrigin
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qq.closie.ClosieApplication
import com.qq.closie.life.permissions.*
import com.qq.closie.life.ui.theme.BundledText as Text
import com.qq.closie.ui.lifeos.theme.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

private fun ModuleReadMode.title() = when (this) {
    ModuleReadMode.ALLOW -> "允许访问"
    ModuleReadMode.ASK -> "访问前询问"
    ModuleReadMode.DENY -> "禁止访问"
}

/** The row edits the actual AI dispatch gate. Record-purpose grants remain a separate control. */
@Composable
internal fun FinanceModulePermissionControl(epoch: Long, loaded: Boolean) {
    val app = LocalContext.current.applicationContext as ClosieApplication
    val repository = app.moduleAccess
    var error by remember { mutableStateOf<String?>(null) }
    val rulesFlow = remember(repository) { repository.rules.catch { error = "模块策略读取失败，请重新进入后重试"; emit(emptyMap()) } }
    val rules by rulesFlow.collectAsStateWithLifecycle(initialValue = emptyMap())
    val pending by repository.owner.pending.collectAsStateWithLifecycle()
    val stored = rules[ReadModule.FINANCE]
    val mode = stored?.takeIf { it.epoch == epoch }?.mode ?: ModuleReadMode.ASK
    var open by remember { mutableStateOf(false) }
    var rowOrigin by remember {mutableStateOf<com.qq.closie.ui.lifeos.components.LifeSurfaceOrigin?>(null)}
    val orientation=androidx.compose.ui.platform.LocalConfiguration.current.orientation
    var editingEpoch by remember { mutableLongStateOf(epoch) }
    var editingRevision by remember { mutableLongStateOf(0L) }
    var editingGeneration by remember { mutableLongStateOf(0L) }
    var request by remember { mutableStateOf<ModuleReadRequest?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val ready = loaded && rules.isNotEmpty()
    SettingsLine("财务 Finance", null,
        Icons.Outlined.AccountBalanceWallet, if (ready) mode.title() else "读取策略",
        modifier=Modifier.height(50.dp).lifeSurfaceOrigin(orientation){rowOrigin=it},
        trailingContent={ModulePermissionStamp(if(ready) mode.title() else "读取策略")},
        onClick = if (ready) { {
            editingEpoch = epoch; editingRevision = stored?.revision ?: 0L
            editingGeneration = com.qq.closie.data.backup.RestoreStartupGate.state.value.restoreGeneration
            request = pending[ReadModule.FINANCE]?.takeIf { it.restoreEpoch == epoch && it.restoreGeneration == editingGeneration }
            message = null; error = null; open = true
        } } else null)
    error?.let { Text(it, style = LifeText.caption, color = MaterialTheme.colorScheme.error) }
    message?.let { Text(it, style = LifeText.caption, color = LocalLifeOsColors.current.accentDeep) }
    if (open) ModuleReadPermissionDialog(mode, request, busy, error, rowOrigin, onClose = { if (!busy) open = false }) { selected, duration ->
        busy = true; error = null
        scope.launch {
            try {
                repository.owner.authorize(ReadModule.FINANCE, selected, duration, editingEpoch, request, editingRevision, editingGeneration)
                message = when (duration) {
                    ModuleGrantDuration.ONCE -> "已确认此读取请求，使用后失效"
                    ModuleGrantDuration.CONVERSATION -> "已更新此对话的读取权限，结束对话或重启后失效"
                    ModuleGrantDuration.LONG_TERM -> "模块策略已更新；备份恢复后需重新确认"
                }
                open = false
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.message ?: "模块策略未保存" }
            finally { busy = false }
        }
    }
}

@Composable
private fun ModuleReadPermissionDialog(initial: ModuleReadMode, request: ModuleReadRequest?, busy: Boolean, error: String?,
    origin:com.qq.closie.ui.lifeos.components.LifeSurfaceOrigin?,
    onClose: () -> Unit, onConfirm: (ModuleReadMode, ModuleGrantDuration) -> Unit) {
    var selected by rememberSaveable {mutableStateOf(initial)}
    var duration by rememberSaveable {mutableStateOf(ModuleGrantDuration.LONG_TERM)}
    val colors=LocalLifeOsColors.current
    SettingsOriginDialog(origin,onClose,dismissEnabled=!busy) {close->
        Column(Modifier.fillMaxWidth().heightIn(max=(androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp*.88f).dp)
            .verticalScroll(rememberScrollState()).padding(start=23.dp,end=23.dp,top=49.dp,bottom=48.dp)) {
            Text("财务模块授权",style=SettingsText.title.copy(fontSize=23.sp,lineHeight=33.sp),color=colors.ink)
            Text("当前权限 · "+initial.title(),Modifier.padding(top=5.dp),style=SettingsText.caption.copy(fontSize=13.sp,lineHeight=18.sp),color=colors.inkSecondary)
            Column(Modifier.padding(top=17.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                ModuleReadMode.entries.forEach {mode->
                    Surface(onClick={selected=mode},enabled=!busy,shape=androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
                        border=androidx.compose.foundation.BorderStroke(1.dp,if(selected==mode) colors.accentDeep else colors.line),
                        color=if(selected==mode) colors.paperTertiary else colors.paper) {
                        Row(Modifier.fillMaxWidth().heightIn(min=64.dp).padding(horizontal=14.dp,vertical=10.dp),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
                            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(2.dp)) {
                                Text(mode.title(),style=SettingsText.body.copy(fontSize=16.sp,lineHeight=23.sp),color=colors.ink)
                                Text(when(mode) {
                                    ModuleReadMode.ALLOW->"有效期内允许读取已授权范围。"
                                    ModuleReadMode.ASK->"每次读取前请求确认。"
                                    ModuleReadMode.DENY->"AI 无法读取该模块。"
                                },style=SettingsText.caption.copy(lineHeight=17.sp),color=colors.inkSecondary)
                            }
                            if(selected==mode) Icon(Icons.Outlined.Check,null,Modifier.size(16.dp),tint=colors.accentDeep)
                        }
                    }
                }
            }
            Text("授权有效期",Modifier.padding(top=20.dp),style=SettingsText.body.copy(fontSize=15.sp,lineHeight=21.sp),color=colors.ink)
            Row(Modifier.padding(top=7.dp,start=3.dp).fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(21.dp)) {
                ModuleGrantDuration.entries.forEach {item->
                    val enabled=!busy && when(item) {
                        ModuleGrantDuration.ONCE->request!=null
                        ModuleGrantDuration.CONVERSATION->request?.conversationId!=null
                        ModuleGrantDuration.LONG_TERM->true
                    }
                    Surface(onClick={duration=item},enabled=enabled,modifier=Modifier.width(if(item==ModuleGrantDuration.CONVERSATION) 94.dp else 72.dp).graphicsLayer {alpha=if(enabled) 1f else .45f},shape=androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
                        color=if(duration==item) colors.accent else colors.paperTertiary) {
                        Box(Modifier.height(28.dp).fillMaxWidth(),contentAlignment=androidx.compose.ui.Alignment.Center) {
                            Text(when(item) {ModuleGrantDuration.ONCE->"仅本次";ModuleGrantDuration.CONVERSATION->"本次对话";else->"长期"},
                                style=SettingsText.caption,color=if(duration==item) colors.onAccent else colors.inkSecondary)
                        }
                    }
                }
            }
            if(request==null) Text("仅本次与本次对话需先发起真实读取请求。",Modifier.padding(top=8.dp),style=SettingsText.detail,color=colors.inkSecondary)
            error?.let {Text(it,style=SettingsText.caption,color=MaterialTheme.colorScheme.error)}
            Row(Modifier.padding(top=if(request==null) 16.dp else 31.dp).fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(16.dp)) {
                FilledTonalButton(close,enabled=!busy,modifier=Modifier.weight(1f).height(50.dp),shape=androidx.compose.foundation.shape.RoundedCornerShape(12.dp)) {Text("取消",style=SettingsText.body.copy(fontSize=15.sp))}
                Button({onConfirm(selected,duration)},enabled=!busy,modifier=Modifier.weight(1f).height(50.dp),shape=androidx.compose.foundation.shape.RoundedCornerShape(12.dp)) {Text(if(busy) "保存中…" else "确认更新",style=SettingsText.body.copy(fontSize=15.sp))}
            }
        }
    }
}

@Composable
private fun ModulePermissionStamp(value:String) {
    val reduced=com.qq.closie.ui.lifeos.components.reducedLifeMotion()
    androidx.compose.animation.AnimatedContent(value,transitionSpec={
        androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(if(reduced) 150 else 125))
            .togetherWith(androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(if(reduced) 150 else 125)))
    },label="permission-word") {label->
        val flip by transition.animateFloat(transitionSpec={androidx.compose.animation.core.tween(if(reduced) 150 else 250)},label="permission-flip") {state->
            if(reduced) 0f else when(state) {
                androidx.compose.animation.EnterExitState.PreEnter->90f
                androidx.compose.animation.EnterExitState.Visible->0f
                androidx.compose.animation.EnterExitState.PostExit->-90f
            }
        }
        Text(label,Modifier.widthIn(max=102.dp).graphicsLayer {rotationX=flip;cameraDistance=12*density},
            style=SettingsText.caption,color=LocalLifeOsColors.current.inkSecondary)
    }
}
