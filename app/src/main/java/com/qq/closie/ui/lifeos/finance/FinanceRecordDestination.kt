package com.qq.closie.ui.lifeos.finance

import androidx.compose.runtime.saveable.Saver

/** Candidate IDs never enter this route; only canonical ledger record IDs are accepted. */
internal sealed interface FinanceRecordDestination {
    data object Closed : FinanceRecordDestination
    data class Viewing(val id: String) : FinanceRecordDestination
    data class Editing(val id: String, val revision: Long) : FinanceRecordDestination
    data class Creating(val draftId: String) : FinanceRecordDestination

    companion object {
        val Saver = Saver<FinanceRecordDestination, List<String>>(
            save = { when (it) {
                Closed -> listOf("closed")
                is Viewing -> listOf("view", it.id)
                is Editing -> listOf("edit", it.id, it.revision.toString())
                is Creating -> listOf("create", it.draftId)
            } },
            restore = { decode(it) }
        )
        fun decode(parts: List<String>): FinanceRecordDestination = when {
            parts.size == 2 && parts[1].isNotBlank() && parts[0] == "view" -> Viewing(parts[1])
            parts.size == 3 && parts[1].isNotBlank() && parts[0] == "edit" &&
                parts[2].toLongOrNull()?.let { it >= 0 } == true -> Editing(parts[1], parts[2].toLong())
            parts.size == 2 && parts[1].isNotBlank() && parts[0] == "create" -> Creating(parts[1])
            else -> Closed
        }
    }
}
