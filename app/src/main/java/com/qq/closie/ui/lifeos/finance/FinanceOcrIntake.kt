package com.qq.closie.ui.lifeos.finance

import com.qq.closie.life.ui.theme.BundledText as Text
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import com.qq.closie.life.finance.*
import kotlinx.coroutines.*

@Composable
internal fun FinanceOcrIntake(busy:Boolean,action:FinanceMediaAction) {
    val context=LocalContext.current;val scope=rememberCoroutineScope()
    var job by remember {mutableStateOf<Job?>(null)}
    var reading by remember {mutableStateOf(false)}
    var result by remember {mutableStateOf<Pair<FinanceSelectedDocument,FinanceOcrFields>?>(null)}
    var error by remember {mutableStateOf<String?>(null)}
    var kind by rememberSaveable {mutableStateOf("")}
    var reviewed by rememberSaveable {mutableStateOf(false)}
    var retain by rememberSaveable {mutableStateOf(false)}
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {uri->
        if(uri!=null) {
            job?.cancel();result=null;error=null;reviewed=false;retain=false;reading=true
            job=scope.launch {
                try {
                    val found=FinanceSelectedMediaIO.recognize(context,uri)
                    result=found;kind=found.second.suggestedKind?.name.orEmpty()
                } catch(e:CancellationException) {throw e}
                catch(e:Exception) {error=e.message ?: "本地识别未完成，可重新选择或手工录入"}
                finally {reading=false}
            }
        }
    }
    Text("选图识别 · 本地",style=MaterialTheme.typography.titleMedium)
    Text("仅处理你选中的一张图片，支持离线识别；不读取整个相册。OCR 原文只在内存中提取字段，确认后才入账。")
    error?.let {Text(it,color=MaterialTheme.colorScheme.error)}
    if(reading) {
        LinearProgressIndicator()
        TextButton({job?.cancel();result=null;retain=false;reviewed=false;error="正在结束本次本地识别，不会保存候选。"}) {Text("取消识别")}
    } else TextButton({picker.launch(arrayOf("image/*"))},enabled=!busy) {Text("选择图片进行本地 OCR")}
    result?.let {found->
        val (document,fields)=found
        Text(fields.issue)
        Text("识别金额："+(fields.amountMinor?.let {FinanceMoney.display(it)} ?: "待填写"))
        Text("识别时间："+(fields.occurredAt?.let(FinanceTime::input) ?: "待填写，不使用选图时间代替"))
        FinanceChoice("核对资金方向 · 人民币",kind,listOf("" to "请选择")+FinanceProposalKind.entries.map {it.name to when(it){FinanceProposalKind.EXPENSE->"支出";FinanceProposalKind.INCOME->"收入";FinanceProposalKind.TRANSFER->"转账/信用卡还款"}}){kind=it;reviewed=false}
        Row {Checkbox(reviewed,{reviewed=it});Text("已核对方向，接下来逐项修改小票")}
        Row {Checkbox(retain,{retain=it});Text("明确保留这张原图的凭据引用")}
        Text("未勾选保留时，不写入原图 URI；候选只保留金额、时间、方向和识别问题。")
        TextButton({
            val chosen=FinanceProposalKind.valueOf(kind);val keep=retain
            action {repo,token->
            withContext(NonCancellable) {
                val id=repo.createOcrCandidate(token,fields,chosen,document,keep)
                if(keep) retainFinanceUri(context,android.net.Uri.parse(document.uri))
                result=null;retain=false;reviewed=false;FinanceReviewRequests.open(id)
            }
        }},enabled=!busy && reviewed && kind.isNotBlank()) {Text("保存待复核候选并打开小票")}
        TextButton({result=null;reviewed=false;retain=false}) {Text("丢弃识别结果")}
    }
}
