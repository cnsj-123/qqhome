package com.qq.closie.life.finance

import com.qq.closie.life.data.database.LifeDatabase

/** Raw typed editing fields can be incomplete. They never contribute cash, cost or obligations. */
class FinanceEconomicDraftRepository(private val db:LifeDatabase,private val finance:FinanceRepository,private val clock:()->Long=System::currentTimeMillis) {
    private val commands=FinanceCommandRunner(db,finance,clock)
    private val dao get()=db.financeV16Dao()
    suspend fun save(token:FinanceCommandToken,input:FinanceEconomicDraftEntity,funding:List<FinanceEconomicDraftFundingEntity>,
        shares:List<FinanceEconomicDraftShareEntity>,expectedRevision:Long?):String=
        commands.run(token,"ECONOMIC_DRAFT_SAVE",listOf(input,funding,shares,expectedRevision)) {
            val old=dao.economicDraftRows().singleOrNull {it.id==input.id}
            require(old?.revision==expectedRevision && old?.voidedAt==null && input.epoch==token.restoreEpoch) {"经济草稿已改变，请重新打开"}
            require(funding.size<=100 && shares.size<=100 && funding.all {it.draftId==input.id} && shares.all {it.draftId==input.id})
            require(funding.map {it.localId}.distinct().size==funding.size && shares.map {it.localId}.distinct().size==shares.size)
            require(funding.map {it.position}.distinct().size==funding.size && shares.map {it.position}.distinct().size==shares.size)
            val now=clock()
            dao.putEconomicDraft(listOf(input.copy(createdAt=old?.createdAt ?: now,updatedAt=now,revision=Math.addExact(old?.revision ?: 0L,1L),voidedAt=null)))
            dao.clearEconomicDraftFunding(input.id);dao.clearEconomicDraftShares(input.id)
            dao.putEconomicDraftFunding(funding);dao.putEconomicDraftShare(shares);input.id
        }
    suspend fun commit(token:FinanceCommandToken,draftId:String,expectedRevision:Long):String=
        commands.run(token,"ECONOMIC_DRAFT_COMMIT",listOf(draftId,expectedRevision)) {
            val row=requireNotNull(dao.economicDraftRows().singleOrNull {it.id==draftId && it.voidedAt==null})
            require(row.revision==expectedRevision && row.epoch==token.restoreEpoch) {"经济草稿已改变，请重新打开"}
            if(row.eventId!=null) {
                val event=requireNotNull(db.financeIntakeDao().event(row.eventId))
                require(event.voidedAt==null && event.updatedAt==row.eventVersion) {"原事项在编辑后已改变，请重新核对再建立分配草稿"}
            }
            val funds=dao.economicDraftFundingRows().filter {it.draftId==draftId}.sortedBy {it.position}
            val portions=dao.economicDraftShareRows().filter {it.draftId==draftId}.sortedBy {it.position}
            val input=FinanceEconomicInput(row.eventId,row.expectedRevision,row.description,row.currencyCode,
                FinanceTime.resolve(row.occurredText,row.preservedOccurredAt),row.valueText.takeIf(String::isNotBlank)?.let {FinanceMoney.parse(it,row.currencyCode,true)},
                row.valueBasis,row.categoryId,row.allocationsComplete,
                funds.map {FinanceFundingInput(it.localId,it.partySelection,FinanceMoney.parse(it.amountText,row.currencyCode),it.entryLinkSelection,it.basis)},
                portions.map {part->
                    val payer=funds.singleOrNull {it.localId==part.fundingLocalId}?.partySelection
                    FinanceShareInput(part.localId,part.amountText.takeIf(String::isNotBlank)?.let {FinanceMoney.parse(it,row.currencyCode)},part.beneficiarySelection,
                        when(part.relation){FinanceShareRelation.UNRESOLVED->null;FinanceShareRelation.REPAYABLE_CONFIRMED->part.bearerSelection;else->payer},part.fundingLocalId,part.relation)
                },if(row.statExcluded) FinanceStatPolicy.EXCLUDE else FinanceStatPolicy.INCLUDE,
                if(row.budgetExcluded) FinanceBudgetPolicy.EXCLUDE else FinanceBudgetPolicy.INCLUDE)
            val result=finance.economics.confirmEconomic(FinanceCommandToken(token.operationId+":confirm",token.restoreEpoch),input)
            dao.putEconomicDraft(listOf(row.copy(voidedAt=clock(),updatedAt=clock(),revision=Math.addExact(row.revision,1L))));result
        }
    suspend fun discard(token:FinanceCommandToken,draftId:String,expectedRevision:Long):String=
        commands.run(token,"ECONOMIC_DRAFT_DISCARD",listOf(draftId,expectedRevision)) {
            val row=requireNotNull(dao.economicDraftRows().singleOrNull {it.id==draftId && it.voidedAt==null})
            require(row.revision==expectedRevision)
            dao.putEconomicDraft(listOf(row.copy(voidedAt=clock(),updatedAt=clock(),revision=Math.addExact(row.revision,1L))));draftId
        }
}
