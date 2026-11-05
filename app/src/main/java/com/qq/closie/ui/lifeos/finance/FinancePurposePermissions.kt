package com.qq.closie.ui.lifeos.finance

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.Button
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import com.qq.closie.life.finance.*
import com.qq.closie.life.ui.theme.BundledText as Text
import com.qq.closie.ui.lifeos.theme.LifeText

/** Both routes use the same purpose grants and the same command/epoch checks. */
@Composable
internal fun FinancePurposePermissions(state: FinanceUiState, vm: FinanceViewModel) {
    val s = state.snapshot
    val v = s.v16
    val context = LocalContext.current
    val epoch = rememberSaveable { v?.controlRows?.singleOrNull()?.restoreEpoch ?: 0L }
    var share by remember {mutableStateOf<FinanceSharePreview?>(null)}
    var shareError by remember {mutableStateOf<String?>(null)}
    HorizontalDivider()
    Text("财务 · 分用途授权", style = LifeText.title)
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
    val grants = v?.purposeGrantRows.orEmpty().sortedByDescending { it.createdAt }
    Text("授权历史", style = LifeText.title)
    if (grants.isEmpty()) Text("暂无授权记录", style = LifeText.caption)
    FinancePagedItems(grants,"purpose-grants",{it.id}) {g->Row {
        Text(g.purpose.name + " · " + when {
            g.voidedAt != null -> "已撤销"
            g.expiresAt <= System.currentTimeMillis() -> "已过期"
            else -> "至 " + FinanceTime.input(g.expiresAt)
        })
        if (g.voidedAt == null) TextButton({vm.workspaceAction(epoch) {repo,token->repo.revoke(token,g.id)}},enabled=!state.busy){Text("撤销")}
        if(g.purpose==FinanceReadPurpose.SHARE && g.voidedAt == null && g.expiresAt > System.currentTimeMillis()) TextButton({
            runCatching {FinanceShareProjection.preview(s,g.id,System.currentTimeMillis())}.onSuccess {share=it;shareError=null}.onFailure {shareError=it.message}
        },enabled=!state.busy) {Text("预览此范围分享")}
    }}
    shareError?.let {Text(it,color=MaterialTheme.colorScheme.error)}
    Text("云AI默认关闭，当前没有真实云连接或数据发送。Home、分享、本地查询和AI分别授权。未来可选的自动加密同步目标保留。",style=LifeText.caption)
    share?.let {preview->FinanceReceipt("核对分享内容",state.busy,state.error,{share=null},actions={Button({vm.shareFinance(context,preview){share=null}},enabled=!state.busy){Text("确认并选择接收应用")}}) {
        Text("以下是唯一将交给所选应用的明文内容。接收者可保存它；原图、账户身份、未授权字段不在此片段中。")
        Text(preview.text)
    }}
}
