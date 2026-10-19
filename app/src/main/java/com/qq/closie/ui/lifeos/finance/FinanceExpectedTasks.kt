package com.qq.closie.ui.lifeos.finance

import com.qq.closie.life.ui.theme.BundledText as Text
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import com.qq.closie.life.finance.*
import com.qq.closie.ui.lifeos.theme.LifeText

@Composable
internal fun FinanceExpectedTasks(state:FinanceUiState,vm:FinanceViewModel) {
    val s=state.snapshot
    val epoch=rememberSaveable {s.v16?.controlRows?.singleOrNull()?.restoreEpoch ?: 0L}
    var show by rememberSaveable {mutableStateOf(false)}
    var finished by rememberSaveable {mutableStateOf(false)}
    var creating by rememberSaveable {mutableStateOf(false)}
    var eventId by rememberSaveable {mutableStateOf("")}
    var role by rememberSaveable {mutableStateOf(FinanceFlowRole.REBATE.name)}
    var amount by rememberSaveable {mutableStateOf("")}
    var note by rememberSaveable {mutableStateOf("")}
    var planId by rememberSaveable {mutableStateOf<String?>(null)}
    var eventVersion by rememberSaveable {mutableStateOf<Long?>(null)}
    var reason by rememberSaveable {mutableStateOf("")}
    var ending by remember {mutableStateOf<FinanceExpectedTask?>(null)}
    var receiving by remember {mutableStateOf<FinanceExpectedTask?>(null)}
    TextButton({show=!show}) {Text("预计返利与返现任务")}
    if(!show) return
    Text("预计到账只作待办，不增加收入。同事项同性质合并核对实际到账；放弃或结束不会制造现金。",style=LifeText.caption)
    Row {FilterChip(!finished,{finished=false},label={Text("进行中")});FilterChip(finished,{finished=true},label={Text("已到账或明确结束")})}
    TextButton({creating=!creating;planId=null;eventId="";eventVersion=null;amount="";note=""}) {Text("添加预计返利/返现")}
    if(creating) FinancePanel {
        if(planId==null) {
            FinanceChoice("关联事项",eventId,s.v4?.events.orEmpty().filter {it.voidedAt==null && it.relatedTransferId==null}.map {it.id to it.description.ifBlank {FinanceTime.input(it.occurredAt)}}){eventId=it;eventVersion=s.v4?.events.orEmpty().single {e->e.id==it}.updatedAt;amount=""}
            FinanceChoice("预计性质",role,listOf(FinanceFlowRole.REBATE.name to "返利",FinanceFlowRole.CASHBACK.name to "返现")){role=it}
        } else Text("修改此事项的现有预计金额与说明")
        OutlinedTextField(amount,{amount=it},label={Text("目标累计到账金额（含已到账，未知可留空）")})
        Text("按此事项该性质的全部实际到账核对目标。已有同性质计划请修改；结束后明确建立新目标时，也含历史已到账。",style=LifeText.caption)
        OutlinedTextField(note,{note=it},label={Text("待办说明（选填）")})
        TextButton({vm.expectedAction(epoch,{creating=false;amount="";note=""}) {repo,token->
            val event=requireNotNull(s.v4?.events.orEmpty().singleOrNull {it.id==eventId})
            repo.save(token,event.id,eventVersion ?: event.updatedAt,FinanceFlowRole.valueOf(role),amount.takeIf(String::isNotBlank)?.let {FinanceMoney.parse(it,event.currencyCode)},note,planId)
        }},enabled=!state.busy && eventId.isNotBlank()) {Text("保存预计待办")}
    }
    FinancePagedItems(FinanceExpectedTaskProjection.rows(s).filter {it.ended==finished},"expected-$finished",{it.event.id+":"+it.role.name}) {task->key(task.event.id,task.role) {FinancePanel {
        Text(task.event.description.ifBlank {"经济事项"}+" · "+task.role.label,style=LifeText.title)
        Text((if(task.historicalTargetsSeparate) "历史累计目标逐项列于下方，不相加" else "预计 "+(task.targetMinor?.let {FinanceMoney.display(it,task.event.currencyCode)} ?: "未知"))+" · 实际到账 "+FinanceMoney.display(task.receivedMinor,task.event.currencyCode))
        Text(if(task.plans.all {it.cancelledAt!=null}) "已明确结束；到账仅以上方实际现金为准。" else "尚待 "+(task.remainingMinor?.let {FinanceMoney.display(it,task.event.currencyCode)} ?: "金额待确认"))
        task.plans.forEach {plan->
            Text((plan.amountMinor?.let {FinanceMoney.display(it,task.event.currencyCode)} ?: "预计未知")+if(plan.cancelledAt!=null) " · 已明确结束" else "",style=LifeText.caption)
            Text(plan.note.ifBlank {"未填说明"},style=LifeText.caption)
            if(plan.cancelledAt==null) TextButton({creating=true;planId=plan.id;eventId=task.event.id;eventVersion=task.event.updatedAt;role=task.role.name;
                amount=plan.amountMinor?.let {FinanceMoney.input(it,task.event.currencyCode)}.orEmpty();note=plan.note},enabled=!state.busy) {Text("修改预计金额与说明")}
        }
        if(task.plans.any {it.cancelledAt==null}) Row {
            if(!task.ended) TextButton({receiving=task},enabled=!state.busy) {Text(if(s.v16?.economicRows.orEmpty().any {it.eventId==task.event.id && it.voidedAt==null}) "记录或关联实际到账" else "记录实际到账")}
            TextButton({ending=task;reason=""},enabled=!state.busy) {Text(if(task.ended) "明确结束此计划" else "明确放弃/结束剩余待办")}
        }
        if(!task.ended && s.v16?.economicRows.orEmpty().none {it.eventId==task.event.id && it.voidedAt==null}) Text("已经记过到账时，请从账簿打开原流水修改事项关联，勿再记一笔。",style=LifeText.caption)
    }}}
    ending?.let {task->FinanceReceipt("结束预计待办",state.busy,state.error,{ending=null},actions={Button({vm.expectedAction(epoch,{ending=null}) {repo,token->
        repo.end(token,task.event.id,task.event.updatedAt,task.role,reason)
    }},enabled=!state.busy && reason.isNotBlank()) {Text("确认结束，不生成流水")}}) {
        Text("已经实际到账的记录与成本调整保留；仅结束此事项该性质的剩余预计任务。")
        OutlinedTextField(reason,{reason=it},label={Text("明确结束依据或放弃说明")})
    }}
    receiving?.let {task->key(task.event.id,task.role) {
        if(s.v16?.economicRows.orEmpty().any {it.eventId==task.event.id && it.voidedAt==null})
            FinanceCashActionSheet(state,vm,eventId=task.event.id,initialRole=task.role){receiving=null}
        else FinanceEntrySheet(state,null,vm,initialRelationship=FinanceRelationshipDraft(nature=task.event.nature,role=task.role,
            eventId=task.event.id,eventUpdatedAt=task.event.updatedAt),initialKind=FinanceEditorKind.INCOME){receiving=null}
    }}
}
