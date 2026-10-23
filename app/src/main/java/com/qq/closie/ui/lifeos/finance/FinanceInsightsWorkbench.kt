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
    var share by remember {mutableStateOf<FinanceSharePreview?>(null)}
    var shareError by remember {mutableStateOf<String?>(null)}
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
    HorizontalDivider()
    Text("分用途授权",style=LifeText.title)
    var purpose by rememberSaveable {mutableStateOf(FinanceReadPurpose.HOME.name)}
    val selectionSaver=androidx.compose.runtime.saveable.listSaver<Set<String>,String>(save={it.toList()},restore={it.toSet()})
    var selected by rememberSaveable(stateSaver=selectionSaver) {mutableStateOf(emptySet<String>())}
    var selectedEntries by rememberSaveable(stateSaver=selectionSaver) {mutableStateOf(emptySet<String>())}
    var amount by rememberSaveable {mutableStateOf(false)}
    var date by rememberSaveable {mutableStateOf(false)}
    var description by rememberSaveable {mutableStateOf(false)}
    var hours by rememberSaveable {mutableStateOf("24")}
    listOf(FinanceReadPurpose.HOME,FinanceReadPurpose.SHARE,FinanceReadPurpose.LOCAL_QUERY).forEach {p->FilterChip(purpose==p.name,{purpose=p.name},label={Text(when(p){FinanceReadPurpose.HOME->"Home回忆";FinanceReadPurpose.SHARE->"分享携出";else->"本地条件查询"})})}
    FinanceMultiChoice("明确选择允许的事项",selected,s.v4?.events.orEmpty().filter {it.voidedAt==null}.map {it.id to (it.description.ifBlank {"事项"}+" · "+FinanceTime.input(it.occurredAt))}){selected=it}
    if(purpose==FinanceReadPurpose.LOCAL_QUERY.name) {
        Text("真实流水单独授权整笔内容，可能含其他事项的分配。仅选事项不会自动授权关联流水。",style=LifeText.caption)
        FinanceMultiChoice("另行授权整笔真实流水",selectedEntries,s.entries.filter {it.voidedAt==null}.map {entry->entry.id to
            (FinanceTime.input(entry.occurredAt)+" · "+entry.description+" · "+FinanceMoney.display(entry.amountMinor,s.accounts.single {it.id==entry.accountId}.currencyCode))}){selectedEntries=it}
    }
    Row {Checkbox(amount,{amount=it});Text("允许金额")}
    Row {Checkbox(date,{date=it});Text("允许日期")}
    Row {Checkbox(description,{description=it});Text("允许备注")}
    OutlinedTextField(hours,{hours=it},label={Text("有效小时数")})
    TextButton({vm.workspaceAction(epoch) {repo,token->
        val p=FinanceReadPurpose.valueOf(purpose);val provider=when(p){FinanceReadPurpose.HOME->"home-memory";FinanceReadPurpose.SHARE->"manual-share";else->"local-conditions"}
        val duration=hours.toLong();require(duration in 1..8760)
        val entries=if(p==FinanceReadPurpose.LOCAL_QUERY) selectedEntries else emptySet()
        repo.grant(token,p,provider,selected,entries,amount,date,description,Math.addExact(System.currentTimeMillis(),Math.multiplyExact(duration,3_600_000)))
    }},enabled=!state.busy && (selected.isNotEmpty() || (purpose==FinanceReadPurpose.LOCAL_QUERY.name && selectedEntries.isNotEmpty())) && (amount || date || description)){Text("确认此用途与范围授权")}
    FinancePagedItems(v?.purposeGrantRows.orEmpty().filter {it.voidedAt==null},"purpose-grants",{it.id}) {g->Row {
        Text(g.purpose.name+" · 至 "+FinanceTime.input(g.expiresAt))
        TextButton({vm.workspaceAction(epoch) {repo,token->repo.revoke(token,g.id)}},enabled=!state.busy){Text("撤销")}
        if(g.purpose==FinanceReadPurpose.SHARE) TextButton({
            runCatching {FinanceShareProjection.preview(s,g.id,System.currentTimeMillis())}.onSuccess {share=it;shareError=null}.onFailure {shareError=it.message}
        },enabled=!state.busy) {Text("预览此范围分享")}
    }}
    shareError?.let {Text(it,color=MaterialTheme.colorScheme.error)}
    Text("云AI默认关闭，当前没有真实云连接或数据发送。Home、分享、本地查询和AI分别授权。未来可选的自动加密同步目标保留。",style=LifeText.caption)
    TextButton({exportWarning=true},enabled=!state.busy){Text("携出完整财务事实与可读CSV")}
    if(exported) Text("财务携出文件已写入并读回校验。附件与跨域恢复请使用设置中的加密备份。",style=LifeText.caption)
    if(exportWarning) AlertDialog(onDismissRequest={exportWarning=false},title={Text("导出明文财务数据？")},text={Text("此文件含账户、原始ID、流水、事项关系、义务、购买明细及复核配置，未加密。接收方可以读取全部内容；只在你主动选择的位置创建。")},
        confirmButton={TextButton({exportWarning=false;exporter.launch("LifeOS-finance-${LocalDate.now()}.zip")}){Text("确认明文携出")}},dismissButton={TextButton({exportWarning=false}){Text("取消")}})
    editingBudget?.let {id->v?.budgetRows.orEmpty().singleOrNull {it.id==id && it.voidedAt==null}?.let {budget->key(id) {FinanceBudgetEditor(state,vm,budget){editingBudget=null}}}}
    diagnostic?.let {d->key(d.id) {FinanceDiagnosticViewer(state,vm,d){diagnostic=null}}}
    share?.let {preview->FinanceReceipt("核对分享内容",state.busy,state.error,{share=null},actions={Button({vm.shareFinance(context,preview){share=null}},enabled=!state.busy){Text("确认并选择接收应用")}}) {
        Text("以下是唯一将交给所选应用的明文内容。接收者可保存它；原图、账户身份、未授权字段不在此片段中。")
        Text(preview.text)
    }}
}
