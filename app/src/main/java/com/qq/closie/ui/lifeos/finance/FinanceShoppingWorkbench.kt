package com.qq.closie.ui.lifeos.finance

import com.qq.closie.life.ui.theme.BundledText as Text
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.ui.Modifier
import com.qq.closie.life.finance.*
import com.qq.closie.life.shopping.*
import com.qq.closie.ui.lifeos.theme.LifeText

private data class LineForm(val id:String=java.util.UUID.randomUUID().toString(),val title:String="",val quantity:String="1",val value:String="")
private val LineFormsSaver=listSaver<androidx.compose.runtime.snapshots.SnapshotStateList<LineForm>,String>(
    save={it.flatMap {line->listOf(line.id,line.title,line.quantity,line.value)}},
    restore={if(it.size%4!=0) null else it.chunked(4).map {parts->LineForm(parts[0],parts[1],parts[2],parts[3])}.toMutableStateList()})
@Composable
internal fun FinanceShoppingWorkbench(state:FinanceUiState,vm:FinanceViewModel) {
    var open by rememberSaveable {mutableStateOf(false)}
    var editing by rememberSaveable {mutableStateOf<String?>(null)}
    var allocationLine by rememberSaveable {mutableStateOf<String?>(null)}
    var editingAllocation by rememberSaveable {mutableStateOf<String?>(null)}
    var viewingEntry by rememberSaveable {mutableStateOf<String?>(null)}
    var viewingEvent by rememberSaveable {mutableStateOf<String?>(null)}
    val s=state.snapshot;val v=s.v16
    val mediaEpoch=rememberSaveable {v?.controlRows?.singleOrNull()?.restoreEpoch ?: 0L}
    Text("购买与商品成本",style=LifeText.title)
    Text("购买明细本身不产生流水。关联已确认经济事项后，展示原承担和实际退款时间线；未分配部分保留未知。",style=LifeText.caption)
    Button({editing=null;open=true}) {Text("建立购买记录")}
    FinancePagedItems(v?.purchaseRows.orEmpty().filter {it.voidedAt==null}.sortedByDescending {it.occurredAt},"purchases",{it.id}) {purchase->FinancePanel {
        Text(purchase.title,style=LifeText.title)
        TextButton({editing=purchase.id;open=true}) {Text("编辑商品明细")}
        FinanceAttachments(s,FinanceMediaTarget(purchaseId=purchase.id),state.busy,state.error) {block->vm.mediaAction(mediaEpoch,block)}
        FinancePagedItems(ShoppingCostProjection.lines(s,purchase.id),"purchase-lines-${purchase.id}",{it.line.id}) {cost->
            Text(cost.line.title+" × ${cost.line.quantity}")
            Text("确认累计个人成本 "+FinanceMoney.display(cost.confirmedCostMinor,purchase.currencyCode)+if(cost.incomplete) " · 仍有未分配或未知" else "")
            FinancePagedItems(cost.points,"cost-points-${cost.line.id}",{it.sourceId}) {point->
                Text(FinanceTime.input(point.at)+" · "+(if(point.adjustment) "实际调整 " else "确认承担 ")+ (point.amountMinor?.let {FinanceMoney.display(it,purchase.currencyCode)} ?: "未知"),style=LifeText.caption)
                TextButton({viewingEvent=point.eventId}) {Text("查看原事项与承担")}
                if(point.entryIds.isNotEmpty()) {
                    var cashOpen by rememberSaveable(point.sourceId) {mutableStateOf(false)}
                    TextButton({cashOpen=!cashOpen}) {Text("相关原始流水 · ${point.entryIds.size} 笔")}
                    if(cashOpen) {
                        Text("以下展示整笔真实收付，可能同时涉及其他商品；此商品成本以上方分配和调整为准。",style=LifeText.caption)
                        FinancePagedItems(s.entries.filter {it.id in point.entryIds && it.voidedAt==null},"point-cash-${point.sourceId}",{it.id}) {entry->
                            TextButton({viewingEntry=entry.id}) {Text(FinanceTime.input(entry.occurredAt)+" · "+FinanceMoney.display(entry.amountMinor,purchase.currencyCode)+" · "+entry.description)}
                        }
                    }
                }
            }
            if(cost.unassignedAdjustmentCount>0) Text("关联事项另有 ${cost.unassignedAdjustmentCount} 笔调整未分配到具体商品，没有自动均摊。",style=LifeText.caption)
            TextButton({allocationLine=cost.line.id}) {Text("关联事项并分配商品成本")}
            FinancePagedItems(v?.purchaseAllocationRows.orEmpty().filter {it.lineId==cost.line.id && it.voidedAt==null},"line-allocations-${cost.line.id}",{it.id}) {allocation->
                TextButton({editingAllocation=allocation.id}) {Text("更正或解除分配 · "+FinanceMoney.display(allocation.valueMinor,purchase.currencyCode)+" · "+s.v4?.events.orEmpty().single {it.id==allocation.eventId}.description.ifBlank {"经济事项"})}
            }
        }
    }}
    if(open) {
        val purchase=remember {v?.purchaseRows.orEmpty().singleOrNull {it.id==editing}}
        val epoch=rememberSaveable {v?.controlRows?.singleOrNull()?.restoreEpoch ?: 0L}
        val purchaseRevision=rememberSaveable(editing) {purchase?.revision}
        var title by rememberSaveable {mutableStateOf(purchase?.title.orEmpty())}
        var currency by rememberSaveable {mutableStateOf(purchase?.currencyCode ?: "CNY")}
        var date by rememberSaveable {mutableStateOf(FinanceTime.input(purchase?.occurredAt ?: System.currentTimeMillis()))}
        val forms=rememberSaveable(saver=LineFormsSaver) {mutableStateListOf<LineForm>().apply {
            addAll(v?.purchaseLineRows.orEmpty().filter {it.purchaseId==editing && it.voidedAt==null}.map {LineForm(it.id,it.title,it.quantity.toString(),it.valueMinor?.let {n->FinanceMoney.input(n,currency)}.orEmpty())})
            if(isEmpty()) add(LineForm())
        }}
        FinanceReceipt("购买明细",state.busy,state.error,{open=false},actions={Button({vm.shoppingAction(epoch,{open=false}) {repo,token->
            repo.save(token,ShoppingPurchaseInput(purchase?.id,purchaseRevision,title,currency,FinanceTime.resolve(date,purchase?.occurredAt),forms.map {
                ShoppingLineInput(it.id,it.title,it.quantity.toInt(),it.value.takeIf(String::isNotBlank)?.let {n->FinanceMoney.parse(n,currency,true)})
            }))
        }},enabled=!state.busy){Text("保存购买明细")}}) {
            OutlinedTextField(title,{title=it},label={Text("购买记录名称")})
            OutlinedTextField(currency,{currency=it},label={Text("币种")},enabled=purchase==null)
            OutlinedTextField(date,{date=it},label={Text("购买发生时间")})
            forms.toList().forEachIndexed {index,line->Column {
                OutlinedTextField(line.title,{forms[index]=line.copy(title=it)},label={Text("商品名称")})
                OutlinedTextField(line.quantity,{forms[index]=line.copy(quantity=it)},label={Text("数量")})
                OutlinedTextField(line.value,{forms[index]=line.copy(value=it)},label={Text("该行商品总价值（未知可留空）")})
                TextButton({forms.removeAt(index)}){Text("移除此明细")}
            }}
            TextButton({forms.add(LineForm())},enabled=forms.size<200){Text("添加商品明细（${forms.size}/200）")}
        }
    }
    allocationLine?.let {lineId->
        val epoch=rememberSaveable {v?.controlRows?.singleOrNull()?.restoreEpoch ?: 0L}
        val line=v?.purchaseLineRows.orEmpty().singleOrNull {it.id==lineId && it.voidedAt==null}
        val purchase=v?.purchaseRows.orEmpty().singleOrNull {it.id==line?.purchaseId && it.voidedAt==null}
        if(line==null || purchase==null) FinanceReceipt("商品明细已改变",onClose={allocationLine=null}) {Text("请重新打开当前有效的商品明细。")}
        else {
        val events=v?.economicRows.orEmpty().filter {it.voidedAt==null}.filter {detail->s.v4?.events.orEmpty().any {it.id==detail.eventId && it.currencyCode==purchase.currencyCode && it.voidedAt==null}}
        var event by rememberSaveable {mutableStateOf("")}
        var value by rememberSaveable {mutableStateOf("")}
        var personal by rememberSaveable {mutableStateOf("")}
        val selected=events.singleOrNull {it.eventId==event}
        var selectedRevision by rememberSaveable {mutableStateOf<Long?>(null)}
        FinanceReceipt("分配到 ${line.title}",state.busy,state.error,{allocationLine=null},actions={Button({vm.economicAction(epoch,{allocationLine=null}) {repo,token->
            repo.allocatePurchase(token,lineId,event,FinanceMoney.parse(value,purchase.currencyCode,true),personal.takeIf(String::isNotBlank)?.let {FinanceMoney.parse(it,purchase.currencyCode,true)},requireNotNull(selectedRevision))
        }},enabled=!state.busy && selected!=null){Text("确认商品分配")}}) {
            FinanceChoice("经济事项",event,events.map {d->d.eventId to s.v4?.events.orEmpty().single {it.id==d.eventId}.description.ifBlank {"经济事项 · "+FinanceTime.input(s.v4!!.events.single {it.id==d.eventId}.occurredAt)}}){event=it;selectedRevision=events.single {d->d.eventId==it}.revision}
            OutlinedTextField(value,{value=it},label={Text("此事项分配的商品价值")})
            OutlinedTextField(personal,{personal=it},label={Text("此商品个人承担（未知可留空）")})
            Text("可关联多次付款对应的事项。总分配不得超过商品价值、事项价值或已确认个人承担。",style=LifeText.caption)
        }}
    }
    editingAllocation?.let {id->key(id) {FinancePurchaseAllocationEditor(state,vm,id){editingAllocation=null}}}
    viewingEntry?.let {id->
        val row=remember(s,id) {FinanceProjection.ledger(s).firstOrNull {it.entry.id==id || it.transfer?.inflowEntryId==id}}
        if(row==null) FinanceReceipt("原流水已改变",onClose={viewingEntry=null}) {Text("请返回时间线重新核对有效记录。")}
        else key(id) {FinanceEntrySheet(state,row,vm){viewingEntry=null}}
    }
    viewingEvent?.let {id->key(id) {FinanceEconomicEditor(state,vm,id){viewingEvent=null}}}
}
