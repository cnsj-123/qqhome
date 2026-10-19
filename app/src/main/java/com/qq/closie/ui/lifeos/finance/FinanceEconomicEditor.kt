package com.qq.closie.ui.lifeos.finance

import com.qq.closie.life.ui.theme.BundledText as Text
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.ui.Modifier
import com.qq.closie.life.finance.*
import com.qq.closie.ui.lifeos.theme.LifeText

private data class FundingForm(val id:String,val party:String="self",val amount:String="",val link:String?=null,val basis:String="用户确认")
private data class ShareForm(val id:String,val amount:String="",val beneficiary:String="self",val bearer:String="self",
    val funding:String?=null,val relation:FinanceShareRelation=FinanceShareRelation.UNRESOLVED)
private val fundingFormSaver=listSaver<androidx.compose.runtime.snapshots.SnapshotStateList<FundingForm>,String>(
    save={rows->rows.flatMap {listOf(it.id,it.party,it.amount,it.link.orEmpty(),it.basis)}},
    restore={values->values.chunked(5).map {FundingForm(it[0],it[1],it[2],it[3].ifBlank {null},it[4])}.toMutableStateList()})
private val shareFormSaver=listSaver<androidx.compose.runtime.snapshots.SnapshotStateList<ShareForm>,String>(
    save={rows->rows.flatMap {listOf(it.id,it.amount,it.beneficiary,it.bearer,it.funding.orEmpty(),it.relation.name)}},
    restore={values->values.chunked(6).map {ShareForm(it[0],it[1],it[2],it[3],it[4].ifBlank {null},FinanceShareRelation.valueOf(it[5]))}.toMutableStateList()})

@Composable
internal fun FinanceEconomicEditor(state:FinanceUiState,vm:FinanceViewModel,eventId:String?,resumeDraftId:String?=null,createFresh:Boolean=false,close:()->Unit) {
    if(!state.loaded) {FinanceReceipt("正在打开经济草稿",error=state.error,onClose=close) {Text("等账本快照读取完成后继续编辑。")} ;return}
    val s=state.snapshot;val v=s.v16
    val event=s.v4?.events.orEmpty().singleOrNull {it.id==eventId}
    val detail=v?.economicRows.orEmpty().singleOrNull {it.eventId==eventId && it.voidedAt==null}
    val candidate=remember(eventId,resumeDraftId,createFresh) {if(createFresh) null else v?.economicDraftRows.orEmpty().filter {it.eventId==eventId && it.voidedAt==null && (resumeDraftId==null || it.id==resumeDraftId)}.maxByOrNull {it.updatedAt}}
    val editor=rememberSaveable(eventId) {java.util.UUID.randomUUID().toString()}
    val draftId=rememberSaveable(eventId) {candidate?.id ?: java.util.UUID.randomUUID().toString()}
    val saved=remember(draftId) {v?.economicDraftRows.orEmpty().singleOrNull {it.id==draftId && it.eventId==eventId && it.voidedAt==null}}
    val initialRevision=remember(editor) {saved?.revision}
    val epoch=rememberSaveable(eventId) {v?.controlRows?.singleOrNull()?.restoreEpoch ?: 0L}
    if(v?.economicDraftRows.orEmpty().any {it.id==draftId && it.voidedAt!=null} || epoch!=(v?.controlRows?.singleOrNull()?.restoreEpoch ?: 0L)) {
        FinanceReceipt("此编辑器已结束",onClose=close) {Text("草稿已被确认、舍弃或恢复代际已改变。请收起后重新打开；不会重建旧草稿或重复生成事项。")};return
    }
    val expected=remember(eventId) {if(saved!=null) saved.expectedRevision else detail?.revision}
    val eventVersion=remember(eventId) {if(saved!=null) saved.eventVersion else event?.updatedAt}
    val preserved=rememberSaveable(eventId) {saved?.preservedOccurredAt ?: event?.occurredAt ?: System.currentTimeMillis()}
    var description by rememberSaveable(eventId) {mutableStateOf(saved?.description ?: event?.description.orEmpty())}
    var currency by rememberSaveable(eventId) {mutableStateOf(saved?.currencyCode ?: event?.currencyCode ?: "CNY")}
    var value by rememberSaveable(eventId) {mutableStateOf(saved?.valueText ?: detail?.valueMinor?.let {FinanceMoney.input(it,currency)}.orEmpty())}
    var at by rememberSaveable(eventId) {mutableStateOf(saved?.occurredText ?: FinanceTime.input(preserved))}
    var basis by rememberSaveable(eventId) {mutableStateOf(saved?.valueBasis ?: detail?.valueBasis ?: "用户确认经济事项")}
    var categoryId by rememberSaveable(eventId) {mutableStateOf(if(saved!=null) saved.categoryId else detail?.categoryId)}
    var complete by rememberSaveable(eventId) {mutableStateOf(saved?.allocationsComplete ?: detail?.allocationsComplete ?: false)}
    var excludeStat by rememberSaveable(eventId) {mutableStateOf(saved?.statExcluded ?: (detail?.statPolicy==FinanceStatPolicy.EXCLUDE))}
    var excludeBudget by rememberSaveable(eventId) {mutableStateOf(saved?.budgetExcluded ?: (detail?.budgetPolicy==FinanceBudgetPolicy.EXCLUDE))}
    var partyName by rememberSaveable {mutableStateOf("")}
    var reimbursementOpen by rememberSaveable {mutableStateOf(false)}
    var reimbursementParty by rememberSaveable {mutableStateOf("")}
    var reimbursementPersonal by rememberSaveable {mutableStateOf("")}
    var templateError by remember {mutableStateOf<String?>(null)}
    val funds=rememberSaveable(eventId,saver=fundingFormSaver) {mutableStateListOf<FundingForm>().apply {
        if(saved!=null) addAll(v?.economicDraftFundingRows.orEmpty().filter {it.draftId==saved.id}.sortedBy {it.position}.map {FundingForm(it.localId,it.partySelection,it.amountText,it.entryLinkSelection,it.basis)})
        else {
        addAll(v?.fundingRows.orEmpty().filter {it.eventId==eventId && it.voidedAt==null}.map {FundingForm(it.id,it.partyId,FinanceMoney.input(it.amountMinor,currency),it.entryLinkId,it.basis)})
        if(isEmpty()) addAll(s.v4?.activeLinks.orEmpty().filter {it.eventId==eventId && it.role in setOf(FinanceFlowRole.PAYMENT,FinanceFlowRole.FEE)}.map {
            FundingForm(java.util.UUID.randomUUID().toString(),"self",FinanceMoney.input(it.allocatedMinor,currency),it.id,"已有真实付款")
        })
        }
    } }
    val shares=rememberSaveable(eventId,saver=shareFormSaver) {mutableStateListOf<ShareForm>().apply {
        if(saved!=null) addAll(v?.economicDraftShareRows.orEmpty().filter {it.draftId==saved.id}.sortedBy {it.position}.map {ShareForm(it.localId,it.amountText,it.beneficiarySelection,it.bearerSelection,it.fundingLocalId,it.relation)})
        else
        addAll(v?.shareRows.orEmpty().filter {it.eventId==eventId && it.voidedAt==null}.map {ShareForm(it.id,it.amountMinor?.let {n->FinanceMoney.input(n,currency)}.orEmpty(),it.beneficiaryId,it.bearerId ?: "self",it.fundingId,it.relation)})
    } }
    val draft=FinanceEconomicDraftEntity(draftId,eventId,expected,eventVersion,epoch,description,currency,at,preserved,value,basis,categoryId,complete,excludeStat,excludeBudget,0,0)
    val draftFunds=funds.mapIndexed {index,form->FinanceEconomicDraftFundingEntity(draftId,form.id,index,form.party,form.amount,form.link,form.basis)}
    val draftShares=shares.mapIndexed {index,form->FinanceEconomicDraftShareEntity(draftId,form.id,index,form.amount,form.beneficiary,form.bearer,form.funding,form.relation)}
    LaunchedEffect(draft,draftFunds,draftShares) {kotlinx.coroutines.delay(250);vm.persistEconomic(editor,initialRevision,draft,draftFunds,draftShares)}
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_STOP) {vm.flushEconomicOnStop(editor,initialRevision,draft,draftFunds,draftShares)}
    fun leave()=vm.leaveEconomic(editor,initialRevision,draft,draftFunds,draftShares,close)
    val parties=listOf("self" to "本人")+v?.partyRows.orEmpty().filter {it.id!="self" && it.voidedAt==null}.map {it.id to it.name}
    FinanceReceipt(if(event==null) "新建经济事项" else "编辑经济分配",state.busy,state.error,::leave,actions={
        Button({vm.confirmEconomicDraft(editor,initialRevision,draft,draftFunds,draftShares,done=close)},enabled=!state.busy,modifier=Modifier.fillMaxWidth()) {Text("确认事项与分配")}
        TextButton({vm.confirmEconomicDraft(editor,initialRevision,draft,draftFunds,draftShares,discard=true,done=close)},enabled=!state.busy) {Text("舍弃本次分配草稿，保留正式事实")}
    }) {
        Text("事项价值、本人现金、个人承担和往来分别保存。没有本人付款时可直接记录外部赠送、代付或未知关系。",style=LifeText.caption)
        Text("未填完的字段、出资与份额会保存为草稿；收起可稍后继续，确认前不生成账务。",style=LifeText.caption)
        OutlinedTextField(description,{description=it},label={Text("事项备注（可留空）")})
        OutlinedTextField(currency,{currency=it},label={Text("币种")},enabled=event==null && funds.isEmpty() && shares.isEmpty())
        OutlinedTextField(value,{value=it},label={Text("事项价值（未知可留空）")})
        OutlinedTextField(at,{at=it},label={Text("经济发生时间")})
        OutlinedTextField(basis,{basis=it},label={Text("确认依据")})
        FinanceCategoryFields(s,categoryId,{categoryId=it},state.busy,vm::createCategory)
        Row {Checkbox(complete,{complete=it});Text("分配已完整；否则保留未分配部分")}
        Row {Checkbox(excludeStat,{excludeStat=it});Text("不计入统计")}
        Row {Checkbox(excludeBudget,{excludeBudget=it});Text("不计入预算")}
        OutlinedTextField(partyName,{partyName=it},label={Text("新增参与方名称")})
        TextButton({vm.economicAction(epoch,{partyName=""}) {repo,token->repo.saveParty(token,partyName)}},enabled=!state.busy && partyName.isNotBlank()) {Text("建立参与方")}
        TextButton({reimbursementOpen=!reimbursementOpen},enabled=!state.busy) {Text("按实际垫付与个人承担填写报销")}
        if(reimbursementOpen) FinancePanel {
            Text("先在账簿记真实垫付，再选报销方与个人承担。确认分配后，差额成为待收；分次到账不会再次计消费。")
            FinanceChoice("确认应承担的报销方",reimbursementParty,parties.filter {it.first!="self"}) {reimbursementParty=it}
            OutlinedTextField(reimbursementPersonal,{reimbursementPersonal=it},label={Text("确认个人承担（例如垫付500中的50）")})
            templateError?.let {Text(it,color=MaterialTheme.colorScheme.error)}
            TextButton({runCatching {
                val parts=FinanceEconomicDraftTemplates.reimbursement(draftId,draftFunds,FinanceMoney.parse(reimbursementPersonal,currency,true),reimbursementParty,currency)
                shares.clear();shares.addAll(parts.map {ShareForm(it.localId,it.amountText,it.beneficiarySelection,it.bearerSelection,it.fundingLocalId,it.relation)})
                value=FinanceMoney.input(exactSum(draftFunds.map {FinanceMoney.parse(it.amountText,currency)}),currency)
                complete=true;basis="确认本人垫付、个人承担与报销方承担";templateError=null;reimbursementOpen=false
            }.onFailure {templateError=it.message ?: "请先核对垫付与承担"}},enabled=!state.busy) {Text("替换本草稿份额，继续核对后确认")}
        }
        Text("出资来源",style=LifeText.title)
        funds.toList().forEachIndexed { index,form->
            FinancePanel {
                FinanceChoice("出资方",form.party,parties) {funds[index]=form.copy(party=it,link=if(it=="self") form.link else null)}
                OutlinedTextField(form.amount,{funds[index]=form.copy(amount=it)},label={Text("出资金额")})
                if(form.party=="self") {
                    val links=s.v4?.activeLinks.orEmpty().filter {it.eventId==eventId && it.role in setOf(FinanceFlowRole.PAYMENT,FinanceFlowRole.FEE)}
                    FinanceChoice("真实付款",form.link.orEmpty(),links.map {link->link.id to (s.entries.single {it.id==link.entryId}.let {e->
                        (s.accounts.single {it.id==e.accountId}.name)+" · "+FinanceMoney.display(link.allocatedMinor,currency)+" · "+FinanceTime.input(e.occurredAt)})}) {funds[index]=form.copy(link=it)}
                    if(links.isEmpty()) Text("先记录本人真实付款，再从已有事项进入经济分配；不会凭出资金额生成流水。",style=LifeText.caption)
                }
                OutlinedTextField(form.basis,{funds[index]=form.copy(basis=it)},label={Text("出资确认依据")})
                TextButton({funds.removeAt(index)}) {Text("移除此出资分配")}
            }
        }
        TextButton({funds.add(FundingForm(java.util.UUID.randomUUID().toString(),parties.firstOrNull {it.first!="self"}?.first ?: "self"))}) {Text("添加出资方")}
        Text("受益与承担",style=LifeText.title)
        shares.toList().forEachIndexed {index,form->FinancePanel {
            OutlinedTextField(form.amount,{shares[index]=form.copy(amount=it)},label={Text("份额金额（未知可留空）")})
            FinanceChoice("受益方",form.beneficiary,parties) {shares[index]=form.copy(beneficiary=it)}
            FinanceChoice("由哪项出资支持",form.funding.orEmpty(),funds.map {it.id to ((parties.firstOrNull {p->p.first==it.party}?.second ?: "参与方")+" · "+it.amount)}) {shares[index]=form.copy(funding=it)}
            FinanceShareRelation.entries.forEach {relation->FilterChip(form.relation==relation,{shares[index]=form.copy(relation=relation)},label={Text(when(relation) {
                FinanceShareRelation.UNRESOLVED->"关系待确认";FinanceShareRelation.GIFT_CONFIRMED->"确认赠送"
                FinanceShareRelation.PERSONAL_CONFIRMED->"出资方自行承担";FinanceShareRelation.REPAYABLE_CONFIRMED->"确认需要归还"
            })})}
            if(form.relation==FinanceShareRelation.REPAYABLE_CONFIRMED) FinanceChoice("应归还方",form.bearer,parties) {shares[index]=form.copy(bearer=it)}
            TextButton({shares.removeAt(index)}) {Text("移除此份额")}
        }}
        TextButton({shares.add(ShareForm(java.util.UUID.randomUUID().toString(),funding=funds.firstOrNull()?.id))}) {Text("添加不等额份额")}
        if(detail==null && event!=null) Text("切换为经济分配后，个人成本按以上确认承担计算；旧流水和余额保持原值。",style=LifeText.caption)
    }
}

@Composable
internal fun FinanceChoice(label:String,selected:String,options:List<Pair<String,String>>,choose:(String)->Unit) {
    var expanded by remember {mutableStateOf(false)}
    Column {
        TextButton({expanded=!expanded}) {Text(label+" · "+(options.singleOrNull {it.first==selected}?.second ?: "请选择"))}
        if(expanded) {
            var search by rememberSaveable(label) {mutableStateOf("")}
            if(options.size>30) OutlinedTextField(search,{search=it.take(200)},label={Text("搜索$label")})
            key(search) {FinancePagedItems(options.filter {search.isBlank() || it.second.contains(search,true)},"choice-$label",{it.first}) {(id,name)->
                TextButton({choose(id);expanded=false}) {Text(name)}
            }}
        }
    }
}
