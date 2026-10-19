package com.qq.closie.ui.lifeos.finance

import com.qq.closie.life.ui.theme.BundledText as Text
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import com.qq.closie.life.finance.*
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

internal typealias FinanceMediaAction = (suspend (FinanceMediaRepository,FinanceCommandToken)->Unit)->Unit

internal fun retainFinanceUri(context:Context,uri:Uri):Boolean=runCatching {
    context.contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION);true
}.getOrDefault(false)

@Composable
internal fun FinanceAttachments(s:FinanceSnapshot,target:FinanceMediaTarget,busy:Boolean,error:String?,action:FinanceMediaAction) {
    key(target) {FinanceAttachmentsContent(s,target,busy,error,action)}
}

@Composable
private fun FinanceAttachmentsContent(s:FinanceSnapshot,target:FinanceMediaTarget,busy:Boolean,error:String?,action:FinanceMediaAction) {
    var open by rememberSaveable {mutableStateOf(false)}
    var label by rememberSaveable {mutableStateOf("")}
    var reconnect by rememberSaveable {mutableStateOf<String?>(null)}
    var reconnectRevision by rememberSaveable {mutableLongStateOf(0L)}
    var message by remember {mutableStateOf<String?>(null)}
    var reuse by rememberSaveable {mutableStateOf("")}
    var shown by rememberSaveable {mutableIntStateOf(20)}
    val context=LocalContext.current
    val v=s.v16
    val direct=v?.mediaLinkRows.orEmpty().filter {it.voidedAt==null && target.matches(it)}
    val sourceProposals=if(target.eventId==null) emptySet() else {
        val entries=s.v4?.links.orEmpty().filter {it.eventId==target.eventId && it.voidedAt==null}.map {it.entryId}.toSet()
        s.v4?.proposals.orEmpty().filter {it.canonicalId in entries}.map {it.id}.toSet()
    }
    val rows=(direct+v?.mediaLinkRows.orEmpty().filter {it.voidedAt==null && it.proposalId in sourceProposals}).distinctBy {it.id}
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {uri->
        if(uri!=null) {
            val linkId=reconnect;val expected=reconnectRevision
            action {repo,token->
                val document=FinanceSelectedMediaIO.inspect(context,uri)
                withContext(NonCancellable) {
                    if(linkId==null) repo.attach(token,target,document,label) else repo.reconnect(token,linkId,expected,document)
                    message=if(retainFinanceUri(context,uri)) "已保存原件引用，原文件未复制。" else "已保存引用；此来源不支持长期授权，重新打开时可能需要选择原件。"
                }
                reconnect=null
            }
        } else reconnect=null
    }
    TextButton({open=true}) {Text("凭据与附件 · ${rows.size}")}
    if(open) FinanceReceipt("凭据与附件",busy,error,{open=false}) {
        Text("只引用所选原件，支持图片和 PDF。删除本引用保留原件和账务；换机或权限撤销后可重新选择同一原件。")
        message?.let {Text(it)}
        OutlinedTextField(label,{label=it.take(100)},label={Text("凭据名称（选填）")})
        TextButton({reconnect=null;picker.launch(arrayOf("image/*","application/pdf"))},enabled=!busy) {Text("选择原件并保留引用")}
        val reusable=v?.mediaLinkRows.orEmpty().filter {it.voidedAt==null}.distinctBy {it.mediaAssetId to it.sourceHash}.take(100)
        FinanceChoice("复用已有凭据",reuse,listOf("" to "请选择")+reusable.map {it.id to it.label}) {reuse=it}
        TextButton({
            val selected=reusable.singleOrNull {it.id==reuse}
            if(selected!=null) action {repo,token->repo.linkSharedAsset(token,target,selected.mediaAssetId,selected.sourceHash,label.ifBlank {selected.label})}
        },enabled=!busy && reuse.isNotBlank()) {Text("关联同一媒体原件")}
        rows.take(shown).forEach {link->
            HorizontalDivider();Text(link.label)
            if(link.proposalId in sourceProposals) Text("来自已确认的来源小票")
            TextButton({action {repo,_->
                val source=repo.source(context,link)
                message=when(source.availability) {
                    FinanceMediaAvailability.MISSING_ASSET->"共享媒体记录缺失，账务已保留，请重新选择原件。"
                    FinanceMediaAvailability.SOURCE_MISSING->"原件已移除或授权失效，账务已保留，请重新选择原件。"
                    FinanceMediaAvailability.SOURCE_CHANGED->"此位置的文件内容已经改变，未用它替换原凭据。"
                    FinanceMediaAvailability.AVAILABLE->"原件摘要匹配。"
                }
                if(source.availability==FinanceMediaAvailability.AVAILABLE) context.startActivity(Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(Uri.parse(requireNotNull(source.uri)),source.mimeType)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                })
            }},enabled=!busy){Text("检查并打开凭据")}
            TextButton({reconnect=link.id;reconnectRevision=link.revision;picker.launch(arrayOf("image/*","application/pdf"))},enabled=!busy){Text("重新选择同一原件以恢复访问")}
            TextButton({action {repo,token->repo.unlink(token,link.id,link.revision)}},enabled=!busy){Text("移除此引用，保留原件")}
        }
        if(rows.size>shown) TextButton({shown+=20}) {Text("再显示 20 个凭据")}
    }
}
