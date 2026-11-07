package com.qq.closie.ui.lifeos.finance

import com.qq.closie.life.ui.theme.BundledText as Text
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.qq.closie.life.finance.*
import com.qq.closie.ui.lifeos.theme.LifeText
import java.time.LocalDate

private val QuerySpecSaver=Saver<FinanceQuerySpec,String>(
    save={com.google.gson.Gson().toJson(it)},
    restore={runCatching {FinanceQuerySpec.decode(it)}.getOrNull()})

@Composable
internal fun FinanceQueryWorkbench(state:FinanceUiState,vm:FinanceViewModel) {
    val s=state.snapshot
    val localGrants=remember(s.v16?.purposeGrantRows) {
        s.v16?.purposeGrantRows.orEmpty().filter {it.purpose==FinanceReadPurpose.LOCAL_QUERY &&
            it.providerId=="local-conditions" && it.voidedAt==null}
    }
    val now by produceState(System.currentTimeMillis(),localGrants) {
        while(true) {
            val current=System.currentTimeMillis()
            value=current
            if(localGrants.none {it.expiresAt>current}) break
            kotlinx.coroutines.delay(1_000)
        }
    }
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
    var viewingEvent by rememberSaveable {mutableStateOf<String?>(null)}
    var viewingCash by rememberSaveable {mutableStateOf<String?>(null)}
    var editingEvent by rememberSaveable {mutableStateOf(false)}
    LaunchedEffect(spec,useLocal,grant) {
        viewingEvent=null;viewingCash=null;editingEvent=false
    }
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
    FinanceChoice("业务性质",spec.nature?.name.orEmpty(),listOf("" to "全部性质")+FinanceNature.entries.map {it.name to it.label}) {
        val nature = it.takeIf(String::isNotBlank)?.let(FinanceNature::valueOf)
        spec = spec.copy(version = if (nature == null) 1 else 2, nature = nature)
        useLocal = false
        error = null
    }
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
        " · ${spec.accountIds.size}账户/${spec.categoryIds.size}分类/${spec.tagIds.size}标签 · ${spec.nature?.label ?: "全部性质"}",style=LifeText.caption)
    OutlinedTextField(name,{name=it},label={Text("保存视图名称")})
    TextButton({vm.workspaceAction(epoch) {repo,token->repo.saveQuery(token,name,spec)}},enabled=!state.busy && name.isNotBlank()){Text("保存当前条件")}
    FinancePagedItems(s.v16?.savedQueryRows.orEmpty().filter {it.voidedAt==null},"saved-queries",{it.id}) {query->Row {
        TextButton({runCatching {FinanceQuerySpec.decode(query.specJson).also {require(query.specVersion in 1..2 && query.specVersion==it.version)}}
            .onSuccess {adopt(it);useLocal=false;error=null}.onFailure {error="保存视图无法解释，请更新条件"}}){Text(query.name)}
        TextButton({vm.workspaceAction(epoch){repo,token->repo.removeQuery(token,query.id,query.revision)}}){Text("移除视图")}
    }}
    HorizontalDivider()
    Text("本地受限查账",style=LifeText.title)
    Text("例如：今年 标签=回家 分类=交通 花多少。完整解释后展示条件；不执行修改、删除、SQL或不完整语句。",style=LifeText.caption)
    OutlinedTextField(localText,{localText=it},label={Text("用固定短句描述查询")},modifier=Modifier.testTag("finance-query-local-input"))
    val grants=localGrants.filter {it.expiresAt>now}
    FinanceChoice("本次查询授权",grant.orEmpty(),grants.map {it.id to ("授权至 "+FinanceTime.input(it.expiresAt))}){grant=it}
    Text("在预算与隐私页可选择事项并单独授权本地查询；Home、分享和AI授权互不替代。",style=LifeText.caption)
    TextButton({runCatching {FinanceLocalQueryParser.parse(localText,s)}.onSuccess {adopt(it);useLocal=true;error=null}.onFailure {error=it.message}}){Text("解释为可见查询条件")}
    error?.let {Text(it,color=MaterialTheme.colorScheme.error)}
    val grantActive=grants.any {it.id==grant}
    val result=remember(s,spec,useLocal,grant,grantActive) {runCatching {
        when {
            useLocal -> FinancePurposeRead.query(s,spec,requireNotNull(grant){"请选择本次查询授权"},now) to null
            spec.mode==FinanceQueryMode.CASH_MOVEMENT -> {
                val ledger=FinanceLedgerQuery.execute(s,spec)
                FinanceQueryResult(spec,emptyList(),ledger.cash,ledger.totals,undatedCash=ledger.undatedCash) to ledger
            }
            else -> FinanceQueryEngine.execute(s,spec) to null
        }
    }}
    val accountCurrencies=remember(s.accounts) {s.accounts.associate {it.id to it.currencyCode}}
    val unknownCashDateIds = remember(s.v16?.entryTimingRows) {
        s.v16?.entryTimingRows.orEmpty().filter {
            it.voidedAt == null && it.cashPrecision == FinanceTimePrecision.UNKNOWN
        }.map { it.entryId }.toSet()
    }
    fun hasUnknownCashDate(row:FinanceLedgerRow)=row.entry.id in unknownCashDateIds ||
        row.transfer?.inflowEntryId in unknownCashDateIds
    result.onSuccess {(value,visibleCash)->
        val summary=FinanceQueryPreview(spec,visibleCash?.rows?.size ?: if(spec.mode==FinanceQueryMode.PERSONAL_COST) value.costs.size else value.cash.size,
            value.totals,visibleCash?.undatedRows?.size ?: value.undatedCash.size,
            visibleCash?.directions ?: value.cash.groupBy {accountCurrencies.getValue(it.accountId)}
                .mapValues {(_,entries)->entries.map {it.direction}.toSet()},
            confirmedCostCurrencies=value.costs.filter {it.amountMinor!=null}.map {it.currencyCode}.toSet())
        value.totals.forEach {(code,total)->Text(summary.totalLabel(code,total))}
        if(spec.mode==FinanceQueryMode.CASH_MOVEMENT) Text(
            (if(useLocal) "授权收付明细 · " else "资金记录 · ")+summary.count+" 笔",style=LifeText.caption)
        if (useLocal) Text("受限结果仅展示本次授权的查询内容。", style=LifeText.caption)
        val undatedCostCount = (value.undatedCosts + value.costs.filter {
            FinanceCostProjection.hasUnknownDate(it,unknownCashDateIds)
        }).distinctBy { it.id }.size
        val undatedCashCount = if(visibleCash!=null) visibleCash.undatedRows.size+visibleCash.rows.count(::hasUnknownCashDate)
            else (value.undatedCash + value.cash.filter {
            it.id in unknownCashDateIds
        }).distinctBy { it.id }.size
        if (undatedCostCount > 0 || undatedCashCount > 0) {
            Text("日期待确认 · $undatedCostCount 项成本 / $undatedCashCount 笔"+
                if(useLocal) "收付明细" else "资金记录", style = LifeText.caption)
            if (spec.startInclusive != null || spec.endExclusive != null)
                Text("未归期记录单独列示，未纳入本次期间合计。", style = LifeText.caption)
        }
        @Composable
        fun CostResult(row: PersonalCostRow, undated: Boolean = false) {
            val date = if (undated || FinanceCostProjection.hasUnknownDate(row,unknownCashDateIds))
                if (row.adjustment) "调整日期待确认" else "成本日期待确认"
                else FinanceTime.input(row.occurredAt)
            val label = date + " · " + row.description.ifBlank { "经济事项" } + " · " +
                (row.amountMinor?.let { FinanceMoney.display(it, row.currencyCode) } ?: "未知")
            val marker = Modifier.testTag("finance-query-cost-" + row.id)
            if (useLocal) Text(label, marker)
            else TextButton({
                if (row.eventId != null) viewingEvent = row.eventId else viewingCash = row.entryId
            }, modifier = marker.fillMaxWidth(), enabled = row.eventId != null || row.entryId != null) { Text(label) }
        }
        @Composable
        fun CashResult(row: FinanceEntryEntity, undated: Boolean = false) {
            val date = if (undated || row.id in unknownCashDateIds) "收付日期待确认" else FinanceTime.input(row.occurredAt)
            val label = date + " · " + row.description.ifBlank { "资金流水" } + " · " + row.direction.name + " " +
                FinanceMoney.display(row.amountMinor,accountCurrencies.getValue(row.accountId))
            val marker = Modifier.testTag("finance-query-cash-" + row.id)
            if (useLocal) Text(label, marker)
            else TextButton({ viewingCash = row.id }, modifier = marker.fillMaxWidth()) { Text(label) }
        }
        @Composable
        fun LedgerResult(row:FinanceLedgerRow,undated:Boolean=false) {
            val date=if(undated || hasUnknownCashDate(row)) "收付日期待确认" else FinanceTime.input(row.entry.occurredAt)
            val detail=if(row.transfer!=null) "账户转账 · "+row.account.name+" → "+row.targetAccount?.name
                else (if(row.entry.direction==FinanceDirection.INFLOW) "流入 · " else "流出 · ")+
                    row.entry.description.ifBlank {"资金流水"}
            TextButton({viewingCash=row.id},Modifier.testTag("finance-query-cash-"+row.id).fillMaxWidth()) {
                Text(date+" · "+detail+" · "+FinanceMoney.display(row.entry.amountMinor,row.currencyCode))
            }
        }
        key(spec,useLocal,grant) {
            FinancePagedItems(value.costs,"query-costs",{it.id}) { CostResult(it) }
            if(visibleCash!=null) FinancePagedItems(visibleCash.rows,"query-cash",{it.id}) {LedgerResult(it)}
            else FinancePagedItems(value.cash,"query-cash",{it.id}) { CashResult(it) }
            if (value.undatedCosts.isNotEmpty() || value.undatedCash.isNotEmpty()) {
                Text("未归期记录", style = LifeText.title)
                FinancePagedItems(value.undatedCosts, "query-undated-costs", { it.id }) { CostResult(it, undated = true) }
                if(visibleCash!=null) FinancePagedItems(visibleCash.undatedRows,"query-undated-cash",{it.id}) {LedgerResult(it,undated=true)}
                else FinancePagedItems(value.undatedCash, "query-undated-cash", { it.id }) { CashResult(it, undated = true) }
            }
        }
        if(value.costs.isEmpty() && value.cash.isEmpty() && value.undatedCosts.isEmpty() && value.undatedCash.isEmpty())
            Text("当前条件与授权范围内没有记录。")
    }.onFailure {Text(it.message ?: "查询无法完成",color=MaterialTheme.colorScheme.error)}
    // Restricted results stay within the granted projection, including after state restoration.
    if(!useLocal) {
        viewingEvent?.let {id->
            if(editingEvent) FinanceEconomicEditor(state,vm,id){editingEvent=false}
            else FinanceEconomicDetailNavigation(state,vm,id,onEdit={editingEvent=true},onOpenCash={viewingCash=it},onClose={viewingEvent=null})
        }
        viewingCash?.let {id->FinanceCanonicalRecordSheet(state,vm,id){viewingCash=null}}
    }
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
