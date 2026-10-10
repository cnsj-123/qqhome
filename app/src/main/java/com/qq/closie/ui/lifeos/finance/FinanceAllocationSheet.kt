package com.qq.closie.ui.lifeos.finance

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.qq.closie.life.finance.*

private data class AllocationDraft(val eventId: String, val role: FinanceFlowRole, val amount: String)

@Composable
internal fun FinanceAllocationSheet(state: FinanceUiState, row: FinanceLedgerRow, vm: FinanceViewModel, close: () -> Unit) {
    val parts = remember(row.entry.id) { state.snapshot.v4?.activeLinks.orEmpty().filter { it.entryId == row.entry.id }
        .map { AllocationDraft(it.eventId, it.role, FinanceMoney.input(it.allocatedMinor, row.currencyCode)) }.toMutableStateList() }
    var query by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val events = state.snapshot.v4?.events.orEmpty().filter { it.voidedAt == null && it.currencyCode == row.currencyCode && it.relatedTransferId == null }
    FinanceReceipt("分配到多个事件", state.busy, error ?: state.error, close) {
        Text("真实流水 ${FinanceMoney.display(row.entry.amountMinor, row.currencyCode)} 保持一笔，分配总额必须与它相等。")
        parts.forEachIndexed { index, part ->
            Text(state.snapshot.v4?.events?.find { it.id == part.eventId }?.description ?: "事件已不可用")
            EnumChoice("用途", part.role, FinanceFlowRole.entries.map { it to it.label }, !state.busy) {
                parts[index] = part.copy(role = it)
            }
            OutlinedTextField(part.amount, { parts[index] = part.copy(amount = it) }, label = { Text("分配金额") }, enabled = !state.busy)
            TextButton(onClick = { parts.removeAt(index) }, enabled = !state.busy) { Text("移除分配") }
        }
        OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), label = { Text("按事由或日期查找事件") }, enabled = !state.busy)
        if (query.isNotBlank()) events.filter { event ->
            parts.none { it.eventId == event.id } && (event.description + " " + FinanceTime.input(event.occurredAt)).contains(query, true)
        }.take(12).forEach { event ->
            TextButton(onClick = {
                parts += AllocationDraft(event.id, if (row.entry.direction == FinanceDirection.INFLOW) FinanceFlowRole.SETTLEMENT else FinanceFlowRole.PAYMENT, "")
                query = ""
            }, enabled = !state.busy) { Text("${FinanceTime.input(event.occurredAt)} · ${event.description}") }
        }
        Button(onClick = {
            try {
                val values = parts.map { FinanceAllocation(it.eventId, it.role, FinanceMoney.parse(it.amount, row.currencyCode)) }
                vm.allocate(row, values, close)
            } catch (e: Exception) { error = e.message }
        }, enabled = !state.busy && parts.isNotEmpty()) { Text("确认分配") }
    }
}
