package com.qq.closie.ui.lifeos.finance

import com.qq.closie.life.ui.theme.BundledText as Text
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import com.qq.closie.life.finance.*
import com.qq.closie.ui.lifeos.theme.LifeText
import java.time.YearMonth
import java.time.ZoneId

@Composable
internal fun FinanceAccountWorkbench(state:FinanceUiState,account:FinanceAccountEntity,vm:FinanceViewModel,
    showInterval:((FinanceObservationEntity,FinanceObservationEntity)->Unit)?=null) {
    val s=state.snapshot
    val epoch=rememberSaveable(account.id) { s.v16?.controlRows?.singleOrNull()?.restoreEpoch ?: 0L }
    val policy=s.v16?.accountPolicyRows.orEmpty().singleOrNull { it.accountId==account.id && it.voidedAt==null }
    val trust=FinanceAccountStatusProjection.status(s,account.id)
    var editingObservation by rememberSaveable(account.id) { mutableStateOf(false) }
    var editingAnchor by rememberSaveable(account.id) { mutableStateOf(false) }
    var editingCycle by rememberSaveable(account.id) { mutableStateOf(false) }
    var repaymentOpen by rememberSaveable(account.id) { mutableStateOf(false) }
    var basis by rememberSaveable(account.id) { mutableStateOf(FinanceBalanceBasis.LEGACY_CASH.name) }
    var observedAmount by rememberSaveable(account.id) { mutableStateOf("") }
    var at by rememberSaveable(account.id) { mutableStateOf(FinanceTime.input(System.currentTimeMillis())) }
    var note by rememberSaveable(account.id) { mutableStateOf("") }
    var opening by rememberSaveable(account.id) { mutableStateOf(FinanceMoney.input(account.openingBalanceMinor,account.currencyCode)) }
    var anchorAt by rememberSaveable(account.id) { mutableStateOf(FinanceTime.input(account.balanceAnchorAt)) }
    var fromZero by rememberSaveable(account.id) { mutableStateOf(false) }
    var anchorPreview by remember { mutableStateOf<FinanceAnchorPreview?>(null) }
    var anchorError by remember { mutableStateOf<String?>(null) }
    val cycle=s.v16?.creditCycleRows.orEmpty().singleOrNull { it.accountId==account.id && it.voidedAt==null }
    var closingDay by rememberSaveable(account.id) { mutableStateOf(cycle?.closingDay?.toString() ?: "") }
    var dueDay by rememberSaveable(account.id) { mutableStateOf(cycle?.dueDay?.toString() ?: "") }
    var zone by rememberSaveable(account.id) { mutableStateOf(cycle?.zoneId ?: java.time.ZoneId.systemDefault().id) }
    var statementMonth by rememberSaveable(account.id) { mutableStateOf(YearMonth.now().toString()) }
    FinanceSection {
        Text(trust.trust.label,style=LifeText.title);Text(trust.explanation,style=LifeText.caption)
        Text("旧余额起点 · "+FinanceTime.anchorLabel(account.balanceAnchorAt)+" · "+FinanceMoney.display(account.openingBalanceMinor,account.currencyCode),style=LifeText.caption)
        Row {
            Checkbox(policy?.includedInNet!=false,{ included -> vm.workspaceAction(epoch) { repo,token ->
                repo.setAccountPolicy(token,account.id,included,policy?.liabilityConvention ?: FinanceLiabilityConvention.SIGNED,
                    policy?.anchorBasis ?: FinanceBalanceBasis.LEGACY_CASH,policy?.revision,account.updatedAt)
            } },enabled=!state.busy && account.archivedAt==null)
            Text("纳入账户净额")
        }
        Text("净额贡献 · "+FinanceMoney.display(FinanceAccountProjection.netContribution(s,account),account.currencyCode),style=LifeText.body)
        Text("起点符号口径："+(if(policy?.liabilityConvention==FinanceLiabilityConvention.POSITIVE_DEBT) "旧期初正数表示欠款，只将期初转负，之后按真实收付计算。旧口径余额另行保留。" else "期初按账面正负号计入，之后按真实收付计算。"),style=LifeText.caption)
        if(FinanceAccountProjection.liabilitySignPending(s,account)) Text("此正数信用期初尚未确认是欠款还是余额，净额暂沿旧账簿正号。请选择实际含义后再核对净额。",style=LifeText.caption)
        if(policy==null && account.kind==FinanceAccountKind.CREDIT && account.openingBalanceMinor>0) TextButton({vm.workspaceAction(epoch) {repo,token->
            repo.setAccountPolicy(token,account.id,true,FinanceLiabilityConvention.SIGNED,FinanceBalanceBasis.LEGACY_CASH,null,account.updatedAt)
        }},enabled=!state.busy && account.archivedAt==null) {Text("确认期初是正数余额，按账面符号计入")}
        TextButton({ vm.workspaceAction(epoch) { repo,token ->
            repo.setAccountPolicy(token,account.id,policy?.includedInNet!=false,
                if(policy?.liabilityConvention==FinanceLiabilityConvention.POSITIVE_DEBT) FinanceLiabilityConvention.SIGNED else FinanceLiabilityConvention.POSITIVE_DEBT,
                policy?.anchorBasis ?: FinanceBalanceBasis.LEGACY_CASH,policy?.revision,account.updatedAt)
        } },enabled=!state.busy && account.openingBalanceMinor>=0 && account.archivedAt==null) { Text(if(policy?.liabilityConvention==FinanceLiabilityConvention.POSITIVE_DEBT) "改为期初按账面符号计入" else "确认旧期初正数表示欠款") }
        TextButton({editingAnchor=!editingAnchor}) { Text("检查或更改余额起点") }
        if(editingAnchor) {
            OutlinedTextField(opening,{opening=it},label={Text("起点余额，可为负数")})
            OutlinedTextField(anchorAt,{anchorAt=it},label={Text("起点时间 · 年-月-日 时:分")})
            Row { Checkbox(fromZero,{fromZero=it;if(it) opening="0"});Text("从零完整历史") }
            Text("回填起点前历史不改变当前余额。更改起点会使既有核对结论失效。",style=LifeText.caption)
            TextButton({
                runCatching { FinanceMoney.parse(opening,account.currencyCode,true) to FinanceTime.resolve(anchorAt,account.balanceAnchorAt) }
                    .onSuccess { (value,time) -> vm.previewAnchor(account.id,value,time,fromZero) {anchorPreview=it;anchorError=null} }
                    .onFailure {anchorError=it.message}
            },enabled=!state.busy) { Text("预览起点影响") }
            anchorError?.let {Text(it)}
        }
        TextButton({editingObservation=!editingObservation}) { Text("记录观察余额并核对") }
        if(editingObservation) {
            FinanceBalanceBasis.entries.forEach { value -> FilterChip(basis==value.name,{basis=value.name},label={Text(if(value==FinanceBalanceBasis.LEGACY_CASH) "实际收付口径" else "确知入账口径")}) }
            OutlinedTextField(observedAmount,{observedAmount=it},label={Text("观察余额")})
            OutlinedTextField(at,{at=it},label={Text("观察时间 · 年-月-日 时:分")})
            OutlinedTextField(note,{note=it},label={Text("核对说明（选填）")})
            TextButton({ vm.workspaceAction(epoch,{editingObservation=false}) { repo,token ->
                repo.saveObservation(token,account.id,FinanceMoney.parse(observedAmount,account.currencyCode,true),
                    FinanceTime.parse(at),FinanceBalanceBasis.valueOf(basis),FinanceTimePrecision.INSTANT,note)
            } },enabled=!state.busy) { Text("保存观察点") }
        }
        val observations=s.v16?.observationRows.orEmpty().filter {it.accountId==account.id && it.voidedAt==null}.sortedByDescending {it.observedAt}
        FinancePagedItems(observations,"observations-${account.id}",{it.id}) { observation ->
            val saved=s.v16?.reconciliationRows.orEmpty().filter {it.observationId==observation.id && it.voidedAt==null}.maxByOrNull {it.confirmationOrder}
            val result=FinanceAccountProjection.reconcile(s,observation,saved)
            Text(observation.timeLabel()+" · 观察 "+FinanceMoney.display(observation.amountMinor,account.currencyCode),style=LifeText.body)
            Text((if(observation.basis==FinanceBalanceBasis.LEGACY_CASH) "实际收付" else "确知入账")+" · "+
                (result.differenceMinor?.let {"差额 "+FinanceMoney.display(it,account.currencyCode)} ?: "口径资料不足，无法比较")+
                (if(result.invalidated) " · 原结论失效" else if(saved!=null) " · 已保存结论" else ""),style=LifeText.caption)
            TextButton({vm.workspaceAction(epoch) {repo,token ->repo.confirmReconciliation(token,observation.id,result.fingerprint,note)}},enabled=!state.busy) {Text("确认本次核对结论")}
        }
        FinancePagedItems(observations.groupBy {it.basis}.values.flatMap {it.zipWithNext()}.filter {it.first.observedAt>it.second.observedAt},
            "observation-intervals-${account.id}",{it.first.id+":"+it.second.id}) { (last,first) ->
            val interval=FinanceAccountStatusProjection.compare(s,first,last)
            Text("区间 "+first.timeLabel()+" → "+last.timeLabel()+
                " · 观察变化 "+FinanceMoney.display(interval.observedMovementMinor,account.currencyCode)+" · "+
                (interval.unexplainedMinor?.let {"未解释差额 "+FinanceMoney.display(it,account.currencyCode)} ?: "账面变化未知"),style=LifeText.caption)
            if(showInterval!=null) TextButton({showInterval(first,last)},enabled=interval.bookMovementMinor!=null) {Text("筛选此区间的账面流水")}
        }
        if(account.kind==FinanceAccountKind.CREDIT) {
            Text("实际还款由付款账户流出、此信用账户流入，两腿属于同一转账，不再计消费。已记录的还款请打开原转账核对。",style=LifeText.caption)
            TextButton({repaymentOpen=true},enabled=!state.busy && account.archivedAt==null &&
                s.accounts.any {it.id!=account.id && it.archivedAt==null && it.currencyCode==account.currencyCode}) {Text("记录新的实际还款转账")}
            TextButton({editingCycle=!editingCycle}) {Text("信用卡账期与入账口径")}
            if(editingCycle) {
                OutlinedTextField(closingDay,{closingDay=it},label={Text("结账日 · 1–31")})
                OutlinedTextField(dueDay,{dueDay=it},label={Text("还款日 · 1–31")})
                OutlinedTextField(zone,{zone=it},label={Text("账期时区")})
                TextButton({vm.workspaceAction(epoch) {repo,token ->repo.saveCreditCycle(token,account.id,closingDay.toInt(),dueDay.toInt(),zone,cycle?.revision)}},enabled=!state.busy) {Text("保存账期")}
                OutlinedTextField(statementMonth,{statementMonth=it},label={Text("结账月份 · 年-月")})
                if(cycle!=null) runCatching {FinanceAccountStatusProjection.statement(s,account.id,YearMonth.parse(statementMonth))}.getOrNull()?.let { statement ->
                    Text("确知入账流出 "+FinanceMoney.display(statement.outflowMinor,account.currencyCode)+" / 流入含还款 "+FinanceMoney.display(statement.inflowMinor,account.currencyCode))
                    Text("还款日 "+FinanceTime.input(statement.dueAt)+" · 另有 ${statement.unknownPostingCount} 笔入账日未知，不猜入账期。",style=LifeText.caption)
                }
                Text("入账口径起点须由用户明确确认，旧实际收付起点不会自动改用入账日。",style=LifeText.caption)
                TextButton({vm.workspaceAction(epoch) {repo,token ->repo.setAccountPolicy(token,account.id,policy?.includedInNet!=false,
                    policy?.liabilityConvention ?: FinanceLiabilityConvention.SIGNED,FinanceBalanceBasis.KNOWN_POSTED,policy?.revision,account.updatedAt)}},enabled=!state.busy) {Text("确认此起点也适用于确知入账口径")}
            }
        }
    }
    if(repaymentOpen) key(account.id) {
        FinanceEntrySheet(state,null,vm,initialKind=FinanceEditorKind.TRANSFER,
            initialAccountId=s.accounts.firstOrNull {it.id!=account.id && it.archivedAt==null && it.currencyCode==account.currencyCode}?.id,
            initialTargetAccountId=account.id,onSaved={repaymentOpen=false}) {repaymentOpen=false}
    }
    anchorPreview?.let { preview -> AlertDialog(onDismissRequest={anchorPreview=null},title={Text("确认余额起点影响")},text={Column {
        Text("当前推算 "+FinanceMoney.display(preview.oldBalanceMinor,account.currencyCode))
        Text("更改后推算 "+FinanceMoney.display(preview.proposedBalanceMinor,account.currencyCode))
        Text("${preview.affectedReconciliations} 个已保存核对需重新计算；原流水 ID、金额和时间保留。")
    }},confirmButton={TextButton({vm.workspaceAction(epoch,{anchorPreview=null;editingAnchor=false}) {repo,token ->repo.confirmAnchor(token,preview)}},enabled=!state.busy){Text("确认更改起点")}},dismissButton={TextButton({anchorPreview=null}){Text("返回修改")}}) }
}

@Composable
internal fun FinancePostingEditor(state:FinanceUiState,row:FinanceLedgerRow,vm:FinanceViewModel,epoch:Long) {
    var open by rememberSaveable(row.id) {mutableStateOf(false)}
    val timing=state.snapshot.v16?.entryTimingRows.orEmpty().singleOrNull {it.entryId==row.entry.id && it.voidedAt==null}
    var dateOnly by rememberSaveable(row.id) {mutableStateOf(timing?.postedPrecision==FinanceTimePrecision.DATE)}
    var zone by rememberSaveable(row.id) {mutableStateOf(timing?.postedZoneId ?: if(dateOnly) "" else ZoneId.systemDefault().id)}
    var text by rememberSaveable(row.id) {mutableStateOf(if(dateOnly) timing?.postedLocalDate.orEmpty() else timing?.postedAt?.let(FinanceTime::input).orEmpty())}
    TextButton({open=!open}) {Text("入账日期 · "+(timing?.postedTimeLabel() ?: "未知"))}
    if(open) {
        Row {Checkbox(dateOnly,{dateOnly=it});Text("仅知道日历日期")}
        OutlinedTextField(text,{text=it},label={Text(if(dateOnly) "原始日期 · 年-月-日；留空恢复未知" else "确知入账时间；留空恢复未知")},modifier=Modifier.fillMaxWidth())
        if(dateOnly) {
            OutlinedTextField(zone,{zone=it},label={Text("日期所属时区")})
            Text("只确认这一天，不代表已知当天的入账先后。旧记录缺少依据时须明确补充，不从旧时间猜日期。",style=LifeText.caption)
        }
        TextButton({vm.workspaceAction(epoch,{open=false}) {repo,token ->
            val civil=if(dateOnly && text.isNotBlank()) FinanceCivilDate.capture(java.time.LocalDate.parse(text.trim()),ZoneId.of(zone.trim())) else null
            val at=if(civil!=null) timing?.postedAt?.takeIf {timing?.postedCivilDate()==civil} ?: civil.startInclusive
                else text.takeIf(String::isNotBlank)?.let(FinanceTime::parse)
            repo.setPostingDate(token,row.entry.id,row.entry.revision,at,
                if(text.isBlank()) FinanceTimePrecision.UNKNOWN else if(dateOnly) FinanceTimePrecision.DATE else FinanceTimePrecision.INSTANT,
                "用户明确确认",civil)
        }},enabled=!state.busy) {Text("保存入账事实")}
    }
}
