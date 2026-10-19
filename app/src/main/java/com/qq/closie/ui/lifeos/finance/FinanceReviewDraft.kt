package com.qq.closie.ui.lifeos.finance

import com.qq.closie.life.finance.*

internal fun FinanceReviewSessionEntity.toEntryDraft(s:FinanceSnapshot):FinanceEntryDraft =FinanceEntryDraft(
    FinanceEditorKind.valueOf(kind.name),amountText,accountId.orEmpty(),targetAccountId.orEmpty(),occurredText,
    description,category,tagsText,occurredAt,subcategory,
    FinanceRelationshipDraft(nature,role,eventId,shareText,excluded,excludedBudget,
        s.v16?.reviewExpectedRows.orEmpty().filter {it.proposalId==proposalId}.sortedBy {it.position}.map {
            FinanceExpectedFlowDraft(it.expectedId,it.role,it.amountText,it.note,it.cancelled)
        },expectedEdited,eventUpdatedAt),categoryId,costReductionText)

internal fun FinanceEntryDraft.reviewInput(proposal:FinanceProposalEntity,epoch:Long)=FinanceReviewSessionEntity(
    proposalId=proposal.id,baseRevision=proposal.revision,epoch=epoch,amountText=amount,accountId=accountId.ifBlank {null},
    category=category,subcategory=subcategory,description=description,state=FinanceReviewState.EDITING,
    kind=FinanceDraftKind.valueOf(kind.name),targetAccountId=targetAccountId.ifBlank {null},occurredAt=preservedOccurredAt ?: proposal.occurredAt,
    occurredText=occurredAt,categoryId=categoryId,tagsText=tags,nature=relationship.nature,role=relationship.role,
    eventId=relationship.eventId,eventUpdatedAt=relationship.eventUpdatedAt,shareText=relationship.share,
    excluded=relationship.excluded,excludedBudget=relationship.excludedBudget,expectedEdited=relationship.expectedEdited,
    costReductionText=costReductionText,createdAt=0,updatedAt=0)

internal fun FinanceEntryDraft.reviewExpected(proposalId:String)=relationship.expectedFlows.mapIndexed {index,row->
    FinanceReviewExpectedEntity(proposalId,index,row.id,row.role,row.amount,row.note,row.cancelled)
}
