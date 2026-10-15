package com.qq.closie.ui.lifeos.finance

import com.qq.closie.life.ui.theme.BundledText as Text

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.qq.closie.life.finance.*
import com.qq.closie.ui.lifeos.theme.LifeText

data class FinanceExpectedFlowDraft(val id: String? = null, val role: FinanceFlowRole = FinanceFlowRole.SETTLEMENT,
    val amount: String = "", val note: String = "", val cancelled: Boolean = false) {
    fun resolve(currency: String) = FinanceExpectedFlowInput(id, role,
        (if (cancelled) null else amount.takeIf { it.isNotBlank() }?.let { FinanceMoney.parse(it, currency) }), note, cancelled)
}

data class FinanceRelationshipDraft(
    val nature: FinanceNature = FinanceNature.PERSONAL, val role: FinanceFlowRole = FinanceFlowRole.PAYMENT,
    val eventId: String? = null, val share: String = "",
    val excluded: Boolean = false, val excludedBudget: Boolean = false,
    val expectedFlows: List<FinanceExpectedFlowDraft> = emptyList(), val expectedEdited: Boolean = false,
    val eventUpdatedAt: Long? = null
) {
    fun resolve(currency: String): FinanceEventInput = FinanceEventInput(
        nature = nature, role = role, existingEventId = eventId,
        personalShareMinor = share.takeIf { it.isNotBlank() }?.let { FinanceMoney.parse(it, currency, allowNegative = true).also { n -> require(n >= 0) } },
        expectedFlows = expectedFlows.takeIf { expectedEdited }?.map { it.resolve(currency) },
        statPolicy = if (excluded) FinanceStatPolicy.EXCLUDE else FinanceStatPolicy.INCLUDE,
        budgetPolicy = if (excludedBudget) FinanceBudgetPolicy.EXCLUDE else FinanceBudgetPolicy.INCLUDE,
        expectedEventUpdatedAt = eventUpdatedAt
    )
    companion object {
        val Saver = androidx.compose.runtime.saveable.Saver<FinanceRelationshipDraft, String>(
            save = { com.google.gson.Gson().toJson(it) },
            restore = { runCatching { com.google.gson.Gson().fromJson(it, FinanceRelationshipDraft::class.java) }.getOrNull() })
        fun fromProposal(proposal: FinanceProposalEntity): FinanceRelationshipDraft {
            val role = if (proposal.kind != FinanceProposalKind.INCOME || proposal.role != FinanceFlowRole.OTHER) proposal.role
            else when (proposal.nature) {
                FinanceNature.REIMBURSABLE -> FinanceFlowRole.REIMBURSEMENT
                FinanceNature.PROXY_PURCHASE, FinanceNature.SPLIT -> FinanceFlowRole.SETTLEMENT
                else -> proposal.role
            }
            return FinanceRelationshipDraft(nature = proposal.nature, role = role)
        }
        fun expectations(snapshot: FinanceSnapshot, event: FinanceEventEntity?) = snapshot.v4?.expected.orEmpty()
            .filter { it.eventId == event?.id && it.cancelledAt == null }.map {
                FinanceExpectedFlowDraft(it.id, it.role, it.amountMinor?.let { amount ->
                    FinanceMoney.input(amount, event!!.currencyCode) }.orEmpty(), it.note)
            }
        fun from(snapshot: FinanceSnapshot, row: FinanceLedgerRow?): FinanceRelationshipDraft {
            val link = snapshot.v4?.activeLinks?.singleOrNull { it.entryId == row?.entry?.id }
            val event = snapshot.v4?.events?.find { it.id == link?.eventId }
            return FinanceRelationshipDraft(
                nature = event?.nature ?: FinanceNature.PERSONAL,
                role = link?.role ?: if (row?.entry?.direction == FinanceDirection.INFLOW) FinanceFlowRole.OTHER else FinanceFlowRole.PAYMENT,
                // Primary payment edits update the event; follow-up flows only reference it.
                eventId = event?.id?.takeIf { link?.role !in setOf(FinanceFlowRole.PAYMENT, FinanceFlowRole.OTHER) },
                share = event?.personalShareMinor?.let { FinanceMoney.input(it, event.currencyCode) }.orEmpty(),
                excluded = row?.entry?.statPolicy == FinanceStatPolicy.EXCLUDE,
                excludedBudget = row?.entry?.budgetPolicy == FinanceBudgetPolicy.EXCLUDE,
                expectedFlows = expectations(snapshot, event), eventUpdatedAt = event?.updatedAt)
        }
    }
}

@Composable
internal fun FinanceRelationshipFields(snapshot: FinanceSnapshot, draft: FinanceRelationshipDraft,
    enabled: Boolean, onChange: (FinanceRelationshipDraft) -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var search by rememberSaveable { mutableStateOf("") }
    TextButton({ expanded = !expanded }) { Text("这笔是什么性质？ · ${draft.nature.label}") }
    if (!expanded) return
    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items(FinanceNature.entries) { nature ->
            FilterChip(draft.nature == nature, { onChange(draft.copy(nature = nature)) }, enabled = enabled,
                label = { Text(nature.label) })
        }
    }
    if (draft.nature in setOf(FinanceNature.SPLIT, FinanceNature.REIMBURSABLE)) {
        OutlinedTextField(draft.share, { onChange(draft.copy(share = it)) }, Modifier.fillMaxWidth(),
            label = { Text("我承担的总额（未知可留空）") }, enabled = enabled, textStyle = com.qq.closie.life.ui.theme.LifeType.inputFor(draft.share))
    }
    Text("预计后续款项", style = LifeText.body)
    draft.expectedFlows.forEachIndexed { index, flow ->
        fun update(value: FinanceExpectedFlowDraft) = onChange(draft.copy(
            expectedFlows = draft.expectedFlows.mapIndexed { i, old -> if (i == index) value else old }, expectedEdited = true))
        if (!flow.cancelled) {
            EnumChoice("用途", flow.role, FinanceFlowRole.entries.map { it to it.label }, enabled) { update(flow.copy(role = it)) }
            OutlinedTextField(flow.amount, { update(flow.copy(amount = it)) }, Modifier.fillMaxWidth(),
                label = { Text("预计金额（可留空）") }, enabled = enabled, textStyle = com.qq.closie.life.ui.theme.LifeType.inputFor(flow.amount))
            OutlinedTextField(flow.note, { update(flow.copy(note = it)) }, Modifier.fillMaxWidth(),
                label = { Text("备注") }, enabled = enabled, textStyle = com.qq.closie.life.ui.theme.LifeType.inputFor(flow.note))
            TextButton({ update(flow.copy(cancelled = true)) }, enabled = enabled) { Text("取消此项") }
        } else Text("此项将在保存时取消", style = LifeText.caption)
    }
    TextButton({ onChange(draft.copy(expectedFlows = draft.expectedFlows + FinanceExpectedFlowDraft(
        role = if (draft.nature == FinanceNature.REIMBURSABLE) FinanceFlowRole.REIMBURSEMENT else FinanceFlowRole.SETTLEMENT),
        expectedEdited = true)) }, enabled = enabled) { Text("+ 添加预计款项") }
    Text("预计款项不改变余额；实际到账后再确认关联。", style = LifeText.caption)
    EnumChoice("资金用途", draft.role, FinanceFlowRole.entries.map { it to it.label }, enabled) {
        onChange(draft.copy(role = it))
    }
    Text("关联到之前的一笔", style = LifeText.body)
    val events = snapshot.v4?.events.orEmpty().filter { it.voidedAt == null }
    draft.eventId?.let { id ->
        Text(events.find { it.id == id }?.description ?: "原事件暂不可用", style = LifeText.caption)
        TextButton({ onChange(draft.copy(eventId = null, eventUpdatedAt = null, expectedFlows = emptyList(), expectedEdited = false)) }, enabled = enabled) { Text("取消选择") }
    }
    OutlinedTextField(search, { search = it }, Modifier.fillMaxWidth(), label = { Text("搜事由、日期或付款金额") }, enabled = enabled, textStyle = com.qq.closie.life.ui.theme.LifeType.inputFor(search))
    if (search.isNotBlank()) {
        val amounts = snapshot.v4?.activeLinks.orEmpty().groupBy { it.eventId }.mapValues { (_, links) ->
            links.filter { it.role == FinanceFlowRole.PAYMENT }.sumOf { it.allocatedMinor }
        }
        events.filter { event ->
            (event.description + " " + FinanceTime.input(event.occurredAt) + " " +
                FinanceMoney.input(amounts[event.id] ?: 0, event.currencyCode)).contains(search, true)
        }.take(12).forEach { event ->
            TextButton({ onChange(draft.copy(eventId = event.id, nature = event.nature, eventUpdatedAt = event.updatedAt,
                expectedFlows = FinanceRelationshipDraft.expectations(snapshot, event), expectedEdited = false)); search = "" }, enabled = enabled) {
                Text(FinanceTime.input(event.occurredAt) + " · " + event.description)
            }
        }
    }
    Row { Checkbox(draft.excluded, { onChange(draft.copy(excluded = it)) }, enabled = enabled); Text("不计入个人收支") }
    Row { Checkbox(draft.excludedBudget, { onChange(draft.copy(excludedBudget = it)) }, enabled = enabled); Text("保留不计入预算标记") }
}
