package com.qq.closie.ui.lifeos.finance

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.qq.closie.life.finance.*
import com.qq.closie.life.ui.theme.BundledText as Text
import com.qq.closie.ui.lifeos.theme.LifeText
import com.qq.closie.ui.lifeos.theme.LocalLifeOsColors

/** Reads one coherent snapshot. Editing and opening a canonical record are explicit host actions. */
@Composable
internal fun FinanceEconomicDetails(
    snapshot: FinanceSnapshot, eventId: String, onEdit: () -> Unit,
    onOpenCash: (String) -> Unit, onClose: () -> Unit
) {
    val event = snapshot.v4?.events.orEmpty().singleOrNull { it.id == eventId && it.voidedAt == null }
    val v = snapshot.v16
    val detail = v?.economicRows.orEmpty().singleOrNull { it.eventId == eventId && it.voidedAt == null }
    val colors = LocalLifeOsColors.current
    FinanceReceipt("经济事项详情", onClose = onClose, actions = {
        if (event != null) Button(onEdit, Modifier.fillMaxWidth()) { Text("编辑出资与承担") }
    }) {
        if (event == null) {
            Text("事项已变化或不可用", style = LifeText.title)
            Text("请关闭后重新查看当前记录。", style = LifeText.body)
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
            Text(event.description.ifBlank { "经济事项" }, style = LifeText.title, color = colors.ink)
            Text(FinanceTime.input(event.occurredAt) + " · " + event.nature.label, style = LifeText.caption, color = colors.inkSecondary)
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                EconomicMetric("事项价值", detail?.valueMinor?.let { FinanceMoney.display(it, currency) } ?: "待确认", Modifier.weight(1f))
                EconomicMetric("本人承担", personalLabel, Modifier.weight(1f))
            }
            detail?.valueBasis?.takeIf(String::isNotBlank)?.let { Text("价值依据 · " + it, style = LifeText.caption) }
            EconomicMetric("本人现金变化", if (cash.isEmpty()) "暂无本人流水" else FinanceMoney.display(cashDelta, currency))
            Text("按此事项分配的真实收付金额列示。", style = LifeText.caption, color = colors.inkSecondary)
            HorizontalDivider(color = colors.line)
            Text("出资与承担", style = LifeText.title)
            val funding = v?.fundingRows.orEmpty().filter { it.eventId == event.id && it.voidedAt == null }
            if (funding.isEmpty()) Text("尚未确认出资分配", style = LifeText.body)
            funding.forEach { row ->
                Text(party(row.partyId) + "出资 · " + FinanceMoney.display(row.amountMinor, currency), style = LifeText.body)
                Text(row.basis, style = LifeText.caption, color = colors.inkSecondary)
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
                    (row.amountMinor?.let { FinanceMoney.display(it, currency) } ?: "金额待确认"), style = LifeText.body)
                Text(relation + " · 最终承担 " + party(row.bearerId), style = LifeText.caption, color = colors.inkSecondary)
            }
            if (detail?.allocationsComplete == false) Text("仍有未分配部分，其承担保持未知。", style = LifeText.caption)
            HorizontalDivider(color = colors.line)
            Text("关联流水", style = LifeText.title)
            if (cash.isEmpty()) Text("暂无本人流水", style = LifeText.body)
            cash.sortedByDescending { it.second.occurredAt }.forEach { (link, entry) ->
                val timing = v?.entryTimingRows.orEmpty().singleOrNull { it.entryId == entry.id && it.voidedAt == null }
                val time = if (timing?.cashPrecision == FinanceTimePrecision.UNKNOWN) "收付时间未知"
                    else FinanceTime.input(entry.occurredAt)
                TextButton({ onOpenCash(entry.id) }) {
                    Column {
                        Text((accounts[entry.accountId]?.name ?: "账户") + " · " + link.role.label + " · " +
                            (if (entry.direction == FinanceDirection.INFLOW) "+" else "−") + FinanceMoney.display(link.allocatedMinor, currency))
                        Text(time + " · " + entry.description, style = LifeText.caption)
                    }
                }
            }
            HorizontalDivider(color = colors.line)
            Text("应收应付与结算", style = LifeText.title)
            if (obligations.isEmpty()) Text("暂无已确认的应收应付", style = LifeText.body)
            obligations.forEach { row ->
                val debt = row.obligation
                Text(party(debt.debtorId) + "应付给" + party(debt.creditorId) + " · " +
                    FinanceMoney.display(debt.amountMinor, debt.currencyCode), style = LifeText.body)
                Text("真实结算 " + FinanceMoney.display(row.settledMinor, debt.currencyCode) + " · 尚余 " +
                    FinanceMoney.display(row.openMinor, debt.currencyCode), style = LifeText.caption)
                v?.resolutionRows.orEmpty().filter { it.obligationId == debt.id && it.voidedAt == null }.forEach { resolution ->
                    Text((if (resolution.kind == FinanceResolutionKind.HISTORICAL_SETTLED) "历史结清声明" else "已放弃或免除") + " · " +
                        FinanceMoney.display(resolution.amountMinor, debt.currencyCode) + " · " + resolution.note, style = LifeText.caption)
                }
                if (row.openMinor == 0L) Text(if (row.cashEvidenceComplete) "已结清，真实现金证据完整"
                    else "工作已结束，历史现金证据仍不完整", style = LifeText.caption, color = colors.inkSecondary)
            }
            val entryIds = cash.map { it.second.id }.toSet()
            val canonicalIds = entryIds + snapshot.transfers.filter {
                it.outflowEntryId in entryIds || it.inflowEntryId in entryIds
            }.map { it.id }
            val proposalIds = snapshot.v4?.proposals.orEmpty().filter { it.canonicalId in canonicalIds }.map { it.id }.toSet()
            val lineIds = v?.purchaseAllocationRows.orEmpty().filter { it.eventId == event.id && it.voidedAt == null }.map { it.lineId }.toSet()
            val purchaseIds = v?.purchaseLineRows.orEmpty().filter { it.id in lineIds }.map { it.purchaseId }.toSet()
            val attachments = v?.mediaLinkRows.orEmpty().filter { it.voidedAt == null &&
                (it.eventId == event.id || it.proposalId in proposalIds || it.purchaseId in purchaseIds) }
            HorizontalDivider(color = colors.line)
            Text("凭据", style = LifeText.title)
            if (attachments.isEmpty()) Text("暂无凭据", style = LifeText.body)
            attachments.forEach { Text(it.label.ifBlank { "凭据附件" }, style = LifeText.body) }
        }
    }
}

@Composable
private fun EconomicMetric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(label, style = LifeText.caption, color = LocalLifeOsColors.current.inkSecondary)
        Text(value, style = LifeText.body, color = LocalLifeOsColors.current.ink)
    }
}
