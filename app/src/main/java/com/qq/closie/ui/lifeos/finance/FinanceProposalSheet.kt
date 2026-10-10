package com.qq.closie.ui.lifeos.finance

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
    } else if (proposal != null) key(id) {
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
    var editing by rememberSaveable { mutableStateOf(false) }
    var kind by rememberSaveable { mutableStateOf(proposal.kind.name) }
    var amount by rememberSaveable { mutableStateOf(proposal.amountMinor?.let { FinanceMoney.input(it) }.orEmpty()) }
    var account by rememberSaveable { mutableStateOf(proposal.accountId.orEmpty()) }
    var target by rememberSaveable { mutableStateOf(proposal.targetAccountId.orEmpty()) }
    var description by rememberSaveable { mutableStateOf(proposal.description) }
    var date by rememberSaveable { mutableStateOf(FinanceTime.input(proposal.occurredAt)) }
    var category by rememberSaveable { mutableStateOf(proposal.category) }
    var subcategory by rememberSaveable { mutableStateOf(proposal.subcategory) }
    var tags by rememberSaveable { mutableStateOf(state.data.tags.filter { it.proposalId == proposal.id }.joinToString("，") { it.name }) }
    var relation by remember { mutableStateOf(FinanceRelationshipDraft(nature = proposal.nature,
        role = if (proposal.evidenceText.contains("退款")) FinanceFlowRole.REFUND else
            if (proposal.kind == FinanceProposalKind.INCOME) FinanceFlowRole.OTHER else FinanceFlowRole.PAYMENT)) }
    val accounts = state.finance.accounts.filter { it.archivedAt == null && it.currencyCode == "CNY" }
    fun later() = vm.dismiss(proposal, FinanceProposalStatus.SNOOZED, close)
    FinanceReceipt(if (proposal.source == FinanceSource.WECHAT) "微信 · 发现一笔" else "支付宝 · 发现一笔",
        state.busy, state.error, ::later) {
        Text(if (amount.isBlank()) "金额待确认（通知中有多个金额）" else "¥$amount", style = MaterialTheme.typography.headlineMedium)
        Text(description)
        Text(listOf(category, subcategory).filter(String::isNotBlank).joinToString(" / ").ifBlank { "尚未分类" })
        Text(accounts.find { it.id == account }?.name ?: "需要选择账户")
        Text("尚未入账 · ${FinanceTime.input(proposal.occurredAt)}", style = MaterialTheme.typography.bodySmall)
        if (editing) {
            EnumChoice("类型", kind, FinanceEditorKind.entries.map { it.name to it.label }, !state.busy) {
                kind = it; relation = relation.copy(role = if (it == FinanceProposalKind.INCOME.name) FinanceFlowRole.OTHER else FinanceFlowRole.PAYMENT)
            }
            OutlinedTextField(amount, { amount = it }, label = { Text("金额") }, enabled = !state.busy)
            AccountChoice("账户", accounts, account, !state.busy) { account = it }
            if (kind == FinanceProposalKind.TRANSFER.name) AccountChoice("转入账户", accounts.filter { it.id != account }, target, !state.busy) { target = it }
            OutlinedTextField(description, { description = it }, label = { Text("事由") }, enabled = !state.busy)
            OutlinedTextField(date, { date = it }, label = { Text("发生时间 · 年-月-日 时:分") }, enabled = !state.busy)
            if (kind != FinanceProposalKind.TRANSFER.name) {
                OutlinedTextField(category, { category = it }, label = { Text("分类") }, enabled = !state.busy)
                OutlinedTextField(subcategory, { subcategory = it }, label = { Text("子分类") }, enabled = !state.busy)
                OutlinedTextField(tags, { tags = it }, label = { Text("标签 · 逗号分隔") }, enabled = !state.busy)
                FinanceRelationshipFields(state.finance, relation, !state.busy) { relation = it }
            }
            Text("通知原文", style = MaterialTheme.typography.labelMedium)
            Text(proposal.evidenceText, style = MaterialTheme.typography.bodySmall)
        }
        Row {
            TextButton(onClick = { editing = !editing }, enabled = !state.busy) { Text(if (editing) "收起修改" else "修改") }
            Button(onClick = {
                if (account.isBlank() || amount.isBlank()) editing = true
                else vm.confirm(proposal, FinanceEntryDraft(FinanceEditorKind.valueOf(kind), amount, account, target,
                    date, description, category, tags, proposal.occurredAt, subcategory, relation), close)
            }, enabled = !state.busy) { Text("确认") }
            TextButton(onClick = ::later, enabled = !state.busy) { Text("稍后") }
            TextButton(onClick = { vm.dismiss(proposal, FinanceProposalStatus.REJECTED, close) }, enabled = !state.busy) { Text("忽略") }
        }
    }
}
