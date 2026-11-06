package com.qq.closie.ui.lifeos.finance

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.TextButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.painterResource
import androidx.compose.material3.Icon
import com.qq.closie.R
import com.qq.closie.life.finance.*
import com.qq.closie.life.ui.theme.BundledText as Text
import com.qq.closie.ui.lifeos.theme.LifeText
import com.qq.closie.ui.lifeos.theme.LocalLifeOsColors

/** Reads one coherent snapshot. Editing and opening a canonical record are explicit host actions. */
@Composable
internal fun FinanceEconomicDetails(
    snapshot: FinanceSnapshot, eventId: String, onEdit: () -> Unit,
    onOpenCash: (String) -> Unit, onOpenEvidence: ((List<FinanceMediaLinkEntity>)->Unit)?=null, onClose: () -> Unit
) {
    val event = snapshot.v4?.events.orEmpty().singleOrNull { it.id == eventId && it.voidedAt == null }
    val v = snapshot.v16
    val detail = v?.economicRows.orEmpty().singleOrNull { it.eventId == eventId && it.voidedAt == null }
    val colors = LocalLifeOsColors.current
    FinanceEconomicPaper(event?.description?.ifBlank { "经济事项" } ?: "经济事项", onClose, actions = {
        if (event != null) TextButton(onEdit, contentPadding = PaddingValues(0.dp),
            modifier = Modifier.semantics { contentDescription = "编辑出资与承担" }) { Text("编辑", style = LedgerText.caption) }
    }) {
        if (event == null) {
            Text("事项已变化或不可用", style = LedgerText.body)
            Text("请关闭后重新查看当前记录。", style = LedgerText.body)
        } else {
            val currency = event.currencyCode
            val links = snapshot.v4?.activeLinks.orEmpty().filter { it.eventId == event.id }
            val entries = snapshot.entries.filter { it.voidedAt == null }.associateBy { it.id }
            val accounts = snapshot.accounts.associateBy { it.id }
            val costs = FinanceCostProjection.rows(snapshot, includeExcluded = true).filter { it.eventId == event.id }
            val personal = FinanceCostProjection.total(costs)
            val personalLabel = when {
                costs.isEmpty() -> event.personalShareMinor?.let { FinanceMoney.display(it, currency) } ?: "待确认"
                !personal.complete -> "已确认 " + FinanceMoney.display(personal.confirmedMinor, currency) + " · 另有待确认"
                else -> FinanceMoney.display(personal.confirmedMinor, currency)
            }
            val cash = links.mapNotNull { link -> entries[link.entryId]?.let { link to it } }
            val cashDelta = exactSum(cash.map { (link, entry) ->
                if (entry.direction == FinanceDirection.INFLOW) link.allocatedMinor else -link.allocatedMinor
            })
            val obligations = FinanceObligationProjection.rows(snapshot).filter { it.obligation.eventId == event.id }
            val parties = v?.partyRows.orEmpty().associate { it.id to it.name }
            fun party(id: String?) = if (id == FinanceV16Integrity.SELF) "本人" else parties[id] ?: "待确认参与方"
            Text("业务性质：" + event.nature.label + " · " + FinanceTime.input(event.occurredAt), style = LedgerText.caption, color = colors.inkSecondary)
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                EconomicMetric("事项价值", detail?.valueMinor?.let { FinanceMoney.display(it, currency) } ?: "待确认", Modifier.weight(1f))
                EconomicMetric("本人承担", personalLabel, Modifier.weight(1f))
            }
            detail?.valueBasis?.takeIf(String::isNotBlank)?.let { Text("价值依据 · " + it, style = LedgerText.caption) }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                EconomicMetric("本人现金变化", if (cash.isEmpty()) "暂无本人流水" else FinanceMoney.display(cashDelta, currency), Modifier.weight(1f))
                val receivable = exactSum(obligations.filter { it.obligation.creditorId == FinanceV16Integrity.SELF }.map { it.openMinor })
                val payable = exactSum(obligations.filter { it.obligation.debtorId == FinanceV16Integrity.SELF }.map { it.openMinor })
                EconomicMetric("应收 / 应付", if (obligations.isEmpty()) "暂无确认义务" else
                    "收 " + FinanceMoney.display(receivable, currency) + " / 付 " + FinanceMoney.display(payable, currency), Modifier.weight(1f))
            }
            Text("按此事项分配的真实收付金额列示。", style = LedgerText.caption, color = colors.inkSecondary)
            HorizontalDivider(color = colors.line)
            Text("关联流水", style = LedgerText.body)
            if (cash.isEmpty()) Text("暂无本人流水", style = LedgerText.body)
            cash.sortedByDescending { it.second.occurredAt }.forEach { (link, entry) ->
                val timing = v?.entryTimingRows.orEmpty().singleOrNull { it.entryId == entry.id && it.voidedAt == null }
                val time = if (timing?.cashPrecision == FinanceTimePrecision.UNKNOWN) "收付时间未知"
                    else FinanceTime.input(entry.occurredAt)
                TextButton({ onOpenCash(entry.id) }, modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(0.dp)) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 54.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                        Icon(painterResource(R.drawable.lifeos_finance_economic_cash), null, Modifier.size(19.dp), tint = colors.accentDeep)
                        Column(Modifier.weight(1f)) {
                            Text((accounts[entry.accountId]?.name ?: "账户") + " · " + link.role.label, style = LedgerText.body, color = colors.ink)
                            Text(time + " · " + entry.description, style = LedgerText.meta, color = colors.inkSecondary)
                        }
                        LedgerOdometer((if (entry.direction == FinanceDirection.INFLOW) "+" else "-") + FinanceMoney.display(link.allocatedMinor, currency),
                            style = LedgerText.rowAmount, color = if (entry.direction == FinanceDirection.INFLOW) colors.accentDeep else colors.ink)
                    }
                }
                HorizontalDivider(color = colors.line)
            }
            HorizontalDivider(color = colors.line)
            Text("出资与承担", style = LedgerText.body)
            val funding = v?.fundingRows.orEmpty().filter { it.eventId == event.id && it.voidedAt == null }
            if (funding.isEmpty()) Text("尚未确认出资分配", style = LedgerText.body)
            funding.forEach { row ->
                Text(party(row.partyId) + "出资 · " + FinanceMoney.display(row.amountMinor, currency), style = LedgerText.body)
                Text(row.basis, style = LedgerText.caption, color = colors.inkSecondary)
            }
            val shares = v?.shareRows.orEmpty().filter { it.eventId == event.id && it.voidedAt == null }
            shares.forEach { row ->
                val relation = when (row.relation) {
                    FinanceShareRelation.PERSONAL_CONFIRMED -> "承担已确认"
                    FinanceShareRelation.GIFT_CONFIRMED -> "赠送已确认"
                    FinanceShareRelation.REPAYABLE_CONFIRMED -> "需归还已确认"
                    FinanceShareRelation.UNRESOLVED -> "关系待确认"
                }
                Text(party(row.beneficiaryId) + "受益 · " +
                    (row.amountMinor?.let { FinanceMoney.display(it, currency) } ?: "金额待确认"), style = LedgerText.body)
                Text(relation + " · 最终承担 " + party(row.bearerId), style = LedgerText.caption, color = colors.inkSecondary)
            }
            if (detail?.allocationsComplete == false) Text("仍有未分配部分，其承担保持未知。", style = LedgerText.caption)
            HorizontalDivider(color = colors.line)
            Text("应收应付与结算", style = LedgerText.body)
            if (obligations.isEmpty()) Text("暂无已确认的应收应付", style = LedgerText.body)
            obligations.forEach { row ->
                val debt = row.obligation
                Text(party(debt.debtorId) + "应付给" + party(debt.creditorId) + " · " +
                    FinanceMoney.display(debt.amountMinor, debt.currencyCode), style = LedgerText.body)
                Text("真实结算 " + FinanceMoney.display(row.settledMinor, debt.currencyCode) + " · 尚余 " +
                    FinanceMoney.display(row.openMinor, debt.currencyCode), style = LedgerText.caption)
                v?.resolutionRows.orEmpty().filter { it.obligationId == debt.id && it.voidedAt == null }.forEach { resolution ->
                    Text((if (resolution.kind == FinanceResolutionKind.HISTORICAL_SETTLED) "历史结清声明" else "已放弃或免除") + " · " +
                        FinanceMoney.display(resolution.amountMinor, debt.currencyCode) + " · " + resolution.note, style = LedgerText.caption)
                }
                if (row.openMinor == 0L) Text(if (row.cashEvidenceComplete) "已结清，真实现金证据完整"
                    else "工作已结束，历史现金证据仍不完整", style = LedgerText.caption, color = colors.inkSecondary)
            }
            val attachments = financeEventEvidence(snapshot,event.id)
            HorizontalDivider(color = colors.line)
            Text("凭据", style = LedgerText.body)
            if (attachments.isEmpty()) Text("暂无凭据", style = LedgerText.body)
            if(attachments.isNotEmpty() && onOpenEvidence!=null) TextButton({onOpenEvidence(attachments)},
                contentPadding=PaddingValues(0.dp),modifier=Modifier.semantics {contentDescription="查看事项凭据"}) {
                Text(attachments.joinToString("、"){it.label.ifBlank {"凭据附件"}},style=LedgerText.body)
            } else attachments.forEach { Text(it.label.ifBlank { "凭据附件" }, style = LedgerText.body) }
        }
    }
}

@Composable
private fun FinanceEconomicPaper(title: String, close: () -> Unit, actions: @Composable RowScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit) {
    val colors = LocalLifeOsColors.current
    com.qq.closie.ui.lifeos.components.SuspendFunctionPageSwipe()
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize().semantics { paneTitle = "经济事项详情" }, color = colors.paper) {
            Scaffold(containerColor = colors.paper, topBar = { FinanceAuxTopBar(title, close, actions = actions) }) { padding ->
                Column(Modifier.padding(padding).fillMaxSize().navigationBarsPadding().verticalScroll(rememberScrollState())
                    .padding(horizontal = 32.dp).padding(top = 4.dp, bottom = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
            }
        }
    }
}

@Composable
private fun EconomicMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(label, style = LedgerText.caption, color = LocalLifeOsColors.current.inkSecondary)
        LedgerOdometer(value, style = LedgerText.title.copy(fontSize = 18.sp, lineHeight = 24.sp), color = LocalLifeOsColors.current.ink)
    }
}
