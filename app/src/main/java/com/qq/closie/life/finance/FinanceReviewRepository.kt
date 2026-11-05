package com.qq.closie.life.finance

import com.qq.closie.life.data.database.LifeDatabase

/** Persisted editing state is separate from a candidate and from Canonical money. */
class FinanceReviewRepository(private val db:LifeDatabase,finance:FinanceRepository,
    private val automation:FinanceAutomationRepository,private val clock:()->Long=System::currentTimeMillis) {
    private val commands=FinanceCommandRunner(db,finance,clock)
    private val dao get()=db.financeV16Dao()
    private val provenance=finance.provenance
    private suspend fun validateAccount(accountId: String, proposal: FinanceProposalEntity, occurredAt: Long): String {
        val account = requireNotNull(db.financeDao().account(accountId)) { "请选择账户以确认币种" }
        val currency = FinanceEvidenceOrigin.permittedCurrency(proposal, readFinanceSnapshot(db, true).v16)
        require(account.archivedAt == null && (currency == null || account.currencyCode == currency)) {
            currency?.let { "请选择 $it 账户" } ?: "请选择有效账户"
        }
        provenance.validateAccountSuggestion(proposal.id, accountId, occurredAt)
        return account.currencyCode
    }
    private suspend fun linkingSession(proposalId:String,revision:Long,epoch:Long):Pair<FinanceReviewSessionEntity,FinanceProposalEntity> {
        val row=requireNotNull(dao.reviewSessionRows().singleOrNull {it.proposalId==proposalId && it.voidedAt==null})
        val p=requireNotNull(db.financeIntakeDao().proposal(proposalId))
        require(row.revision==revision && row.epoch==epoch && row.state!=FinanceReviewState.COMPLETED &&
            row.baseRevision==p.revision && p.status in setOf(FinanceProposalStatus.ACTIVE,FinanceProposalStatus.SNOOZED)) {"复核已改变，请重新打开"}
        return row to p
    }
    private suspend fun finishLink(row:FinanceReviewSessionEntity,p:FinanceProposalEntity,canonical:String):String {
        val snapshot=readFinanceSnapshot(db,true)
        require(FinanceEvidenceCorrelation.blocking(p,snapshot.v4!!.proposals,snapshot.v16).none {
            it.status==FinanceProposalStatus.CONFIRMED && it.canonicalId!=canonical
        }) {"相同付款编号已关联另一笔正式记录，请先核对来源身份和重复账"}
        db.financeIntakeDao().updateProposal(p.copy(status=FinanceProposalStatus.CONFIRMED,canonicalId=canonical,revision=Math.addExact(p.revision,1L)))
        dao.putReviewSession(listOf(row.copy(state=FinanceReviewState.COMPLETED,updatedAt=clock(),revision=Math.addExact(row.revision,1L))))
        return canonical
    }
    suspend fun linkExistingEntry(token:FinanceCommandToken,proposalId:String,sessionRevision:Long,entryId:String,entryRevision:Long):String=
        commands.run(token,"REVIEW_LINK_ENTRY",listOf(proposalId,sessionRevision,entryId,entryRevision)) {
            val (row,p)=linkingSession(proposalId,sessionRevision,token.restoreEpoch)
            val e=requireNotNull(db.financeDao().entry(entryId))
            require(e.revision==entryRevision && e.voidedAt==null && db.financeDao().transferForEntry(entryId)==null)
            val currency = validateAccount(e.accountId, p, e.occurredAt)
            require(row.kind!=FinanceDraftKind.TRANSFER && row.accountId==e.accountId && FinanceCalculator.evaluate(row.amountText,currency)==e.amountMinor &&
                (row.kind==FinanceDraftKind.INCOME)==(e.direction==FinanceDirection.INFLOW)) {"已有流水的账户、方向或金额不符"}
            if(FinanceEvidenceOrigin.requiresManualAccount(p)) {
                require(p.kind!=FinanceProposalKind.TRANSFER)
                require(p.role!=FinanceFlowRole.REFUND || row.kind==FinanceDraftKind.INCOME && row.role==FinanceFlowRole.REFUND && row.eventId!=null) {
                    "退款须关联原事项的真实退款到账"
                }
            }
            if(row.role in setOf(FinanceFlowRole.REFUND,FinanceFlowRole.REBATE,FinanceFlowRole.CASHBACK)) {
                require(row.eventId!=null && db.financeIntakeDao().linksForEntry(entryId).any {it.eventId==row.eventId && it.role==row.role && it.voidedAt==null}) {
                    "已有到账尚未关联此事项和调整性质，请先在事项中核对实际到账"
                }
            }
            val typed=dao.economicRows().any {it.eventId==row.eventId && it.voidedAt==null}
            if(typed && row.role in setOf(FinanceFlowRole.REFUND,FinanceFlowRole.REBATE,FinanceFlowRole.CASHBACK)) {
                val links=db.financeIntakeDao().linksForEntry(entryId).filter {it.eventId==row.eventId && it.role==row.role}.map {it.id}.toSet()
                require(dao.costAdjustmentRows().any {it.entryLinkId in links && it.voidedAt==null}) {"已有到账尚无此事项的成本归属，请先在事项中关联实际调整"}
            }
            finishLink(row,p,entryId)
        }
    suspend fun linkExistingTransfer(token:FinanceCommandToken,proposalId:String,sessionRevision:Long,transferId:String,expectedUpdatedAt:Long):String=
        commands.run(token,"REVIEW_LINK_TRANSFER",listOf(proposalId,sessionRevision,transferId,expectedUpdatedAt)) {
            val (row,p)=linkingSession(proposalId,sessionRevision,token.restoreEpoch)
            require(!FinanceEvidenceOrigin.requiresManualAccount(p) || p.role!=FinanceFlowRole.REFUND) {"退款不能关联为转账"}
            val t=requireNotNull(db.financeDao().transfers().singleOrNull {it.id==transferId && it.voidedAt==null})
            val out=requireNotNull(db.financeDao().entry(t.outflowEntryId));val incoming=requireNotNull(db.financeDao().entry(t.inflowEntryId))
            val currency = validateAccount(out.accountId, p, out.occurredAt)
            require(currency == validateAccount(incoming.accountId, p, incoming.occurredAt))
            require(t.updatedAt==expectedUpdatedAt && row.kind==FinanceDraftKind.TRANSFER && row.accountId==out.accountId &&
                row.targetAccountId==incoming.accountId && FinanceCalculator.evaluate(row.amountText,currency)==out.amountMinor)
            finishLink(row,p,transferId)
        }
    suspend fun save(token:FinanceCommandToken,input:FinanceReviewSessionEntity,expectedRevision:Long?,
        expected:List<FinanceReviewExpectedEntity>,later:Boolean=false):String =
        commands.run(token,"REVIEW_SAVE",listOf(input,expectedRevision,expected,later)) {
            val proposal=requireNotNull(db.financeIntakeDao().proposal(input.proposalId))
            require(proposal.status in setOf(FinanceProposalStatus.ACTIVE,FinanceProposalStatus.SNOOZED) && proposal.revision==input.baseRevision) {"候选已改变，请重新复核"}
            val old=dao.reviewSessionRows().singleOrNull {it.proposalId==input.proposalId}
            require(old?.revision==expectedRevision && input.epoch==token.restoreEpoch) {"复核编辑器已改变，请重新打开"}
            require(expected.map {it.position}.distinct().size==expected.size)
            val now=clock()
            if(later && proposal.status!=FinanceProposalStatus.SNOOZED) automation.dismiss(proposal.id,proposal.revision,FinanceProposalStatus.SNOOZED)
            dao.putReviewSession(listOf(input.copy(baseRevision=if(later && proposal.status!=FinanceProposalStatus.SNOOZED) Math.addExact(proposal.revision,1L) else proposal.revision,
                createdAt=old?.createdAt ?: now,updatedAt=now,state=if(later) FinanceReviewState.LATER else FinanceReviewState.EDITING,
                revision=Math.addExact(old?.revision ?: 0L,1L),voidedAt=null)))
            dao.clearReviewExpected(input.proposalId)
            dao.putReviewExpected(expected.map {it.copy(proposalId=input.proposalId)})
            input.proposalId
        }
    suspend fun confirm(token:FinanceCommandToken,proposalId:String,expectedRevision:Long,resolvedOccurredAt:Long,
        reviewedDuplicateIds:Set<String> =emptySet(),stillCurrent:()->Boolean={true}):String =
        commands.run(token,"REVIEW_CONFIRM",listOf(proposalId,expectedRevision,resolvedOccurredAt,reviewedDuplicateIds)) {
            require(stillCurrent()) { "当前复核窗口已失效，请从待确认重新打开" }
            val row=requireNotNull(dao.reviewSessionRows().singleOrNull {it.proposalId==proposalId && it.voidedAt==null})
            require(row.epoch==token.restoreEpoch && row.revision==expectedRevision && row.state!=FinanceReviewState.COMPLETED) {"复核草稿已改变"}
            val candidate=requireNotNull(db.financeIntakeDao().proposal(proposalId))
            require(candidate.revision==row.baseRevision && candidate.status in setOf(FinanceProposalStatus.ACTIVE,FinanceProposalStatus.SNOOZED)) {
                "此候选已由其他复核确认或改变，请查看已入账记录"
            }
            val accountId = requireNotNull(row.accountId) { "请选择账户以确认币种" }
            val currency = validateAccount(accountId, candidate, resolvedOccurredAt)
            val expected=dao.reviewExpectedRows().filter {it.proposalId==proposalId}.sortedBy {it.position}.map {input->
                FinanceExpectedFlowInput(input.expectedId,input.role,input.amountText.takeIf {it.isNotBlank() && !input.cancelled}?.let {FinanceMoney.parse(it,currency)},input.note,input.cancelled)
            }
            val input=FinanceProposalConfirmation(FinanceProposalKind.valueOf(row.kind.name),accountId,row.targetAccountId,
                FinanceCalculator.evaluate(row.amountText,currency),row.description,resolvedOccurredAt,row.category,row.subcategory,
                row.tagsText.split(',', '，','\n').map(String::trim).filter(String::isNotBlank),
                FinanceEventInput(row.nature,row.shareText.takeIf(String::isNotBlank)?.let {FinanceMoney.parse(it,currency,true)},row.role,row.eventId,
                    expected.takeIf {row.expectedEdited},if(row.excluded) FinanceStatPolicy.EXCLUDE else FinanceStatPolicy.INCLUDE,
                    if(row.excludedBudget) FinanceBudgetPolicy.EXCLUDE else FinanceBudgetPolicy.INCLUDE,expectedEventUpdatedAt=row.eventUpdatedAt),
                row.categoryId,reviewedDuplicateIds,row.costReductionText.takeIf(String::isNotBlank)?.let {FinanceMoney.parse(it,currency,true)})
            require(stillCurrent()) { "当前复核窗口已失效，请从待确认重新打开" }
            val result=automation.confirm(row.proposalId,row.baseRevision,input,token.restoreEpoch)
            require(stillCurrent()) { "当前复核窗口已失效，请从待确认重新打开" }
            dao.putReviewSession(listOf(row.copy(state=FinanceReviewState.COMPLETED,updatedAt=clock(),revision=Math.addExact(row.revision,1L))))
            result
        }
}
