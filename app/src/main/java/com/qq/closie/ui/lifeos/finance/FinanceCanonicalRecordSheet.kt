package com.qq.closie.ui.lifeos.finance

import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable

@Composable
internal fun FinanceCanonicalRecordSheet(state: FinanceUiState, vm: FinanceViewModel, id: String, onClose: () -> Unit) {
    var destination by rememberSaveable(id, stateSaver = FinanceRecordDestination.Saver) {
        mutableStateOf<FinanceRecordDestination>(FinanceRecordDestination.Viewing(id))
    }
    val row = state.allRows.firstOrNull { it.id == id || it.entry.id == id || it.transfer?.inflowEntryId == id }
    val editing = destination as? FinanceRecordDestination.Editing
    if (editing == null) FinanceRecordDetails(row, state.snapshot,
        { destination = FinanceRecordDestination.Editing(it.id, it.entry.revision) }, onClose = onClose)
    else if (row == null || row.entry.revision != editing.revision) FinanceReceipt("记录已变化", onClose = onClose) {
        com.qq.closie.life.ui.theme.BundledText("请关闭后重新打开这条账单。")
    } else FinanceEntrySheet(state, row, vm, onSaved = onClose, onTerminalClose = onClose, onClose = onClose)
}
