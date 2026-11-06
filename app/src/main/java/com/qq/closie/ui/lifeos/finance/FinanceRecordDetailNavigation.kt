package com.qq.closie.ui.lifeos.finance

import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import com.qq.closie.life.finance.*
import com.qq.closie.ui.lifeos.components.LifeSurfaceOrigin

/** Canonical read projections; only IDs are retained by navigation, never another copy of the ledger. */
internal fun financeEventEvidence(snapshot: FinanceSnapshot, eventId: String): List<FinanceMediaLinkEntity> {
    val v=snapshot.v16
    val entries=snapshot.v4?.activeLinks.orEmpty().filter {it.eventId==eventId}.map {it.entryId}.toSet()
    val canonicalIds=entries+snapshot.transfers.filter {it.outflowEntryId in entries || it.inflowEntryId in entries}.map {it.id}
    val proposals=snapshot.v4?.proposals.orEmpty().filter {it.canonicalId in canonicalIds}.map {it.id}.toSet()
    val lineIds=v?.purchaseAllocationRows.orEmpty().filter {it.eventId==eventId && it.voidedAt==null}.map {it.lineId}.toSet()
    val purchases=v?.purchaseLineRows.orEmpty().filter {it.id in lineIds}.map {it.purchaseId}.toSet()
    return v?.mediaLinkRows.orEmpty().filter {it.voidedAt==null &&
        (it.eventId==eventId || it.proposalId in proposals || it.purchaseId in purchases)}
}

internal fun financeRecordEvidence(snapshot: FinanceSnapshot,row: FinanceLedgerRow): List<FinanceMediaLinkEntity> {
    val entries=setOfNotNull(row.entry.id,row.transfer?.outflowEntryId,row.transfer?.inflowEntryId)
    val canonicalIds=entries+listOfNotNull(row.transfer?.id)
    val proposals=snapshot.v4?.proposals.orEmpty().filter {it.canonicalId in canonicalIds}.map {it.id}.toSet()
    val events=snapshot.v4?.activeLinks.orEmpty().filter {it.entryId in entries}.map {it.eventId}.distinct()
    return (events.flatMap {financeEventEvidence(snapshot,it)} + snapshot.v16?.mediaLinkRows.orEmpty()
        .filter {it.voidedAt==null && it.proposalId in proposals}).distinctBy {it.id}
}

@Composable
internal fun FinanceReadOnlyEvidence(state: FinanceUiState,vm: FinanceViewModel,ids: List<String>,onClose: ()->Unit) {
    val links=state.snapshot.v16?.mediaLinkRows.orEmpty().filter {it.voidedAt==null && it.id in ids}
    val target=links.firstOrNull()?.let {FinanceMediaTarget(it.eventId,it.purchaseId,it.proposalId)}
    if(target==null) {
        FinanceReceipt("凭据与附件",onClose=onClose) {
            com.qq.closie.life.ui.theme.BundledText("原凭据关联已变化或不可用，账务记录保留。")
        }
    } else {
        val epoch=state.snapshot.v16?.controlRows?.singleOrNull()?.restoreEpoch ?: 0L
        FinanceAttachments(state.snapshot,target,state.busy,state.error,readOnly=true,initialOpen=true,
            providedLinks=links,onClose=onClose) {block->vm.mediaAction(epoch,block)}
    }
}

/** Viewing an event and its evidence cannot create an economic draft. Only the Edit action does. */
@Composable
internal fun FinanceEconomicDetailNavigation(state: FinanceUiState,vm: FinanceViewModel,eventId: String,
    onEdit: ()->Unit,onOpenCash: (String)->Unit,onClose: ()->Unit) {
    var evidence by rememberSaveable(eventId) {mutableStateOf<List<String>?>(null)}
    FinanceEconomicDetails(state.snapshot,eventId,onEdit,onOpenCash,
        onOpenEvidence={links->evidence=links.map {it.id}},onClose=onClose)
    evidence?.let {ids->FinanceReadOnlyEvidence(state,vm,ids){evidence=null}}
}

/** Both formal-record hosts share the same read-only drill-down and explicit editing boundary. */
@Composable
internal fun FinanceRecordDetailNavigation(state: FinanceUiState,vm: FinanceViewModel,row: FinanceLedgerRow?,
    origin: LifeSurfaceOrigin?=null,onEdit: (FinanceLedgerRow)->Unit,onClose: ()->Unit) {
    var event by rememberSaveable(row?.id) {mutableStateOf<String?>(null)}
    var editingEvent by rememberSaveable(row?.id) {mutableStateOf(false)}
    var cash by rememberSaveable(row?.id) {mutableStateOf<String?>(null)}
    var evidence by rememberSaveable(row?.id) {mutableStateOf<List<String>?>(null)}
    val eventId=event
    if(eventId==null) FinanceRecordDetails(row,state.snapshot,onEdit,origin,
        voidAction={FinanceRecordVoidAction(it,state,vm,onClose)},
        onOpenEvent={event=it},onOpenEvidence={links->evidence=links.map {it.id}},onClose=onClose)
    else if(editingEvent) FinanceEconomicEditor(state,vm,eventId){editingEvent=false}
    else FinanceEconomicDetailNavigation(state,vm,eventId,onEdit={editingEvent=true},onOpenCash={id->
        if(row!=null && (id==row.id || id==row.entry.id || id==row.transfer?.inflowEntryId)) {
            cash=null;event=null
        } else cash=id
    },onClose={event=null})
    evidence?.let {ids->FinanceReadOnlyEvidence(state,vm,ids){evidence=null}}
    cash?.let {id->FinanceCanonicalRecordSheet(state,vm,id){cash=null}}
}
