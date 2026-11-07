package com.qq.closie.life.finance

enum class FinanceLedgerDateBasis { ECONOMIC_LEDGER, CASH_MOVEMENT }

/** One read-only selection for the ledger and its query preview. A transfer is one visible row;
 * cash/undatedCash retain its real, account-scoped funding legs for explicitly named fund totals. */
data class FinanceLedgerQueryResult(
    val rows:List<FinanceLedgerRow>, val undatedRows:List<FinanceLedgerRow>,
    val matchingRows:List<FinanceLedgerRow>, val cash:List<FinanceEntryEntity>,
    val undatedCash:List<FinanceEntryEntity>, val unknownDateCount:Int,
    val cashDatedRows:List<FinanceLedgerRow>, val dateBasis:FinanceLedgerDateBasis,
    private val fundingByCurrency:Map<String,List<FinanceEntryEntity>>
) {
    val totals:Map<String,PersonalCostTotal> by lazy {
        fundingByCurrency.mapValues {(_,legs)->
            PersonalCostTotal(exactSum(legs.map {if(it.direction==FinanceDirection.OUTFLOW) it.amountMinor else -it.amountMinor}),0)
        }
    }
    val directions:Map<String,Set<FinanceDirection>> by lazy {
        fundingByCurrency.mapValues {(_,legs)->legs.map {it.direction}.toSet()}
    }
}

internal fun validateFinanceQueryReferences(snapshot:FinanceSnapshot,spec:FinanceQuerySpec) {
    spec.validate()
    require(spec.accountIds.all {id->snapshot.accounts.any {it.id==id}} &&
        spec.categoryIds.all {id->snapshot.categories.any {it.id==id}} &&
        spec.tagIds.all {id->snapshot.tags.any {it.id==id}} &&
        spec.eventIds.all {id->snapshot.v4?.events.orEmpty().any {it.id==id}}) {
        "查询中的账户、分类、标签或事项已不可用，请更新条件"
    }
}

object FinanceLedgerQuery {
    fun execute(snapshot:FinanceSnapshot,spec:FinanceQuerySpec,
        canonicalRows:List<FinanceLedgerRow> = FinanceProjection.ledger(snapshot),
        dateBasis:FinanceLedgerDateBasis=FinanceLedgerDateBasis.CASH_MOVEMENT):FinanceLedgerQueryResult {
        validateFinanceQueryReferences(snapshot,spec)
        require(spec.mode==FinanceQueryMode.CASH_MOVEMENT) {"账簿查询需要资金流水口径"}
        val entries=snapshot.entries.associateBy {it.id}
        val accounts=snapshot.accounts.associateBy {it.id}
        val categories=spec.categoryIds.flatMap {FinanceCategoryTree(snapshot.categories).descendants(it)}.toSet()
        val tags=snapshot.entryTags.groupBy {it.entryId}.mapValues {(_,rows)->rows.map {it.tagId}.toSet()}
        val tagNames=snapshot.tags.associate {it.id to it.name}
        val events=snapshot.v4?.events.orEmpty().filter {it.voidedAt==null}.associateBy {it.id}
        val links=snapshot.v4?.activeLinks.orEmpty().groupBy {it.entryId}
        val unknown=snapshot.v16?.entryTimingRows.orEmpty().filter {it.voidedAt==null &&
            it.cashPrecision==FinanceTimePrecision.UNKNOWN}.map {it.entryId}.toSet()
        fun legs(row:FinanceLedgerRow)=if(row.transfer==null) listOf(row.entry) else
            listOfNotNull(entries[row.transfer.outflowEntryId],entries[row.transfer.inflowEntryId]).filter {it.voidedAt==null}
        val candidates=canonicalRows.filter {row->
            val legs=legs(row)
            val rowEvents=legs.flatMap {links[it.id].orEmpty()}.mapNotNull {events[it.eventId]}
            (spec.accountIds.isEmpty() || legs.any {it.accountId in spec.accountIds}) &&
                (spec.currencyCode==null || row.currencyCode==spec.currencyCode) &&
                (categories.isEmpty() || legs.any {it.categoryId in categories}) &&
                (spec.tagIds.isEmpty() || legs.flatMap {tags[it.id].orEmpty()}.toSet().containsAll(spec.tagIds)) &&
                (spec.eventIds.isEmpty() || rowEvents.any {it.id in spec.eventIds}) &&
                (spec.nature==null || rowEvents.any {it.nature==spec.nature} ||
                    (spec.nature==FinanceNature.PERSONAL && row.transfer==null && rowEvents.isEmpty())) &&
                (spec.minimumMinor==null || row.entry.amountMinor>=spec.minimumMinor) &&
                (spec.maximumMinor==null || row.entry.amountMinor<=spec.maximumMinor) &&
                (spec.text.isBlank() || (listOfNotNull(row.entry.description,row.category,row.account.name,
                    row.account.kind.label,row.targetAccount?.name,row.targetAccount?.kind?.label)+row.tags+
                    legs.flatMap {tags[it.id].orEmpty()}.mapNotNull {tagNames[it]}+
                    rowEvents.map {it.description}).any {it.contains(spec.text.trim(),true)})
        }
        val bounded=spec.startInclusive!=null || spec.endExclusive!=null
        val cashUnknownRows=if(unknown.isEmpty()) emptyList() else candidates.filter {row->legs(row).any {it.id in unknown}}
        val cashUnknownIds=cashUnknownRows.map {it.id}.toSet()
        val range=FinanceDateRange(spec.startInclusive,spec.endExclusive)
        val cashDatedRows=candidates.filter {
            (!bounded || it.id !in cashUnknownIds) && range.contains(it.entry.occurredAt)
        }
        // D1/G0: the ordinary ledger follows an independently known economic date. Refund and
        // adjustment links are deliberately absent from economicDates and keep their own date.
        val economicDates=if(dateBasis==FinanceLedgerDateBasis.ECONOMIC_LEDGER) FinanceProjection.economicDates(snapshot) else emptyMap()
        fun effectiveDate(row:FinanceLedgerRow)=economicDates[row.entry.id]
            ?: row.entry.occurredAt.takeUnless {row.id in cashUnknownIds}
        val unknownDateRows=candidates.filter {effectiveDate(it)==null}
        val undated=if(bounded) unknownDateRows else emptyList()
        val rows=if(dateBasis==FinanceLedgerDateBasis.CASH_MOVEMENT) cashDatedRows else candidates.filter {row->
            effectiveDate(row)?.let(range::contains) ?: !bounded
        }
        fun fundingRows(rows:List<FinanceLedgerRow>)=rows.flatMap(::legs).filter {
            spec.accountIds.isEmpty() || it.accountId in spec.accountIds
        }.distinctBy {it.id}
        val cash=fundingRows(rows)
        val byCurrency=cash.groupBy {accounts.getValue(it.accountId).currencyCode}
        return FinanceLedgerQueryResult(rows,undated,candidates,cash,fundingRows(undated),unknownDateRows.size,
            cashDatedRows,dateBasis,byCurrency)
    }
}
