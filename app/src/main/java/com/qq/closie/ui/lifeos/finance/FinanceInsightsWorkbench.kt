package com.qq.closie.ui.lifeos.finance

import com.qq.closie.life.ui.theme.BundledText as Text
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import com.qq.closie.life.finance.*
import com.qq.closie.ui.lifeos.theme.LifeText
import java.time.LocalDate

@Composable
internal fun FinanceInsightsWorkbench(state:FinanceUiState,vm:FinanceViewModel) {
    val context=androidx.compose.ui.platform.LocalContext.current
    var exportWarning by remember {mutableStateOf(false)}
    var exported by remember {mutableStateOf(false)}
    var editingBudget by rememberSaveable {mutableStateOf<String?>(null)}
    var diagnostic by remember {mutableStateOf<FinanceDiagnostic?>(null)}
    val exporter=androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/zip")) {uri->
        if(uri!=null) vm.exportFinance(context,uri){exported=true}
    }
    val s=state.snapshot;val v=s.v16
    val epoch=rememberSaveable {v?.controlRows?.singleOrNull()?.restoreEpoch ?: 0L}
    var name by rememberSaveable {mutableStateOf("")}
    var limit by rememberSaveable {mutableStateOf("")}
    var currency by rememberSaveable {mutableStateOf("CNY")}
    var category by rememberSaveable {mutableStateOf<String?>(null)}
    var start by rememberSaveable {mutableStateOf(LocalDate.now().withDayOfMonth(1).toString())}
    var end by rememberSaveable {mutableStateOf(LocalDate.now().withDayOfMonth(1).plusMonths(1).minusDays(1).toString())}
    Text("轻量预算",style=LifeText.title)
    Text("只为想控制的分类设限；不设预算也可正常记账。退款或返利可使本期已用为负。",style=LifeText.caption)
    OutlinedTextField(name,{name=it},label={Text("预算名称")})
    OutlinedTextField(currency,{currency=it},label={Text("预算币种")})
    OutlinedTextField(limit,{limit=it},label={Text("预算上限")})
    FinanceChoice("预算分类",category.orEmpty(),listOf("" to "全部分类")+s.categories.map {it.id to (FinanceProjection.categoryPath(s.categories,it.id) ?: it.name)}){category=it.ifBlank {null}}
    OutlinedTextField(start,{start=it},label={Text("开始日期 · 年-月-日")})
    OutlinedTextField(end,{end=it},label={Text("结束日期 · 含当天")})
    TextButton({vm.workspaceAction(epoch) {repo,token->
        val range=FinanceDateRange.days(LocalDate.parse(start),LocalDate.parse(end))
        repo.saveBudget(token,name,currency,FinanceMoney.parse(limit,currency,true),category,range.startInclusive!!,range.endExclusive!!)
    }},enabled=!state.busy){Text("建立可选预算")}
    FinancePagedItems(FinanceInsightsProjection.budgets(s),"budgets",{it.budget.id}) {progress->FinancePanel {
        Text(progress.budget.name,style=LifeText.title)
        Text("已用 "+FinanceMoney.display(progress.used.confirmedMinor,progress.budget.currencyCode)+" / 上限 "+FinanceMoney.display(progress.budget.limitMinor,progress.budget.currencyCode))
        Text("剩余 "+FinanceMoney.display(progress.remainingMinor,progress.budget.currencyCode)+if(progress.used.complete) "" else "，另有未知承担")
        Text(progress.explanation,style=LifeText.caption)
        TextButton({editingBudget=progress.budget.id},enabled=!state.busy) {Text("编辑预算范围与上限")}
        TextButton({vm.workspaceAction(epoch) {repo,token->val b=progress.budget
            repo.editBudget(token,b.id,b.revision,b.name,b.limitMinor,b.categoryId,b.startAt,b.endExclusive,true)
        }},enabled=!state.busy){Text("停用此预算")}
    }}
    Text("固定与弹性负担",style=LifeText.title)
    FinanceInsightsProjection.burden(s,state.costRows).forEach {(key,total)->Text((key.first?.let {if(it==FinanceBurden.FIXED) "固定负担" else "弹性消费"} ?: "尚未设置")+" · "+FinanceMoney.display(total.confirmedMinor,key.second)+if(total.complete) "" else " + 未知")}
    var policyCategory by rememberSaveable {mutableStateOf("")}
    var burden by rememberSaveable {mutableStateOf(FinanceBurden.FLEXIBLE.name)}
    var icon by rememberSaveable {mutableStateOf("other")}
    FinanceChoice("设置分类",policyCategory,s.categories.map {it.id to (FinanceProjection.categoryPath(s.categories,it.id) ?: it.name)}){policyCategory=it}
    FinanceBurden.entries.forEach {b->FilterChip(burden==b.name,{burden=b.name},label={Text(if(b==FinanceBurden.FIXED) "固定负担" else "弹性消费")})}
    FinanceCategoryIconPicker(icon, !state.busy, v?.categoryIconRows.orEmpty().filter { it.voidedAt == null }.sortedByDescending { it.updatedAt }.map { it.iconKey }) { icon = it }
    TextButton({vm.workspaceAction(epoch) {repo,token->repo.setCategoryPolicy(token,policyCategory,FinanceBurden.valueOf(burden),icon,
        v?.categoryPolicyRows.orEmpty().singleOrNull {it.categoryId==policyCategory}?.revision)}},enabled=!state.busy && policyCategory.isNotEmpty()){Text("保存可选分类政策")}
    Text("可检查线索",style=LifeText.title)
    val diagnostics=FinanceInsightsProjection.diagnostics(s,System.currentTimeMillis())
    if(diagnostics.isEmpty()) Text("当前没有符合规则的线索；这不代表已完成所有账务核验。",style=LifeText.caption)
    FinancePagedItems(diagnostics,"diagnostics",{it.id}) {d->Text(d.message);TextButton({diagnostic=d}) {Text("查看相关原记录")}}
    FinancePurposePermissions(state, vm)
    TextButton({exportWarning=true},enabled=!state.busy){Text("携出完整财务事实与可读CSV")}
    if(exported) Text("财务携出文件已写入并读回校验。附件与跨域恢复请使用设置中的加密备份。",style=LifeText.caption)
    if(exportWarning) AlertDialog(onDismissRequest={exportWarning=false},title={Text("导出明文财务数据？")},text={Text("此文件含账户、原始ID、流水、事项关系、义务、购买明细及复核配置，未加密。接收方可以读取全部内容；只在你主动选择的位置创建。")},
        confirmButton={TextButton({exportWarning=false;exporter.launch("LifeOS-finance-${LocalDate.now()}.zip")}){Text("确认明文携出")}},dismissButton={TextButton({exportWarning=false}){Text("取消")}})
    editingBudget?.let {id->v?.budgetRows.orEmpty().singleOrNull {it.id==id && it.voidedAt==null}?.let {budget->key(id) {FinanceBudgetEditor(state,vm,budget){editingBudget=null}}}}
    diagnostic?.let {d->key(d.id) {FinanceDiagnosticViewer(state,vm,d){diagnostic=null}}}
}
