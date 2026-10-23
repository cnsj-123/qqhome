package com.qq.closie.ui.lifeos.finance

import com.qq.closie.life.ui.theme.BundledText as Text
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import com.qq.closie.life.finance.*

@Composable
internal fun FinanceDiagnosticViewer(state:FinanceUiState,vm:FinanceViewModel,diagnostic:FinanceDiagnostic,close:()->Unit) {
    if(!state.loaded) {FinanceReceipt("正在打开原始事实",error=state.error,onClose=close) {Text("读取完整账本快照后继续。")};return}
    val s=state.snapshot
    var entry by rememberSaveable {mutableStateOf<String?>(null)}
    var economic by rememberSaveable {mutableStateOf<String?>(null)}
    FinanceReceipt("线索与原始事实",state.busy,state.error,close) {
        Text(diagnostic.message)
        Text("这些是可复核线索，未自动作废、调账或改变义务。")
        diagnostic.entryIds.forEach {id->
            val row=FinanceProjection.ledger(s).firstOrNull {it.entry.id==id || it.transfer?.inflowEntryId==id}
            if(row!=null) TextButton({entry=row.id}) {Text(FinanceTime.input(row.entry.occurredAt)+" · "+row.account.name+" · "+FinanceMoney.display(row.entry.amountMinor,row.currencyCode)+" · "+row.entry.description)}
            else Text("原流水已作废或不在当前有效记录中。")
        }
        diagnostic.eventIds.forEach {id->
            val event=s.v4?.events.orEmpty().singleOrNull {it.id==id && it.voidedAt==null}
            if(event!=null) {
                Text(event.description.ifBlank {"经济事项"})
                val total=FinanceCostProjection.eventTotal(s,id)
                Text("累计确认个人成本 "+FinanceMoney.display(total.confirmedMinor,event.currencyCode)+if(total.complete) "" else "，另有承担待确认")
                TextButton({economic=id}) {Text("打开事项与承担分配")}
            } else Text("原事项已不在当前有效记录中。")
        }
        diagnostic.accountIds.forEach {id->s.accounts.singleOrNull {it.id==id}?.let {account->
            Text(account.name);FinanceAccountWorkbench(state,account,vm)
        }}
    }
    entry?.let {id->FinanceCanonicalRecordSheet(state,vm,id){entry=null}}
    economic?.let {id->key(id) {FinanceEconomicEditor(state,vm,id){economic=null}}}
}
