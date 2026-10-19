package com.qq.closie.ui.lifeos.finance

import com.qq.closie.life.ui.theme.BundledText as Text
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import com.qq.closie.life.finance.*
import com.qq.closie.ui.lifeos.theme.LifeText

@Composable
internal fun FinanceProvenanceWorkbench(state:FinanceUiState,vm:FinanceViewModel) {
    val s=state.snapshot;val v=s.v16
    val epoch=rememberSaveable {v?.controlRows?.singleOrNull()?.restoreEpoch ?: 0L}
    var family by rememberSaveable {mutableStateOf("wechat")}
    var label by rememberSaveable {mutableStateOf("")}
    Text("来源身份与付款工具",style=LifeText.title)
    Text("来源App、来源账号、付款工具和账本账户分别保存。账号标签由你确认，不读取真实账号号码；没有明确身份时只提示可能重复。",style=LifeText.caption)
    FinanceChoice("来源",family,listOf("wechat" to "微信","alipay" to "支付宝","taobao" to "淘宝","jd" to "京东","meituan" to "美团","pinduoduo" to "拼多多","unionpay" to "云闪付")){family=it}
    OutlinedTextField(label,{label=it},label={Text("此来源账号的本地标签，例如个人/工作")})
    TextButton({vm.provenanceAction(epoch,{label=""}) {repo,token->repo.createIdentity(token,family,label)}},enabled=!state.busy && label.isNotBlank()){Text("确认建立来源身份")}
    v?.externalIdentityRows.orEmpty().filter {it.voidedAt==null}.forEach {identity->FinancePanel {
        Text(identity.label+" · "+identity.sourceFamily)
        if(!identity.matchUsable) {
            Text("恢复后匹配未启用，需重新确认本机来源身份。",style=LifeText.caption)
            TextButton({vm.provenanceAction(epoch) {repo,token->repo.approveRestoredIdentity(token,identity.id,identity.revision)}},enabled=!state.busy){Text("确认这是本机使用的同一身份")}
        }
        var instrumentLabel by rememberSaveable(identity.id){mutableStateOf("")}
        var kind by rememberSaveable(identity.id){mutableStateOf(PaymentChannelHint.BANK_CARD.name)}
        OutlinedTextField(instrumentLabel,{instrumentLabel=it},label={Text("付款工具本地名称")})
        FinanceChoice("工具类型",kind,PaymentChannelHint.entries.map {it.name to it.name.replace('_',' ')}){kind=it}
        TextButton({vm.provenanceAction(epoch,{instrumentLabel=""}) {repo,token->repo.createInstrument(token,identity.id,instrumentLabel,kind)}},enabled=!state.busy && identity.matchUsable && instrumentLabel.isNotBlank()){Text("建立付款工具")}
        v?.instrumentRows.orEmpty().filter {it.identityId==identity.id && it.voidedAt==null}.forEach {instrument->
            val open=v?.accountBindingRows.orEmpty().singleOrNull {it.instrumentId==instrument.id && it.voidedAt==null && it.validUntil==null}
            var account by rememberSaveable(instrument.id){mutableStateOf(open?.accountId.orEmpty())}
            var from by rememberSaveable(instrument.id){mutableStateOf(FinanceTime.input(System.currentTimeMillis()))}
            Text(instrument.label,style=LifeText.title)
            FinanceChoice("账本账户建议",account,s.accounts.filter {it.archivedAt==null}.map {it.id to (it.name+" · "+it.currencyCode)}){account=it}
            OutlinedTextField(from,{from=it},label={Text("开始/结束生效时间 · 年-月-日 时:分")})
            TextButton({vm.provenanceAction(epoch) {repo,token->repo.bindInstrument(token,instrument.id,account,FinanceTime.parse(from),open?.id)}},enabled=!state.busy && identity.matchUsable && account.isNotBlank()){Text(if(open==null) "确认建立绑定" else "确认换绑，保留旧区间")}
            if(open!=null) TextButton({vm.provenanceAction(epoch) {repo,token->repo.unbind(token,open.id,open.revision,FinanceTime.parse(from))}},enabled=!state.busy){Text("从上述时间结束当前绑定")}
            v?.accountBindingRows.orEmpty().filter {it.instrumentId==instrument.id}.sortedBy {it.validFrom}.forEach {binding->
                Text((s.accounts.singleOrNull {it.id==binding.accountId}?.name ?: "账户缺失")+" · "+FinanceTime.input(binding.validFrom)+" → "+(binding.validUntil?.let(FinanceTime::input) ?: "仍生效")+if(binding.voidedAt!=null) " · 已停用" else "",style=LifeText.caption)
            }
        }
    }}
}

@Composable
internal fun FinanceEvidenceClassification(proposal:FinanceProposalEntity,s:FinanceSnapshot,busy:Boolean,
    classify:(FinanceEvidenceEntity,String?,String?)->Unit) {
    val v=s.v16 ?: return
    val evidenceIds=v.evidenceProposalRows.filter {it.proposalId==proposal.id}.map {it.evidenceId}.toSet()
    v.evidenceRows.filter {it.id in evidenceIds && it.voidedAt==null}.forEach {evidence->
        var identity by rememberSaveable(evidence.id){mutableStateOf(evidence.identityId.orEmpty())}
        var instrument by rememberSaveable(evidence.id){mutableStateOf(evidence.instrumentId.orEmpty())}
        Text("来源证据 · "+evidence.sourceFamily+" · "+when(evidence.referenceKind){FinanceReferenceKind.ORDER->"订单关联线索，允许多次付款";FinanceReferenceKind.PAYMENT->"付款编号";FinanceReferenceKind.REFUND->"退款编号";else->"无确定编号"},style=LifeText.caption)
        Text(evidence.summary,style=LifeText.caption)
        FinanceChoice("确认来源身份",identity,listOf("" to "身份尚未确定")+v.externalIdentityRows.filter {it.sourceFamily==evidence.sourceFamily && it.voidedAt==null && it.matchUsable}.map {it.id to it.label}){identity=it;instrument=""}
        FinanceChoice("确认付款工具",instrument,listOf("" to "工具未知/手工选账户")+v.instrumentRows.filter {it.identityId==identity && it.voidedAt==null}.map {it.id to it.label}){instrument=it}
        TextButton({classify(evidence,identity.ifBlank {null},instrument.ifBlank {null})},enabled=!busy){Text(if(instrument.isBlank()) "应用来源分类，保留手选账户" else "应用分类与工具账户建议，替换本次所选账户")}
        Text("工具绑定仅建议账户，最终确认时仍需核对真实资金去向。",style=LifeText.caption)
    }
}
