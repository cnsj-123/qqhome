package com.qq.closie.ui.lifeos.finance

import com.qq.closie.life.ui.theme.BundledText as Text
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import com.qq.closie.life.finance.*
import com.qq.closie.ui.lifeos.theme.LifeText

@Composable
internal fun FinanceBatchEditor(state:FinanceUiState,vm:FinanceViewModel) {
    val s=state.snapshot
    var ids by remember {mutableStateOf(emptySet<String>())}
    var account by remember {mutableStateOf("")}
    var replaceCategory by remember {mutableStateOf(false)}
    var category by remember {mutableStateOf("")}
    var add by remember {mutableStateOf(emptySet<String>())}
    var remove by remember {mutableStateOf(emptySet<String>())}
    var preview by remember {mutableStateOf<FinanceChangePreview?>(null)}
    var pending by remember {mutableStateOf<FinanceBatchCorrection?>(null)}
    Text("安全批量修正",style=LifeText.title)
    val transferLegs=s.transfers.flatMap {listOf(it.outflowEntryId,it.inflowEntryId)}.toSet()
    FinanceMultiChoice("选择真实流水",ids,s.entries.filter {it.voidedAt==null && it.id !in transferLegs}.map {it.id to
        (FinanceTime.input(it.occurredAt)+" · "+it.description.ifBlank {"流水"}+" · "+FinanceMoney.display(it.amountMinor,s.accounts.single {a->a.id==it.accountId}.currencyCode))}){ids=it}
    FinanceChoice("更改账户",account,listOf("" to "保持原账户")+s.accounts.filter {it.archivedAt==null}.map {it.id to it.name}){account=it}
    Row {Checkbox(replaceCategory,{replaceCategory=it});Text("统一更改成本分类（须选全事项构成）")}
    if(replaceCategory) FinanceChoice("新分类",category,listOf("" to "未分类")+s.categories.map {it.id to (FinanceProjection.categoryPath(s.categories,it.id) ?: it.name)}){category=it}
    FinanceMultiChoice("添加标签",add,s.tags.map {it.id to it.name}){add=it;remove=remove-it}
    FinanceMultiChoice("移除标签",remove,s.tags.map {it.id to it.name}){remove=it;add=add-it}
    Text("不更改金额、方向或发生时间。转账需整体编辑；币种冲突或记录发生变化时整批拒绝。",style=LifeText.caption)
    TextButton({val edit=FinanceBatchCorrection(account.ifBlank {null},replaceCategory,category.ifBlank {null},add,remove)
        vm.previewBatch(ids,edit){pending=edit;preview=it}},enabled=!state.busy && ids.isNotEmpty()) {Text("预览整批影响")}
    preview?.let {p->AlertDialog(onDismissRequest={preview=null},title={Text("确认批量修正")},text={FinanceChangeImpact(p)},
        confirmButton={TextButton({vm.confirmBatch(p,requireNotNull(pending)){preview=null;ids=emptySet()}},enabled=!state.busy){Text("原子提交整批修正")}},
        dismissButton={TextButton({preview=null}){Text("取消")}})}
}
