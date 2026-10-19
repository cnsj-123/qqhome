package com.qq.closie.ui.lifeos.finance

import com.qq.closie.life.ui.theme.BundledText as Text
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.Saver
import com.qq.closie.life.finance.*
import com.qq.closie.ui.lifeos.theme.LifeText
import java.time.LocalDate

private val QuerySpecSaver=Saver<FinanceQuerySpec,String>(
    save={com.google.gson.Gson().toJson(it)},
    restore={runCatching {com.google.gson.Gson().fromJson(it,FinanceQuerySpec::class.java).also {spec->spec.validate()}}.getOrNull()})

@Composable
internal fun FinanceQueryWorkbench(state:FinanceUiState,vm:FinanceViewModel) {
    val s=state.snapshot
    val now by produceState(System.currentTimeMillis()) {while(true){value=System.currentTimeMillis();kotlinx.coroutines.delay(1_000)}}
    val epoch=rememberSaveable {s.v16?.controlRows?.singleOrNull()?.restoreEpoch ?: 0L}
    var spec by rememberSaveable(stateSaver=QuerySpecSaver) {mutableStateOf(FinanceQuerySpec())}
    var name by rememberSaveable {mutableStateOf("")}
    var localText by rememberSaveable {mutableStateOf("")}
    var error by remember {mutableStateOf<String?>(null)}
    var grant by rememberSaveable {mutableStateOf<String?>(null)}
    var useLocal by rememberSaveable {mutableStateOf(false)}
    var start by rememberSaveable {mutableStateOf("")}
    var end by rememberSaveable {mutableStateOf("")}
    var min by rememberSaveable {mutableStateOf("")}
    var max by rememberSaveable {mutableStateOf("")}
    var currency by rememberSaveable {mutableStateOf("CNY")}
    fun adopt(next:FinanceQuerySpec) {
        val zone=java.time.ZoneId.systemDefault()
        spec=next
        start=next.startInclusive?.let {java.time.Instant.ofEpochMilli(it).atZone(zone).toLocalDate().toString()}.orEmpty()
        end=next.endExclusive?.let {java.time.Instant.ofEpochMilli(it).atZone(zone).toLocalDate().minusDays(1).toString()}.orEmpty()
        currency=next.currencyCode ?: "CNY"
        min=next.minimumMinor?.let {FinanceMoney.input(it,currency)}.orEmpty()
        max=next.maximumMinor?.let {FinanceMoney.input(it,currency)}.orEmpty()
    }
    Text("组合查询与专题",style=LifeText.title)
    Text("保存视图保存条件；新记录会动态进入结果。同一事项的不同承担行只分别计自身份额。",style=LifeText.caption)
    FinanceQueryMode.entries.forEach {mode->FilterChip(spec.mode==mode,{spec=spec.copy(mode=mode)},label={Text(if(mode==FinanceQueryMode.PERSONAL_COST) "个人成本" else "真实资金流水")})}
    OutlinedTextField(spec.text,{spec=spec.copy(text=it.take(FinanceFilterState.MAX_QUERY))},label={Text("搜索备注、分类或标签")})
    FinanceMultiChoice("账户",spec.accountIds,s.accounts.map {it.id to it.name}){spec=spec.copy(accountIds=it)}
    FinanceMultiChoice("分类（含下级）",spec.categoryIds,s.categories.map {it.id to (FinanceProjection.categoryPath(s.categories,it.id) ?: it.name)}){spec=spec.copy(categoryIds=it)}
    FinanceMultiChoice("同时包含的标签",spec.tagIds,s.tags.map {it.id to it.name}){spec=spec.copy(tagIds=it)}
    OutlinedTextField(start,{start=it},label={Text("开始日期 · 年-月-日（空为不限）")})
    OutlinedTextField(end,{end=it},label={Text("结束日期 · 年-月-日（含当天）")})
    OutlinedTextField(currency,{currency=it},label={Text("金额筛选币种")})
    OutlinedTextField(min,{min=it},label={Text("最低金额（空为不限）")})
    OutlinedTextField(max,{max=it},label={Text("最高金额（空为不限）")})
    TextButton({runCatching {
        val zone=java.time.ZoneId.systemDefault()
        spec.copy(startInclusive=start.takeIf(String::isNotBlank)?.let {LocalDate.parse(it).atStartOfDay(zone).toInstant().toEpochMilli()},
            endExclusive=end.takeIf(String::isNotBlank)?.let {LocalDate.parse(it).plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()},
            currencyCode=currency.takeIf {min.isNotBlank() || max.isNotBlank()},minimumMinor=min.takeIf(String::isNotBlank)?.let {FinanceMoney.parse(it,currency,true)},
            maximumMinor=max.takeIf(String::isNotBlank)?.let {FinanceMoney.parse(it,currency,true)}).also {it.validate()}
    }.onSuccess {spec=it;useLocal=false;error=null}.onFailure {error=it.message}}) {Text("应用日期与金额条件")}
    Text("条件：${spec.mode} · "+(spec.startInclusive?.let(FinanceTime::input) ?: "不限起始")+" → "+(spec.endExclusive?.let(FinanceTime::input) ?: "不限结束")+
        " · ${spec.accountIds.size}账户/${spec.categoryIds.size}分类/${spec.tagIds.size}标签",style=LifeText.caption)
    OutlinedTextField(name,{name=it},label={Text("保存视图名称")})
    TextButton({vm.workspaceAction(epoch) {repo,token->repo.saveQuery(token,name,spec)}},enabled=!state.busy && name.isNotBlank()){Text("保存当前条件")}
    FinancePagedItems(s.v16?.savedQueryRows.orEmpty().filter {it.voidedAt==null},"saved-queries",{it.id}) {query->Row {
        TextButton({runCatching {com.google.gson.Gson().fromJson(query.specJson,FinanceQuerySpec::class.java).also {it.validate();require(query.specVersion==1)}}
            .onSuccess {adopt(it);useLocal=false;error=null}.onFailure {error="保存视图无法解释，请更新条件"}}){Text(query.name)}
        TextButton({vm.workspaceAction(epoch){repo,token->repo.removeQuery(token,query.id,query.revision)}}){Text("移除视图")}
    }}
    HorizontalDivider()
    Text("本地受限查账",style=LifeText.title)
    Text("例如：今年 标签=回家 分类=交通 花多少。完整解释后展示条件；不执行修改、删除、SQL或不完整语句。",style=LifeText.caption)
    OutlinedTextField(localText,{localText=it},label={Text("用固定短句描述查询")})
    val grants=s.v16?.purposeGrantRows.orEmpty().filter {it.purpose==FinanceReadPurpose.LOCAL_QUERY && it.providerId=="local-conditions" && it.voidedAt==null && it.expiresAt>now}
    FinanceChoice("本次查询授权",grant.orEmpty(),grants.map {it.id to ("授权至 "+FinanceTime.input(it.expiresAt))}){grant=it}
    Text("在预算与隐私页可选择事项并单独授权本地查询；Home、分享和AI授权互不替代。",style=LifeText.caption)
    TextButton({runCatching {FinanceLocalQueryParser.parse(localText,s)}.onSuccess {adopt(it);useLocal=true;error=null}.onFailure {error=it.message}}){Text("解释为可见查询条件")}
    error?.let {Text(it,color=MaterialTheme.colorScheme.error)}
    val grantActive=grants.any {it.id==grant}
    val result=remember(s,spec,useLocal,grant,grantActive) {runCatching {
        if(useLocal) FinancePurposeRead.query(s,spec,requireNotNull(grant){"请选择本次查询授权"},now) else FinanceQueryEngine.execute(s,spec)
    }}
    result.onSuccess {value->
        value.totals.forEach {(code,total)->Text((if(spec.mode==FinanceQueryMode.PERSONAL_COST) "个人成本 " else "真实流出减流入 ")+FinanceMoney.display(total.confirmedMinor,code)+if(total.complete) "" else " · ${total.unknownCount} 项金额未知")}
        key(spec,useLocal,grant) {
            FinancePagedItems(value.costs,"query-costs",{it.id}) {row->Text(FinanceTime.input(row.occurredAt)+" · "+row.description.ifBlank {"经济事项"}+" · "+(row.amountMinor?.let {FinanceMoney.display(it,row.currencyCode)} ?: "未知"))}
            FinancePagedItems(value.cash,"query-cash",{it.id}) {row->Text(FinanceTime.input(row.occurredAt)+" · "+row.description.ifBlank {"资金流水"}+" · "+row.direction.name+" "+FinanceMoney.display(row.amountMinor,s.accounts.single {it.id==row.accountId}.currencyCode))}
        }
        if(value.costs.isEmpty() && value.cash.isEmpty()) Text("当前条件与授权范围内没有记录。")
    }.onFailure {Text(it.message ?: "查询无法完成",color=MaterialTheme.colorScheme.error)}
}

@Composable
internal fun FinanceMultiChoice(label:String,selected:Set<String>,options:List<Pair<String,String>>,change:(Set<String>)->Unit) {
    var open by rememberSaveable {mutableStateOf(false)}
    TextButton({open=!open}) {Text("$label · "+if(selected.isEmpty()) "不限" else "已选 ${selected.size}")}
    if(open) {
        var search by rememberSaveable(label) {mutableStateOf("")}
        OutlinedTextField(search,{search=it.take(200)},label={Text("搜索$label")})
        key(search) {FinancePagedItems(options.filter {search.isBlank() || it.second.contains(search,true)},"multi-$label",{it.first}) {(id,name)->
            FilterChip(id in selected,{change(if(id in selected) selected-id else selected+id)},label={Text(name)})
        }}
    }
}
