package com.qq.closie.life.finance

/** Restricted-purpose cash reads retain their original per-leg fields. Full transfer metadata is
 * for owner ledger queries only; granting one leg never grants the peer leg's date or description. */
internal object FinanceRestrictedCashQuery {
    fun execute(snapshot:FinanceSnapshot,spec:FinanceQuerySpec,allowedEntryIds:Set<String>):FinanceQueryResult {
        validateFinanceQueryReferences(snapshot,spec)
        val accounts=snapshot.accounts.associateBy {it.id}
        val events=snapshot.v4?.events.orEmpty().filter {it.voidedAt==null}.associateBy {it.id}
        val links=snapshot.v4?.activeLinks.orEmpty().groupBy {it.entryId}
        val transferLegs=snapshot.transfers.flatMap {listOf(it.outflowEntryId,it.inflowEntryId)}.toSet()
        val categories=spec.categoryIds.flatMap {FinanceCategoryTree(snapshot.categories).descendants(it)}.toSet()
        val tags=snapshot.entryTags.groupBy {it.entryId}.mapValues {(_,rows)->rows.map {it.tagId}.toSet()}
        val unknown=snapshot.v16?.entryTimingRows.orEmpty().filter {it.voidedAt==null &&
            it.cashPrecision==FinanceTimePrecision.UNKNOWN}.map {it.entryId}.toSet()
        val candidates=snapshot.entries.filter {it.id in allowedEntryIds}.filter {entry->
            val eventIds=links[entry.id].orEmpty().map {it.eventId}.toSet()
            entry.voidedAt==null && (spec.accountIds.isEmpty() || entry.accountId in spec.accountIds) &&
                (spec.currencyCode==null || accounts[entry.accountId]?.currencyCode==spec.currencyCode) &&
                (categories.isEmpty() || entry.categoryId in categories) &&
                (spec.tagIds.isEmpty() || tags[entry.id].orEmpty().containsAll(spec.tagIds)) &&
                (spec.eventIds.isEmpty() || eventIds.any {it in spec.eventIds}) &&
                (spec.nature==null || eventIds.any {events[it]?.nature==spec.nature} ||
                    (spec.nature==FinanceNature.PERSONAL && eventIds.isEmpty() && entry.id !in transferLegs)) &&
                (spec.minimumMinor==null || entry.amountMinor>=spec.minimumMinor) &&
                (spec.maximumMinor==null || entry.amountMinor<=spec.maximumMinor) &&
                (spec.text.isBlank() || entry.description.contains(spec.text.trim(),true))
        }
        val bounded=spec.startInclusive!=null || spec.endExclusive!=null
        val undated=if(bounded) candidates.filter {it.id in unknown} else emptyList()
        val range=FinanceDateRange(spec.startInclusive,spec.endExclusive)
        val cash=candidates.filter {(!bounded || it.id !in unknown) && range.contains(it.occurredAt)}
        val totals=cash.groupBy {accounts.getValue(it.accountId).currencyCode}.mapValues {(_,rows)->
            PersonalCostTotal(exactSum(rows.map {if(it.direction==FinanceDirection.OUTFLOW) it.amountMinor else -it.amountMinor}),0)
        }
        return FinanceQueryResult(spec,emptyList(),cash,totals,undatedCash=undated)
    }
}
