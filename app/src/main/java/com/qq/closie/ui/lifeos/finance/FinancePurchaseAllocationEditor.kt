package com.qq.closie.ui.lifeos.finance

import com.qq.closie.life.ui.theme.BundledText as Text
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import com.qq.closie.life.finance.*

@Composable
internal fun FinancePurchaseAllocationEditor(state:FinanceUiState,vm:FinanceViewModel,allocationId:String,close:()->Unit) {
    val s=state.snapshot;val v=s.v16
    val row=remember(allocationId) {v?.purchaseAllocationRows.orEmpty().singleOrNull {it.id==allocationId && it.voidedAt==null}}
    if(row==null) {FinanceReceipt("分配已改变",onClose=close) {Text("请重新打开当前有效的分配记录。")};return}
    val event=remember(allocationId) {s.v4?.events.orEmpty().single {it.id==row.eventId}}
    val eventRevision=rememberSaveable(allocationId) {v?.economicRows.orEmpty().single {it.eventId==row.eventId}.revision}
    val allocationRevision=rememberSaveable(allocationId) {row.revision}
    val epoch=rememberSaveable {v?.controlRows?.singleOrNull()?.restoreEpoch ?: 0L}
    var value by rememberSaveable {mutableStateOf(FinanceMoney.input(row.valueMinor,event.currencyCode))}
    var personal by rememberSaveable {mutableStateOf(row.personalMinor?.let {FinanceMoney.input(it,event.currencyCode)}.orEmpty())}
    var release by rememberSaveable {mutableStateOf(false)}
    var remove by remember {mutableStateOf(false)}
    val adjustments=v?.costAdjustmentRows.orEmpty().filter {it.eventId==row.eventId && it.lineId==row.lineId && it.voidedAt==null}
    FinanceReceipt("更正商品成本分配",state.busy,state.error,close,actions={Button({vm.economicAction(epoch,close) {repo,token->
        repo.editPurchaseAllocation(token,row.id,allocationRevision,eventRevision,FinanceMoney.parse(value,event.currencyCode,true),
            personal.takeIf(String::isNotBlank)?.let {FinanceMoney.parse(it,event.currencyCode,true)},release)
    }},enabled=!state.busy) {Text("确认更正分配")}}) {
        Text(event.description.ifBlank {"经济事项"})
        OutlinedTextField(value,{value=it},label={Text("分配的商品价值 · ${event.currencyCode}")})
        OutlinedTextField(personal,{personal=it},label={Text("此商品个人承担（未知留空）")})
        Text("只改变商品与事项的成本归属，不修改现金、事项总价值、个人总承担或往来。")
        if(adjustments.isNotEmpty()) {
            Text("此事项与此商品还关联 ${adjustments.size} 笔实际成本调整。保留归属时，更正后仍需覆盖这些调整。")
            Row {Checkbox(release,{release=it});Text("把上述全部调整改为此事项的未分配商品调整")}
            Text("选择后只清除商品归属；原到账和事项的成本冲减保留。")
        }
        TextButton({remove=true},enabled=!state.busy) {Text("解除这项商品分配")}
    }
    if(remove) AlertDialog(onDismissRequest={remove=false},title={Text("解除此项分配？")},text={Text(
        if(release) "这项分配将停用；此事项该商品的实际调整归为未分配商品。现金和事项总成本保留。"
        else "这项分配将停用。若仍有依赖它的实际调整，此操作会拒绝，请先核对调整归属。")},
        confirmButton={TextButton({vm.economicAction(epoch,close) {repo,token->repo.removePurchaseAllocation(token,row.id,allocationRevision,eventRevision,release)}},enabled=!state.busy){Text("确认解除")}},
        dismissButton={TextButton({remove=false}){Text("取消")}})
}
