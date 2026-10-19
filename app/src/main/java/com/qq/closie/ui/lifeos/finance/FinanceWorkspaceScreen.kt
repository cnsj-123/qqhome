package com.qq.closie.ui.lifeos.finance

import com.qq.closie.life.ui.theme.BundledText as Text
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.qq.closie.life.finance.*
import com.qq.closie.ui.lifeos.theme.*

@Composable
internal fun FinanceWorkspaceScreen(state:FinanceUiState,vm:FinanceViewModel,close:()->Unit) {
    if(!state.loaded) {FinanceReceipt("正在打开财务工作页",error=state.error,onClose=close) {Text("读取完整账本快照后继续。")};return}
    var page by rememberSaveable {mutableIntStateOf(0)}
    val pageStates=rememberSaveableStateHolder()
    var economicOpen by rememberSaveable {mutableStateOf(false)}
    var eventId by rememberSaveable {mutableStateOf<String?>(null)}
    var resumeDraft by rememberSaveable {mutableStateOf<String?>(null)}
    var freshEconomic by rememberSaveable {mutableStateOf(false)}
    var adjustment by rememberSaveable {mutableStateOf<String?>(null)}
    var obligation by rememberSaveable {mutableStateOf<String?>(null)}
    var preview by remember {mutableStateOf<FinanceChangePreview?>(null)}
    val s=state.snapshot;val v=s.v16
    val epoch=rememberSaveable {v?.controlRows?.singleOrNull()?.restoreEpoch ?: 0L}
    BackHandler(!economicOpen && adjustment==null && obligation==null && preview==null) {close()}
    Scaffold(containerColor=LocalLifeOsColors.current.paper,topBar={Row(Modifier.fillMaxWidth().padding(12.dp)) {
        TextButton(close) {Text("返回财务")};Text("财务工作页",style=LifeText.title)
    }}) {padding->Column(Modifier.padding(padding).fillMaxSize()) {
        ScrollableTabRow(page) {listOf("事项与往来","购买明细","查询专题","预算与隐私","有限恢复","来源身份").forEachIndexed {index,label->
            Tab(selected=page==index,onClick={page=index},text={Text(label)})
        }}
        pageStates.SaveableStateProvider(page) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            state.error?.let {Text(it,color=MaterialTheme.colorScheme.error);TextButton(vm::clearError){Text("清除提示")}}
            when(page) {
                0->{
                    FinanceExpectedTasks(state,vm)
                    Button({eventId=null;resumeDraft=null;freshEconomic=true;economicOpen=true},enabled=!state.busy){Text("新建无本人付款的经济事项")}
                    FinancePagedItems(v?.economicDraftRows.orEmpty().filter {it.voidedAt==null}.sortedByDescending {it.updatedAt},"economic-drafts",{it.id}) {draft->
                        TextButton({eventId=draft.eventId;resumeDraft=draft.id;freshEconomic=false;economicOpen=true}) {Text("继续经济草稿 · "+draft.description.ifBlank {"未命名事项"})}
                    }
                    Text("本人付款先记真实流水，再在原事项中编辑分配。",style=LifeText.caption)
                    FinancePagedItems(s.v4?.events.orEmpty().filter {it.voidedAt==null && it.relatedTransferId==null}.sortedWith(compareByDescending<FinanceEventEntity> {it.occurredAt}.thenBy {it.id}),"economic-events",{it.id}) {event->
                        val detail=v?.economicRows.orEmpty().singleOrNull {it.eventId==event.id && it.voidedAt==null}
                        val total=FinanceCostProjection.eventTotal(s,event.id)
                        FinancePanel {
                            Text(event.description.ifBlank {"经济事项"},style=LifeText.title)
                            Text(FinanceTime.input(event.occurredAt)+" · "+event.currencyCode)
                            Text("确认个人成本 "+FinanceMoney.display(total.confirmedMinor,event.currencyCode)+if(total.complete) "" else "，另有未知承担")
                            TextButton({eventId=event.id;resumeDraft=null;freshEconomic=false;economicOpen=true}) {Text(if(detail==null) "查看并确认经济分配" else "编辑出资与承担")}
                            FinanceAttachments(s,FinanceMediaTarget(eventId=event.id),state.busy,state.error) {block->vm.mediaAction(epoch,block)}
                            if(detail!=null) TextButton({adjustment=event.id}) {Text("记录实际退款或返利")}
                            TextButton({vm.previewVoid(eventId=event.id){preview=it}},enabled=!state.busy) {Text("预览整组作废")}
                        }
                    }
                    Text("确认待收待付",style=LifeText.title)
                    val names=v?.partyRows.orEmpty().associate {it.id to it.name}
                    FinancePagedItems(FinanceObligationProjection.rows(s),"obligations",{it.obligation.id}) {row->FinancePanel {
                        Text((if(row.obligation.creditorId=="self") "待收自 " else "待付给 ")+names[if(row.obligation.creditorId=="self") row.obligation.debtorId else row.obligation.creditorId].orEmpty())
                        Text("原义务 "+FinanceMoney.display(row.obligation.amountMinor,row.obligation.currencyCode)+" · 尚余 "+FinanceMoney.display(row.openMinor,row.obligation.currencyCode))
                        Text("真实结算 "+FinanceMoney.display(row.settledMinor,row.obligation.currencyCode)+" / 非现金结束 "+FinanceMoney.display(row.nonCashResolvedMinor,row.obligation.currencyCode),style=LifeText.caption)
                        if(row.openMinor>0) TextButton({obligation=row.obligation.id}) {Text("部分收付或明确结束")}
                        if(row.openMinor==0L) Text(if(row.cashEvidenceComplete) "已结清，真实现金证据完整" else "工作已结束，历史现金证据仍不完整",style=LifeText.caption)
                    }}
                }
                1->FinanceShoppingWorkbench(state,vm)
                2->FinanceQueryWorkbench(state,vm)
                3->FinanceInsightsWorkbench(state,vm)
                4->{
                    FinanceBatchEditor(state,vm)
                    HorizontalDivider()
                    Text("每组保存有限的修改前事实；相关记录随后改变时拒绝覆盖恢复。还原备份后旧恢复组失效。",style=LifeText.caption)
                    FinancePagedItems(v?.recoveryGroupRows.orEmpty().sortedByDescending {it.createdAt},"recovery-groups",{it.id}) {group->FinancePanel {
                        Text((if(group.kind=="VOID") "整组作废" else "批量修正")+" · "+FinanceTime.input(group.createdAt))
                        Text(if(group.consumedAt!=null) "已恢复" else if(group.epoch!=epoch) "恢复代际已改变，此组失效" else "可尝试安全恢复")
                        TextButton({vm.recover(group.id,epoch){}},enabled=!state.busy && group.consumedAt==null && group.epoch==epoch) {Text("恢复这组操作")}
                    }}
                    if(v?.recoveryGroupRows.isNullOrEmpty()) Text("还没有可恢复操作。")
                }
                5->FinanceProvenanceWorkbench(state,vm)
            }
        }}
    }}
    if(economicOpen) key(eventId,resumeDraft,freshEconomic) {FinanceEconomicEditor(state,vm,eventId,resumeDraft,freshEconomic){economicOpen=false}}
    adjustment?.let {id->FinanceCashActionSheet(state,vm,eventId=id,close={adjustment=null})}
    obligation?.let {id->FinanceCashActionSheet(state,vm,obligationId=id,close={obligation=null})}
    preview?.let {p->AlertDialog(onDismissRequest={preview=null},title={Text("整组作废影响")},text={FinanceChangeImpact(p)},
        confirmButton={TextButton({vm.confirmVoid(p){preview=null}},enabled=!state.busy){Text("确认整组作废")}},
        dismissButton={TextButton({preview=null}){Text("取消")}})}
}

@Composable
internal fun FinanceCashActionSheet(state:FinanceUiState,vm:FinanceViewModel,eventId:String?=null,obligationId:String?=null,
    initialRole:FinanceFlowRole=FinanceFlowRole.REFUND,close:()->Unit) {
    val s=state.snapshot
    val obligation=remember {FinanceObligationProjection.rows(s).singleOrNull {it.obligation.id==obligationId}}
    val detail=remember {s.v16?.economicRows.orEmpty().singleOrNull {it.eventId==eventId && it.voidedAt==null}}
    val currency=obligation?.obligation?.currencyCode ?: s.v4?.events.orEmpty().single {it.id==eventId}.currencyCode
    val accounts=s.accounts.filter {it.archivedAt==null && it.currencyCode==currency}
    val epoch=remember {s.v16?.controlRows?.singleOrNull()?.restoreEpoch ?: 0L}
    var account by rememberSaveable {mutableStateOf(accounts.firstOrNull()?.id.orEmpty())}
    var amount by rememberSaveable {mutableStateOf(obligation?.openMinor?.let {FinanceMoney.input(it,currency)}.orEmpty())}
    var reduction by rememberSaveable {mutableStateOf("")}
    var at by rememberSaveable {mutableStateOf(FinanceTime.input(System.currentTimeMillis()))}
    var note by rememberSaveable {mutableStateOf("")}
    var method by rememberSaveable {mutableStateOf("cash")}
    var role by rememberSaveable {mutableStateOf(initialRole.name)}
    var line by rememberSaveable {mutableStateOf("")}
    var existingId by rememberSaveable {mutableStateOf("")}
    val existing=remember(existingId) {s.entries.singleOrNull {it.id==existingId && it.voidedAt==null}}
    FinanceReceipt(if(obligation!=null) "往来结算" else "实际成本调整",state.busy,state.error,close,actions={Button({
        vm.economicAction(epoch,close) {repo,token->
            if(method=="existing") {
                val entry=requireNotNull(existing){"请选择已经存在的真实流水"}
                if(obligation!=null) repo.linkExistingSettlement(token,obligation.obligation.id,obligation.obligation.revision,
                    entry.id,FinanceMoney.parse(amount,currency),entry.revision)
                else repo.adjustExistingCash(token,requireNotNull(eventId),requireNotNull(detail).revision,entry.id,entry.revision,
                    FinanceFlowRole.valueOf(role),FinanceMoney.parse(reduction.ifBlank {FinanceMoney.input(entry.amountMinor,currency)},currency,true),line.ifBlank {null})
            } else {
            val value=FinanceMoney.parse(amount,currency)
            if(obligation!=null && method!="cash") repo.resolveWithoutCash(token,obligation.obligation.id,obligation.obligation.revision,
                FinanceResolutionKind.valueOf(method),value,note)
            else {
                val cash=FinanceCashInput(account,value,FinanceTime.parse(at),note)
                if(obligation!=null) repo.recordSettlement(token,obligation.obligation.id,obligation.obligation.revision,cash)
                else repo.recordAdjustment(token,requireNotNull(eventId),requireNotNull(detail).revision,FinanceFlowRole.valueOf(role),cash,
                    FinanceMoney.parse(reduction.ifBlank {amount},currency,true),line.ifBlank {null})
            }
            }
        }
    },enabled=!state.busy){Text("确认并保存")}}) {
        FilterChip(method=="existing",{method="existing"},label={Text("采用已存在的真实流水，不再记一笔")})
        FilterChip(method=="cash",{method="cash"},label={Text("新记录实际收付")})
        if(obligation!=null) {
            FilterChip(method=="cash",{method="cash"},label={Text("真实收付")})
            FinanceResolutionKind.entries.forEach {kind->FilterChip(method==kind.name,{method=kind.name},label={Text(if(kind==FinanceResolutionKind.HISTORICAL_SETTLED) "历史已结清，现金证据未知" else "明确放弃或免除")})}
        } else {
            listOf(FinanceFlowRole.REFUND,FinanceFlowRole.REBATE,FinanceFlowRole.CASHBACK).forEach {r->FilterChip(role==r.name,{role=r.name},label={Text(r.label)})}
            OutlinedTextField(reduction,{reduction=it},label={Text("冲减个人成本（留空同到账额，可填0）")})
            val allocated=s.v16?.purchaseAllocationRows.orEmpty().filter {it.eventId==eventId && it.voidedAt==null}.map {it.lineId}.toSet()
            FinanceChoice("商品归属（可不分配）",line,listOf("" to "未分配到商品明细")+s.v16?.purchaseLineRows.orEmpty().filter {it.id in allocated}.map {it.id to it.title}) {line=it}
        }
        if(method!="existing" || obligation!=null) OutlinedTextField(amount,{amount=it},label={Text(if(method=="cash") "实际收付金额 · $currency" else if(method=="existing") "这笔计入本项往来金额 · $currency" else "本次结束金额 · $currency")})
        if(method=="cash") {
            AccountChoice("实际收付账户",accounts,account,!state.busy){account=it}
            OutlinedTextField(at,{at=it},label={Text("实际收付时间")})
        } else if(method=="existing") {
            val direction=if(obligation==null || obligation.obligation.creditorId=="self") FinanceDirection.INFLOW else FinanceDirection.OUTFLOW
            val legs=s.transfers.flatMap {listOf(it.outflowEntryId,it.inflowEntryId)}.toSet()
            FinanceChoice("选择已有收付",existingId,s.entries.filter {entry->entry.voidedAt==null && entry.id !in legs && entry.direction==direction &&
                s.accounts.single {it.id==entry.accountId}.currencyCode==currency}.map {entry->entry.id to
                (FinanceTime.input(entry.occurredAt)+" · "+s.accounts.single {it.id==entry.accountId}.name+" · "+FinanceMoney.display(entry.amountMinor,currency)+" · "+entry.description)}) {existingId=it}
            Text("只更新事项关系和成本/义务归属；已有流水金额、时间、备注与账户余额保留。已有其他确定分配的流水需先完整核对。",style=LifeText.caption)
        } else Text("这次只结束义务，不生成账户流水，不宣称历史现金证据齐全。",style=LifeText.caption)
        if(method!="existing") OutlinedTextField(note,{note=it},label={Text(if(method=="cash") "备注（选填）" else "历史声明或放弃依据（必填）")})
        if(obligation==null) Text("按实际退款/返利收付月份冲减成本；原支付金额及原月份不回写。",style=LifeText.caption)
    }
}
