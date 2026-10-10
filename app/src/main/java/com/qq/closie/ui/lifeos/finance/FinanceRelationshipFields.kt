package com.qq.closie.ui.lifeos.finance

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

data class FinanceRelationshipDraft(
    val nature: FinanceNature = FinanceNature.PERSONAL, val role: FinanceFlowRole = FinanceFlowRole.PAYMENT,
    val eventId: String? = null, val share: String = "", val expected: String = "",
    val excluded: Boolean = false, val excludedBudget: Boolean = false
) {
    fun resolve(currency: String): FinanceEventInput = FinanceEventInput(
        nature = nature, role = role, existingEventId = eventId,
        personalShareMinor = share.takeIf { it.isNotBlank() }?.let { FinanceMoney.parse(it, currency, allowNegative = true).also { n -> require(n >= 0) } },
        expectedMinor = expected.takeIf { it.isNotBlank() }?.let { FinanceMoney.parse(it, currency) },
        expectedRole = if (nature == FinanceNature.REIMBURSABLE) FinanceFlowRole.REIMBURSEMENT else FinanceFlowRole.SETTLEMENT,
        statPolicy = if (excluded) FinanceStatPolicy.EXCLUDE else FinanceStatPolicy.INCLUDE,
        budgetPolicy = if (excludedBudget) FinanceBudgetPolicy.EXCLUDE else FinanceBudgetPolicy.INCLUDE
    )
    companion object {
        fun from(snapshot: FinanceSnapshot, row: FinanceLedgerRow?): FinanceRelationshipDraft {
            val link = snapshot.v4?.links?.singleOrNull { it.entryId == row?.entry?.id }
            val event = snapshot.v4?.events?.find { it.id == link?.eventId }
            return FinanceRelationshipDraft(event?.nature ?: FinanceNature.PERSONAL,
                link?.role ?: if (row?.entry?.direction == FinanceDirection.INFLOW) FinanceFlowRole.OTHER else FinanceFlowRole.PAYMENT,
                // Primary payment edits update the event; follow-up flows only reference it.
                event?.id?.takeIf { link?.role !in setOf(FinanceFlowRole.PAYMENT, FinanceFlowRole.OTHER) },
                event?.personalShareMinor?.let { FinanceMoney.input(it, event.currencyCode) }.orEmpty(),
                snapshot.v4?.expected?.firstOrNull { it.eventId == event?.id && it.cancelledAt == null }?.amountMinor
                    ?.let { FinanceMoney.input(it, event!!.currencyCode) }.orEmpty(),
                row?.entry?.statPolicy == FinanceStatPolicy.EXCLUDE, row?.entry?.budgetPolicy == FinanceBudgetPolicy.EXCLUDE)
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
    if (draft.nature == FinanceNature.SPLIT) {
        OutlinedTextField(draft.share, { onChange(draft.copy(share = it)) }, Modifier.fillMaxWidth(),
            label = { Text("我承担的总额（未知可留空）") }, enabled = enabled)
    }
    if (draft.nature in setOf(FinanceNature.SPLIT, FinanceNature.PROXY_PURCHASE, FinanceNature.REIMBURSABLE)) {
        OutlinedTextField(draft.expected, { onChange(draft.copy(expected = it)) }, Modifier.fillMaxWidth(),
            label = { Text("预计后续到账（可留空）") }, enabled = enabled)
        Text("预计款项不改变余额；实际到账后再确认关联。", style = LifeText.caption)
    }
    ChoiceFilter("资金用途", draft.role.name, FinanceFlowRole.entries.map { it.name to it.label }) {
        if (enabled) onChange(draft.copy(role = it?.let(FinanceFlowRole::valueOf) ?: FinanceFlowRole.OTHER))
    }
    Text("关联到之前的一笔", style = LifeText.body)
    val events = snapshot.v4?.events.orEmpty().filter { it.voidedAt == null }
    draft.eventId?.let { id ->
        Text(events.find { it.id == id }?.description ?: "原事件暂不可用", style = LifeText.caption)
        TextButton({ onChange(draft.copy(eventId = null)) }, enabled = enabled) { Text("取消选择") }
    }
    OutlinedTextField(search, { search = it }, Modifier.fillMaxWidth(), label = { Text("搜事由、日期或付款金额") }, enabled = enabled)
    if (search.isNotBlank()) {
        val amounts = snapshot.v4?.links.orEmpty().groupBy { it.eventId }.mapValues { (_, links) ->
            links.filter { it.role == FinanceFlowRole.PAYMENT }.sumOf { it.allocatedMinor }
        }
        events.filter { event ->
            (event.description + " " + FinanceTime.input(event.occurredAt) + " " +
                FinanceMoney.input(amounts[event.id] ?: 0, event.currencyCode)).contains(search, true)
        }.take(12).forEach { event ->
            TextButton({ onChange(draft.copy(eventId = event.id, nature = event.nature)); search = "" }, enabled = enabled) {
                Text(FinanceTime.input(event.occurredAt) + " · " + event.description)
            }
        }
    }
    Row { Checkbox(draft.excluded, { onChange(draft.copy(excluded = it)) }, enabled = enabled); Text("不计入个人收支") }
    Row { Checkbox(draft.excludedBudget, { onChange(draft.copy(excludedBudget = it)) }, enabled = enabled); Text("保留不计入预算标记") }
}
