package com.qq.closie.ui.lifeos.finance

import com.qq.closie.life.ui.theme.BundledText as Text

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.qq.closie.life.finance.*
import com.qq.closie.life.data.LifeContainer
import com.qq.closie.navigation.modules.PresentationFactory

@Composable
internal fun FinanceGlobalReviewHost(container: LifeContainer) {
    val requested by FinanceReviewRequests.pending.collectAsStateWithLifecycle()
    if (requested.isEmpty()) return
    val vm: FinanceAutomationViewModel = viewModel(key = "finance-global-review",
        factory = PresentationFactory(FinanceAutomationViewModel::class.java) {
            FinanceAutomationViewModel(container.financeAutomationRepository, container.financeRepository)
        })
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val id = requested.first()
    val proposal = state.data.proposals.find { it.id == id }
    fun close() { FinanceReviewRequests.close(id); FinanceReviewNotifications.cancel(context, id) }
    if (proposal != null && proposal.status !in setOf(FinanceProposalStatus.ACTIVE, FinanceProposalStatus.SNOOZED)) {
        LaunchedEffect(id, proposal?.status) { close() }
    } else if (proposal != null) key(id, proposal.revision) {
        FinanceProposalSheet(proposal, state, vm, ::close)
    } else if (state.error != null) FinanceReceipt("暂时无法读取账目", error = state.error, onClose = ::close) {
        Text("收起后可从账目 → 自动发现再次打开。")
    } else if (state.loaded && proposal == null) FinanceReceipt("正在查找记录", onClose = ::close) {
        Text("若记录已随恢复操作失效，可收起后进入账目查看。")
    }
}

@Composable
private fun FinanceProposalSheet(proposal: FinanceProposalEntity, state: AutomationUiState,
    vm: FinanceAutomationViewModel, close: () -> Unit) {
    val saved=remember(proposal.id) {state.finance.v16?.reviewSessionRows.orEmpty().singleOrNull {it.proposalId==proposal.id && it.voidedAt==null && it.state!=FinanceReviewState.COMPLETED}}
    val resumed=remember(proposal.id) {saved?.toEntryDraft(state.finance)}
    val editor=rememberSaveable {java.util.UUID.randomUUID().toString()}
    val epoch=rememberSaveable {state.finance.v16?.controlRows?.singleOrNull()?.restoreEpoch ?: 0L}
    if(epoch!=(state.finance.v16?.controlRows?.singleOrNull()?.restoreEpoch ?: 0L)) {
        FinanceReceipt("复核代际已改变",onClose=close) {Text("请收起后重新打开当前候选，旧编辑不会覆盖恢复后的数据。")};return
    }
    var editing by rememberSaveable { mutableStateOf(false) }
    var kind by rememberSaveable { mutableStateOf(resumed?.kind?.name ?: proposal.kind.name) }
    var amount by rememberSaveable { mutableStateOf(resumed?.amount ?: proposal.amountMinor?.let { FinanceMoney.input(it) }.orEmpty()) }
    var account by rememberSaveable { mutableStateOf(resumed?.accountId ?: proposal.accountId.orEmpty()) }
    var target by rememberSaveable { mutableStateOf(resumed?.targetAccountId ?: proposal.targetAccountId.orEmpty()) }
    var description by rememberSaveable { mutableStateOf(resumed?.description ?: proposal.description) }
    var date by rememberSaveable { mutableStateOf(resumed?.occurredAt ?: FinanceTime.input(proposal.occurredAt)) }
    var category by rememberSaveable { mutableStateOf(resumed?.category ?: proposal.category) }
    var subcategory by rememberSaveable { mutableStateOf(resumed?.subcategory ?: proposal.subcategory) }
    var selectedCategoryId by rememberSaveable { mutableStateOf(resumed?.categoryId ?: FinanceCategoryTree(state.finance.categories).find(proposal.category, proposal.subcategory)?.id) }
    var pickingCategory by remember { mutableStateOf(false) }
    var tags by rememberSaveable { mutableStateOf(resumed?.tags ?: state.data.tags.filter { it.proposalId == proposal.id }.joinToString("，") { it.name }) }
    var relation by rememberSaveable(stateSaver = FinanceRelationshipDraft.Saver) { mutableStateOf(resumed?.relationship ?: FinanceRelationshipDraft.fromProposal(proposal)) }
    var costReduction by rememberSaveable {mutableStateOf(resumed?.costReductionText.orEmpty())}
    val accounts = state.finance.accounts.filter { it.archivedAt == null && it.currencyCode == "CNY" }
    val draft = FinanceEntryDraft(FinanceEditorKind.valueOf(kind), amount, account, target, date,
        description, category, tags, resumed?.preservedOccurredAt ?: proposal.occurredAt, subcategory, relation, selectedCategoryId,costReduction)
    LaunchedEffect(draft) {kotlinx.coroutines.delay(250);vm.persistReview(editor,saved?.revision,proposal,draft,epoch)}
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_STOP) {
        vm.flushReviewOnStop(editor,saved?.revision,proposal,draft,epoch)
    }
    val parsedAmount = runCatching { FinanceCalculator.evaluate(amount) }.getOrNull()
    val parsedTime = runCatching { FinanceTime.resolve(date, proposal.occurredAt) }.getOrDefault(proposal.occurredAt)
    val strongPeers = FinanceEvidenceCorrelation.blocking(proposal, state.data.proposals,state.finance.v16)
    val duplicates = parsedAmount?.let { FinanceDuplicateReview.candidates(proposal.id, FinanceProposalKind.valueOf(kind),
        it, parsedTime, state.data.proposals, state.finance) }.orEmpty()
    var distinctConfirmed by remember(proposal.id, duplicates, kind, parsedAmount, parsedTime) { mutableStateOf(false) }
    var existingLink by remember {mutableStateOf<FinanceLedgerRow?>(null)}
    val reviewed = if (distinctConfirmed) duplicates else emptySet()
    val receipt = FinanceReviewReceiptModel.from(proposal,
        state.finance.copy(v4 = (state.finance.v4 ?: FinanceV4Snapshot()).copy(proposals = state.data.proposals)),
        state.data.tags.filter { it.proposalId == proposal.id }.map { it.name },
        FinanceProposalPrivacy.userDescriptions(state.data.rules.flatMap { it.actions }), draft, reviewed)
    fun later() = vm.laterReview(editor,saved?.revision,proposal,draft,epoch,close)
    FinanceReceipt("消费小票",
        state.busy, state.error, ::later, actions = {
        Row {
            TextButton(onClick = { editing = !editing }, enabled = !state.busy) { Text(if (editing) "收起修改" else "修改") }
            Button(onClick = {
                if (!editing) {
                    if (receipt.directlyConfirmable) vm.confirmSession(editor,saved?.revision,proposal,draft,epoch,reviewed,close) else editing = true
                } else if (account.isBlank() || amount.isBlank() || (kind != FinanceProposalKind.TRANSFER.name && selectedCategoryId == null && category.isBlank())) editing = true
                else vm.confirmSession(editor,saved?.revision,proposal,draft,epoch,reviewed,close)
            }, enabled = !state.busy && strongPeers.isEmpty()) { Text(if (strongPeers.isNotEmpty()) "编号冲突" else if (!editing && !receipt.directlyConfirmable) "需要完善" else "确认") }
            TextButton(onClick = ::later, enabled = !state.busy) { Text("稍后") }
            TextButton(onClick = { vm.dismiss(proposal, FinanceProposalStatus.REJECTED, close) }, enabled = !state.busy) { Text("忽略") }
        }
    }) {
        FinanceAttachments(state.finance,FinanceMediaTarget(proposalId=proposal.id),state.busy,state.error) {block->vm.mediaAction(epoch,block)}
        FinanceEvidenceClassification(proposal,state.finance,state.busy) {evidence,identity,instrument->
            vm.classifyReceipt(editor,saved?.revision,proposal,draft,epoch,evidence,identity,instrument)
        }
        if (strongPeers.isNotEmpty()) {
            Text("同一交易编号出现不同金额或时间，请先核对。不能勾选“另一笔交易”来重复入账。")
            strongPeers.forEach { peer ->
                Text("${peer.discoverySourceLabel()} · ${peer.amountMinor?.let { FinanceMoney.display(it) } ?: "金额待核对"} · ${FinanceTime.input(peer.occurredAt)} · ${if (peer.status == FinanceProposalStatus.CONFIRMED) "已入账" else "待确认"}", style = MaterialTheme.typography.bodySmall)
            }
            Text("同一笔请忽略多余的小票；已有记录需更正时，从账目打开原记录修改。", style = MaterialTheme.typography.bodySmall)
        }
        if (duplicates.isNotEmpty()) {
            Text("发现相近金额／时间的候选或已入账流水。请先核对，不能据此自动判断为同一笔。")
            state.data.proposals.filter { "proposal:${it.id}:${it.revision}" in duplicates }.forEach {
                val source = it.discoverySourceLabel()
                val status = if (it.status == FinanceProposalStatus.CONFIRMED) "已确认" else "待确认"
                Text("候选 · ${FinanceTime.input(it.occurredAt)} · $source · $status", style = MaterialTheme.typography.bodySmall)
            }
            state.finance.entries.filter { "entry:${it.id}:${it.revision}" in duplicates }.forEach { entry ->
                val accountName = state.finance.accounts.find { it.id == entry.accountId }?.name.orEmpty()
                Text("已入账 · ${FinanceTime.input(entry.occurredAt)} · $accountName · ${entry.description}", style = MaterialTheme.typography.bodySmall)
            }
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Checkbox(distinctConfirmed, { distinctConfirmed = it }, enabled = !state.busy)
                Text("已核对，这是另一笔真实交易")
            }
            Text("若是同一笔，点“忽略”当前小票；可从账目查看已有记录。", style = MaterialTheme.typography.bodySmall)
        }
        val existing=FinanceProjection.ledger(state.finance).filter {it.entry.amountMinor==parsedAmount && it.account.id==account &&
            if(kind==FinanceEditorKind.TRANSFER.name) it.transfer!=null && it.targetAccount?.id==target
            else it.transfer==null && (kind==FinanceEditorKind.INCOME.name)==(it.entry.direction==FinanceDirection.INFLOW)}
        if(existing.isNotEmpty()) {
            Text("若已手工记过，可只补来源关联，不新增或改写流水。",style=MaterialTheme.typography.bodySmall)
            existing.take(20).forEach {row->TextButton({existingLink=row},enabled=!state.busy) {Text("关联已有："+FinanceTime.input(row.entry.occurredAt)+" · "+row.entry.description.ifBlank {row.account.name})}}
        }
        Text(if (amount.isBlank()) "金额待确认（通知中有多个金额）" else "¥$amount", style = com.qq.closie.life.ui.theme.LifeType.Money)
        Text("${receipt.source} · ${when (receipt.direction) { FinanceProposalKind.INCOME -> "收入"; FinanceProposalKind.TRANSFER -> "转账"; else -> "支出" }}")
        Text("账户：${accounts.find { it.id == account }?.name ?: "待选择"}")
        Text("分类：${FinanceProjection.categoryPath(state.finance.categories, selectedCategoryId) ?: FinanceCategoryTree.displayProposalPath(category, subcategory) ?: "待选择"}")
        Text("说明：$description")
        Text("业务性质", style = MaterialTheme.typography.labelMedium)
        androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(4) { index ->
                val nature = listOf(FinanceNature.PERSONAL, FinanceNature.REIMBURSABLE, FinanceNature.PROXY_PURCHASE, FinanceNature.SPLIT)[index]
                FilterChip(relation.nature == nature, {
                    val role = if (kind != FinanceProposalKind.INCOME.name) relation.role else when (nature) {
                        FinanceNature.REIMBURSABLE -> FinanceFlowRole.REIMBURSEMENT
                        FinanceNature.PROXY_PURCHASE, FinanceNature.SPLIT -> FinanceFlowRole.SETTLEMENT
                        else -> if (proposal.role == FinanceFlowRole.REFUND) FinanceFlowRole.REFUND else FinanceFlowRole.OTHER
                    }
                    relation = relation.copy(nature = nature, role = role)
                }, enabled = !state.busy,
                    label = { Text(if (nature == FinanceNature.PROXY_PURCHASE) "代付·代拍" else nature.label) })
            }
        }
        if (!editing) receipt.ambiguityReason?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        Text("尚未入账 · ${FinanceTime.input(receipt.occurredAt)}", style = MaterialTheme.typography.bodySmall)
        if (editing) {
            EnumChoice("类型", kind, FinanceEditorKind.entries.map { it.name to it.label }, !state.busy) {
                kind = it; relation = relation.copy(role = if (it == FinanceProposalKind.INCOME.name) FinanceFlowRole.OTHER else FinanceFlowRole.PAYMENT)
            }
            OutlinedTextField(amount, { amount = it }, label = { Text("金额") }, enabled = !state.busy, textStyle = com.qq.closie.life.ui.theme.LifeType.Money)
            AccountChoice("账户", accounts, account, !state.busy) { account = it }
            if (kind == FinanceProposalKind.TRANSFER.name) AccountChoice("转入账户", accounts.filter { it.id != account }, target, !state.busy) { target = it }
            OutlinedTextField(description, { description = it }, label = { Text("备注（可留空）") }, enabled = !state.busy, textStyle = com.qq.closie.life.ui.theme.LifeType.inputFor(description))
            OutlinedTextField(date, { date = it }, label = { Text("发生时间 · 年-月-日 时:分") }, enabled = !state.busy, textStyle = com.qq.closie.life.ui.theme.LifeType.MonoNumber)
            if (kind != FinanceProposalKind.TRANSFER.name) {
                TextButton({ pickingCategory = !pickingCategory }, enabled = !state.busy) { Text("选择分类") }
                if (pickingCategory) FinanceCategoryFields(state.finance, selectedCategoryId,
                    { selectedCategoryId = it; pickingCategory = false }, state.busy, vm::createCategory)
                if (selectedCategoryId == null) {
                    OutlinedTextField(category, { category = it }, label = { Text("新主分类") }, enabled = !state.busy, textStyle = com.qq.closie.life.ui.theme.LifeType.inputFor(category))
                    OutlinedTextField(subcategory, { subcategory = it }, label = { Text("新子分类") }, enabled = !state.busy, textStyle = com.qq.closie.life.ui.theme.LifeType.inputFor(subcategory))
                } else TextButton({ selectedCategoryId = null; category = ""; subcategory = "" }, enabled = !state.busy) { Text("改为自定义分类") }
                OutlinedTextField(tags, { tags = it }, label = { Text("标签 · 逗号分隔") }, enabled = !state.busy, textStyle = com.qq.closie.life.ui.theme.LifeType.inputFor(tags))
                FinanceRelationshipFields(state.finance, relation, !state.busy) { relation = it }
                if(relation.role in setOf(FinanceFlowRole.REFUND,FinanceFlowRole.REBATE,FinanceFlowRole.CASHBACK))
                    OutlinedTextField(costReduction,{costReduction=it},label={Text("本次冲减个人成本（新经济事项必填，可为0）")},enabled=!state.busy)
            }
            Text("识别依据", style = MaterialTheme.typography.labelMedium)
            Text("来源：${receipt.source}", style = MaterialTheme.typography.bodySmall)
            Text("时间：${FinanceTime.input(proposal.occurredAt)} · 金额：${proposal.amountMinor?.let { FinanceMoney.display(it, "CNY") } ?: "待核对"}", style = MaterialTheme.typography.bodySmall)
            Text("原始通知或页面仅用于本机即时识别，不保存。", style = MaterialTheme.typography.bodySmall)
        }

    }
    existingLink?.let {row->AlertDialog(onDismissRequest={existingLink=null},title={Text("只关联已有记录？")},text={Text(
        FinanceTime.input(row.entry.occurredAt)+" · "+row.account.name+" · "+FinanceMoney.display(row.entry.amountMinor,row.currencyCode)+
        "。当前小票会标记为已关联，已有记录的备注、时间、金额及余额均保留。")},confirmButton={TextButton({
            vm.linkExisting(editor,saved?.revision,proposal,draft,epoch,row){existingLink=null;close()}
        },enabled=!state.busy){Text("确认是同一笔，仅补来源")}},dismissButton={TextButton({existingLink=null}){Text("取消")}})}

}
