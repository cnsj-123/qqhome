package com.qq.closie.life.finance

data class FinanceGrantedFact(val eventId:String,val currencyCode:String,val amountMinor:Long?,val incomplete:Boolean,
    val occurredAt:Long?,val description:String?)
object FinancePurposeRead {
    fun facts(s:FinanceSnapshot,purpose:FinanceReadPurpose,provider:String,now:Long):List<FinanceGrantedFact> {
        val v=s.v16 ?: return emptyList()
        val grants=v.purposeGrantRows.filter {it.purpose==purpose && it.providerId==provider && it.voidedAt==null && it.expiresAt>now}
        val costs=FinanceCostProjection.rows(s,includeExcluded=true).groupBy {it.eventId}
        return s.v4?.events.orEmpty().filter {it.voidedAt==null}.mapNotNull {event->
            val scoped=grants.filter {g->v.grantEventRows.any {it.grantId==g.id && it.eventId==event.id}}
            if(scoped.isEmpty()) return@mapNotNull null
            val total=FinanceCostProjection.total(costs[event.id].orEmpty())
            val measurable=costs[event.id].orEmpty().any {it.amountMinor!=null} || event.personalShareMinor==0L
            FinanceGrantedFact(event.id,event.currencyCode,if(scoped.any {it.allowAmount} && measurable) total.confirmedMinor else null,
                scoped.any {it.allowAmount} && (!total.complete || !measurable),if(scoped.any {it.allowDate}) event.occurredAt else null,
                if(scoped.any {it.allowDescription}) event.description else null)
        }
    }
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
