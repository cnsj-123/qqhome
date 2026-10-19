package com.qq.closie.ui.lifeos.finance

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.qq.closie.life.finance.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class AutomationUiState(
    val data: FinanceAutomationSnapshot = FinanceAutomationSnapshot(emptyList(), emptyList(), emptyList()),
    val finance: FinanceSnapshot = FinanceSnapshot(), val loaded: Boolean = false,
    val busy: Boolean = false, val error: String? = null
)
internal class FinanceAutomationViewModel(private val repository: FinanceAutomationRepository, private val finance: FinanceRepository) : ViewModel() {
    private val mutable = MutableStateFlow(AutomationUiState())
    val state = mutable.asStateFlow()
    private val editorVersions=FinanceEditorVersions()
    private val sessions get()=editorVersions.mutex
    private suspend fun persistLocked(editor:String,initialRevision:Long?,proposal:FinanceProposalEntity,draft:FinanceEntryDraft,epoch:Long,later:Boolean=false):Long {
        return editorVersions.persistLocked(editor,initialRevision) {prior->
            finance.reviews.save(FinanceCommandToken(java.util.UUID.randomUUID().toString(),epoch),draft.reviewInput(proposal,epoch),
                prior,draft.reviewExpected(proposal.id),later)
        }
    }
    suspend fun persistReview(editor:String,initialRevision:Long?,proposal:FinanceProposalEntity,draft:FinanceEntryDraft,epoch:Long) {
        try {sessions.withLock {if(!editorVersions.isFinished(editor)) persistLocked(editor,initialRevision,proposal,draft,epoch)}}
        catch(e:CancellationException){throw e}
        catch(e:Exception){mutable.update {it.copy(error=e.message ?: "复核草稿保存未完成")}}
    }
    fun laterReview(editor:String,initialRevision:Long?,proposal:FinanceProposalEntity,draft:FinanceEntryDraft,epoch:Long,done:()->Unit)=action {
        sessions.withLock {persistLocked(editor,initialRevision,proposal,draft,epoch,true)};done()
    }
    fun flushReviewOnStop(editor:String,initialRevision:Long?,proposal:FinanceProposalEntity,draft:FinanceEntryDraft,epoch:Long) {
        viewModelScope.launch(start=CoroutineStart.UNDISPATCHED) {
            try {withContext(NonCancellable) {sessions.withLock {
                val current=finance.snapshot()
                val candidate=current.v4?.proposals.orEmpty().singleOrNull {it.id==proposal.id}
                if((current.v16?.controlRows?.singleOrNull()?.restoreEpoch ?: 0L)==epoch && candidate!=null && candidate.revision==proposal.revision &&
                    candidate.status in setOf(FinanceProposalStatus.ACTIVE,FinanceProposalStatus.SNOOZED) &&
                    current.v16?.reviewSessionRows.orEmpty().none {it.proposalId==proposal.id && it.state==FinanceReviewState.COMPLETED}) {
                    persistLocked(editor,initialRevision,proposal,draft,epoch,true)
                }
            }}}
            catch(e:CancellationException){throw e}
            catch(e:Exception){mutable.update {it.copy(error=e.message ?: "离开前复核草稿保存未完成")}}
        }
    }
    fun confirmSession(editor:String,initialRevision:Long?,proposal:FinanceProposalEntity,draft:FinanceEntryDraft,epoch:Long,
        reviewed:Set<String>,done:()->Unit)=action {
        sessions.withLock {
            val revision=persistLocked(editor,initialRevision,proposal,draft,epoch)
            editorVersions.confirmLocked(editor,revision,finish=true) {
                finance.reviews.confirm(FinanceCommandToken(java.util.UUID.randomUUID().toString(),epoch),proposal.id,revision,
                    FinanceTime.resolve(draft.occurredAt,draft.preservedOccurredAt),reviewed)
            }
        };done()
    }
    fun classifyReceipt(editor:String,initialRevision:Long?,proposal:FinanceProposalEntity,draft:FinanceEntryDraft,epoch:Long,
        evidence:FinanceEvidenceEntity,identity:String?,instrument:String?)=action {
        sessions.withLock {
            persistLocked(editor,initialRevision,proposal,draft,epoch)
            finance.provenance.classify(FinanceCommandToken(java.util.UUID.randomUUID().toString(),epoch),proposal.id,proposal.revision,
                evidence.id,evidence.revision,identity,instrument,adoptBindingSuggestion=instrument!=null)
        }
    }
    fun linkExisting(editor:String,initialRevision:Long?,proposal:FinanceProposalEntity,draft:FinanceEntryDraft,epoch:Long,
        existing:FinanceLedgerRow,done:()->Unit)=action {
        sessions.withLock {
            val revision=persistLocked(editor,initialRevision,proposal,draft,epoch)
            editorVersions.confirmLocked(editor,revision,finish=true) {
                val token=FinanceCommandToken(java.util.UUID.randomUUID().toString(),epoch)
                if(existing.transfer!=null) finance.reviews.linkExistingTransfer(token,proposal.id,revision,existing.transfer.id,existing.transfer.updatedAt)
                else finance.reviews.linkExistingEntry(token,proposal.id,revision,existing.entry.id,existing.entry.revision)
            }
        };done()
    }
    init {
        viewModelScope.launch {
            repository.observeReviewState()
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
    fun mediaAction(epoch:Long,block:suspend (FinanceMediaRepository,FinanceCommandToken)->Unit)=action {
        block(finance.media,FinanceCommandToken(java.util.UUID.randomUUID().toString(),epoch))
    }
    fun confirm(proposal: FinanceProposalEntity, draft: FinanceEntryDraft, reviewedDuplicateIds: Set<String> = emptySet(), done: () -> Unit) = action {
        repository.confirm(proposal.id, proposal.revision, draft.toProposalConfirmation().copy(reviewedDuplicateIds = reviewedDuplicateIds))
        done()
    }
    fun confirmReceipt(receipt: FinanceReviewReceiptModel, done: () -> Unit) = action {
        repository.confirm(receipt.proposalId, receipt.revision, requireNotNull(receipt.confirmation) { "请先完善小票" })
        done()
    }
    fun createCategory(parentId: String, name: String, onCreated: (String) -> Unit) = action {
        val created = finance.createCategory(name, parentId)
        mutable.update { it.copy(finance = finance.snapshot()) }
        onCreated(created.id)
    }
    fun dismiss(proposal: FinanceProposalEntity, status: FinanceProposalStatus, done: () -> Unit) = action {
        repository.dismiss(proposal.id, proposal.revision, status); done()
    }
    fun saveRule(set: FinanceRuleSet, done: () -> Unit) = action { repository.saveRule(set); done() }
    fun deleteRule(id: String, done: () -> Unit) = action { repository.deleteRule(id); done() }
}
