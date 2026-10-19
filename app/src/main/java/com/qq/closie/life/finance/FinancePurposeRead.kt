package com.qq.closie.life.finance

data class FinanceGrantedFact(val eventId:String,val currencyCode:String,val amountMinor:Long?,val incomplete:Boolean,
    val occurredAt:Long?,val description:String?)

/** Ephemeral derived facts. Expiry checks never re-enter the ledger/cost projection. */
class FinancePurposeFacts internal constructor(private val candidates:List<Pair<FinanceGrantedFact,List<FinancePurposeGrantEntity>>>) {
    fun at(now:Long):List<FinanceGrantedFact> = candidates.mapNotNull {(fact,grants)->
        val live=grants.filter {it.expiresAt>now}
        if(live.isEmpty()) null else fact.copy(amountMinor=fact.amountMinor.takeIf {live.any {it.allowAmount}},
            incomplete=fact.incomplete && live.any {it.allowAmount},occurredAt=fact.occurredAt.takeIf {live.any {it.allowDate}},
            description=fact.description.takeIf {live.any {it.allowDescription}})
    }
}

object FinancePurposeRead {
    /** Exact dependencies of facts(): intake, drafts, attachments and journal-only changes are irrelevant. */
    fun factsInput(s:FinanceSnapshot)=s.copy(
        v4=s.v4?.let {FinanceV4Snapshot(events=it.events,links=it.links)},
        v16=s.v16?.let {FinanceV16Snapshot(economicRows=it.economicRows,shareRows=it.shareRows,costAdjustmentRows=it.costAdjustmentRows,
            purposeGrantRows=it.purposeGrantRows,grantEventRows=it.grantEventRows,controlRows=it.controlRows)})

    fun prepareFacts(s:FinanceSnapshot,purpose:FinanceReadPurpose,provider:String,now:Long):FinancePurposeFacts {
        val v=s.v16 ?: return FinancePurposeFacts(emptyList())
        val grants=v.purposeGrantRows.filter {it.purpose==purpose && it.providerId==provider && it.voidedAt==null && it.expiresAt>now}
        if(grants.isEmpty()) return FinancePurposeFacts(emptyList())
        val grantsById=grants.associateBy {it.id}
        val scopes=v.grantEventRows.mapNotNull {row->grantsById[row.grantId]?.let {row.eventId to it}}.groupBy({it.first},{it.second})
        if(scopes.isEmpty()) return FinancePurposeFacts(emptyList())
        val costs=if(grants.any {it.allowAmount}) FinanceCostProjection.rows(s,includeExcluded=true).groupBy {it.eventId} else emptyMap()
        return FinancePurposeFacts(s.v4?.events.orEmpty().filter {it.voidedAt==null && it.id in scopes}.map {event->
            val scoped=scopes.getValue(event.id)
            val amountsAllowed=scoped.any {it.allowAmount}
            val total=FinanceCostProjection.total(costs[event.id].orEmpty())
            val measurable=costs[event.id].orEmpty().any {it.amountMinor!=null} || event.personalShareMinor==0L
            FinanceGrantedFact(event.id,event.currencyCode,if(amountsAllowed && measurable) total.confirmedMinor else null,
                amountsAllowed && (!total.complete || !measurable),event.occurredAt.takeIf {scoped.any {it.allowDate}},
                event.description.takeIf {scoped.any {it.allowDescription}}) to scoped
        })
    }
    fun facts(s:FinanceSnapshot,purpose:FinanceReadPurpose,provider:String,now:Long):List<FinanceGrantedFact> =
        prepareFacts(s,purpose,provider,now).at(now)
    fun query(s:FinanceSnapshot,spec:FinanceQuerySpec,grantId:String,now:Long):FinanceQueryResult {
        val v=requireNotNull(s.v16)
        val grant=requireNotNull(v.purposeGrantRows.singleOrNull {it.id==grantId && it.purpose==FinanceReadPurpose.LOCAL_QUERY &&
            it.providerId=="local-conditions" && it.voidedAt==null && it.expiresAt>now}) {"本地条件查询未授权或已撤销"}
        require(grant.allowAmount && grant.allowDate && grant.allowDescription) {"此查询需要金额、日期和备注字段，请明确授权"}
        val events=v.grantEventRows.filter {it.grantId==grantId}.map {it.eventId}.toSet()
        val entries=v.grantEntryRows.filter {it.grantId==grantId}.map {it.entryId}.toSet()
        val result=FinanceQueryEngine.execute(s,spec)
        val costs=result.costs.filter {it.eventId in events || (it.eventId==null && it.entryId in entries)}
        val cash=result.cash.filter {it.id in entries}
        val totals=if(spec.mode==FinanceQueryMode.PERSONAL_COST) costs.groupBy {it.currencyCode}.mapValues {FinanceCostProjection.total(it.value)}
        else cash.groupBy {row->s.accounts.single {it.id==row.accountId}.currencyCode}.mapValues {(_,rows)->PersonalCostTotal(exactSum(rows.map {if(it.direction==FinanceDirection.OUTFLOW) it.amountMinor else -it.amountMinor}),0)}
        return result.copy(costs=costs,cash=cash,totals=totals)
    }
}
