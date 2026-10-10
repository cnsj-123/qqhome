package com.qq.closie.ui.lifeos.finance

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.qq.closie.life.finance.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

internal data class AutomationUiState(
    val data: FinanceAutomationSnapshot = FinanceAutomationSnapshot(emptyList(), emptyList(), emptyList()),
    val finance: FinanceSnapshot = FinanceSnapshot(), val loaded: Boolean = false,
    val busy: Boolean = false, val error: String? = null
)
internal class FinanceAutomationViewModel(private val repository: FinanceAutomationRepository, finance: FinanceRepository) : ViewModel() {
    private val mutable = MutableStateFlow(AutomationUiState())
    val state = mutable.asStateFlow()
    init {
        viewModelScope.launch {
            combine(repository.observe(), finance.observeSnapshot()) { data, snapshot -> data to snapshot }
                .catch { e -> mutable.update { it.copy(error = e.message ?: "读取未完成") } }
                .collect { (data, snapshot) -> mutable.update { it.copy(data = data, finance = snapshot, loaded = true) } }
        }
    }
    fun action(block: suspend () -> Unit) {
        if (mutable.value.busy) return
        mutable.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { mutable.update { it.copy(error = e.message ?: "保存失败，请重试") } }
            finally { mutable.update { it.copy(busy = false) } }
        }
    }
    fun confirm(proposal: FinanceProposalEntity, draft: FinanceEntryDraft, done: () -> Unit) = action {
        repository.confirm(proposal.id, proposal.revision, FinanceProposalConfirmation(
            FinanceProposalKind.valueOf(draft.kind.name), draft.accountId, draft.targetAccountId.ifBlank { null },
            FinanceMoney.parse(draft.amount), draft.description, FinanceTime.resolve(draft.occurredAt, draft.preservedOccurredAt),
            draft.category, draft.subcategory, draft.tags.split('，', ',').map(String::trim).filter(String::isNotBlank),
            draft.relationship.resolve("CNY")))
        done()
    }
    fun dismiss(proposal: FinanceProposalEntity, status: FinanceProposalStatus, done: () -> Unit) = action {
        repository.dismiss(proposal.id, proposal.revision, status); done()
    }
    fun saveRule(set: FinanceRuleSet, done: () -> Unit) = action { repository.saveRule(set); done() }
    fun deleteRule(id: String, done: () -> Unit) = action { repository.deleteRule(id); done() }
}
