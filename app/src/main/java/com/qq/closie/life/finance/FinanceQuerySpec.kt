package com.qq.closie.life.finance

enum class FinanceQueryMode { PERSONAL_COST, CASH_MOVEMENT }

/** Versioned user query configuration. No generated SQL or duplicated financial rows. */
data class FinanceQuerySpec(
    val version: Int = 1, val mode: FinanceQueryMode = FinanceQueryMode.PERSONAL_COST,
    val startInclusive: Long? = null, val endExclusive: Long? = null,
    val accountIds: Set<String> = emptySet(), val categoryIds: Set<String> = emptySet(),
    val tagIds: Set<String> = emptySet(), val eventIds: Set<String> = emptySet(),
    val currencyCode: String? = null, val minimumMinor: Long? = null, val maximumMinor: Long? = null,
    val text: String = "", val nature: FinanceNature? = null
) {
    fun validate() {
        require(version in 1..2 && mode in FinanceQueryMode.entries &&
            (if (version == 1) nature == null else nature != null)) { "查询版本或性质不支持" }
        FinanceDateRange(startInclusive, endExclusive)
        require(minimumMinor == null || maximumMinor == null || minimumMinor <= maximumMinor)
        currencyCode?.let(FinanceMoney::fractionDigits)
        require(text.length <= 200 && listOf(accountIds, categoryIds, tagIds, eventIds).all { ids -> ids.all { it.isNotBlank() } })
    }
    companion object {
        fun decode(json: String): FinanceQuerySpec {
            val raw = com.google.gson.JsonParser.parseString(json).asJsonObject
            val spec = com.google.gson.Gson().fromJson(raw, FinanceQuerySpec::class.java)
            if (raw.has("nature") && !raw.get("nature").isJsonNull) require(spec.nature != null) { "保存视图的业务性质无法解释" }
            return spec.also { it.validate() }
        }
    }
}
data class FinanceQueryResult(val spec: FinanceQuerySpec, val costs: List<PersonalCostRow>,
    val cash: List<FinanceEntryEntity>, val totals: Map<String, PersonalCostTotal>,
    val undatedCosts: List<PersonalCostRow> = emptyList(), val undatedCash: List<FinanceEntryEntity> = emptyList())

object FinanceQueryEngine {
    fun execute(snapshot: FinanceSnapshot, spec: FinanceQuerySpec): FinanceQueryResult {
        spec.validate()
        require(spec.accountIds.all { id -> snapshot.accounts.any { it.id==id } } &&
            spec.categoryIds.all { id -> snapshot.categories.any { it.id==id } } &&
            spec.tagIds.all { id -> snapshot.tags.any { it.id==id } } &&
            spec.eventIds.all { id -> snapshot.v4?.events.orEmpty().any { it.id==id } }) {
            "查询中的账户、分类、标签或事项已不可用，请更新条件"
        }
        val range = FinanceDateRange(spec.startInclusive, spec.endExclusive)
        val accounts = snapshot.accounts.associateBy { it.id }
        val entries=snapshot.entries.associateBy { it.id }
        val events=snapshot.v4?.events.orEmpty().filter {it.voidedAt==null}.associateBy {it.id}
        val unknownDates=snapshot.v16?.entryTimingRows.orEmpty()
            .filter {it.voidedAt==null && it.cashPrecision==FinanceTimePrecision.UNKNOWN}.map {it.entryId}.toSet()
        val bounded=spec.startInclusive!=null || spec.endExclusive!=null
        val transferLegs=snapshot.transfers.flatMap {listOf(it.outflowEntryId,it.inflowEntryId)}.toSet()
        val entryTagIds=snapshot.entryTags.groupBy { it.entryId }.mapValues { it.value.map { row -> row.tagId }.toSet() }
        val categories = spec.categoryIds.flatMap { FinanceCategoryTree(snapshot.categories).descendants(it) }.toSet()
        val entryEvents = snapshot.v4?.activeLinks.orEmpty().groupBy { it.entryId }.mapValues { (_, links) -> links.map { it.eventId }.toSet() }
        val eventAccounts = snapshot.v4?.activeLinks.orEmpty().groupBy { it.eventId }.mapValues { (_, links) ->
            links.mapNotNull { entries[it.entryId]?.takeIf { entry -> entry.voidedAt==null }?.accountId }.toSet()
        }
        fun amountMatches(amount: Long?) = if(amount==null) spec.minimumMinor==null && spec.maximumMinor==null
            else (spec.minimumMinor == null || amount >= spec.minimumMinor) && (spec.maximumMinor == null || amount <= spec.maximumMinor)
        val costCandidates = if (spec.mode != FinanceQueryMode.PERSONAL_COST) emptyList() else FinanceCostProjection.rows(snapshot).filter {
            (spec.currencyCode == null || it.currencyCode == spec.currencyCode) &&
                (spec.nature == null || events[it.eventId]?.nature == spec.nature || (it.eventId==null && spec.nature==FinanceNature.PERSONAL)) &&
                (spec.eventIds.isEmpty() || it.eventId in spec.eventIds) &&
                (spec.accountIds.isEmpty() || eventAccounts[it.eventId].orEmpty().any { account -> account in spec.accountIds } ||
                    entries[it.entryId]?.accountId in spec.accountIds) &&
                (categories.isEmpty() || it.categoryId in categories) &&
                (spec.tagIds.isEmpty() || it.tagIds.containsAll(spec.tagIds)) &&
                (spec.text.isBlank() || (listOf(it.description,FinanceProjection.categoryPath(snapshot.categories,it.categoryId).orEmpty())+
                    it.tags).any { value -> value.contains(spec.text,true) }) && amountMatches(it.amountMinor)
        }
        val costPeriod=FinanceCostProjection.period(snapshot,costCandidates,range)
        val undatedCosts=costPeriod.undatedCosts
        val costs=costPeriod.rows
        val cashCandidates = if (spec.mode != FinanceQueryMode.CASH_MOVEMENT) emptyList() else snapshot.entries.filter {
            it.voidedAt == null && (spec.accountIds.isEmpty() || it.accountId in spec.accountIds) &&
                (spec.nature == null || entryEvents[it.id].orEmpty().any {id->events[id]?.nature==spec.nature} ||
                    (spec.nature==FinanceNature.PERSONAL && entryEvents[it.id].isNullOrEmpty() && it.id !in transferLegs)) &&
                (spec.currencyCode == null || accounts[it.accountId]?.currencyCode == spec.currencyCode) &&
                (categories.isEmpty() || it.categoryId in categories) &&
                (spec.eventIds.isEmpty() || entryEvents[it.id].orEmpty().any { id -> id in spec.eventIds }) &&
                (spec.tagIds.isEmpty() || entryTagIds[it.id].orEmpty().containsAll(spec.tagIds)) &&
                (spec.text.isBlank() || it.description.contains(spec.text, true)) && amountMatches(it.amountMinor)
        }
        val undatedCash=if(bounded) cashCandidates.filter {it.id in unknownDates} else emptyList()
        val undatedCashIds=undatedCash.map {it.id}.toSet()
        val cash=cashCandidates.filter {it.id !in undatedCashIds && range.contains(it.occurredAt)}
        val totals = if (spec.mode == FinanceQueryMode.PERSONAL_COST) costs.groupBy { it.currencyCode }.mapValues { FinanceCostProjection.total(it.value) }
        else cash.groupBy { accounts.getValue(it.accountId).currencyCode }.mapValues { (_, rows) ->
            PersonalCostTotal(exactSum(rows.map { if (it.direction == FinanceDirection.OUTFLOW) it.amountMinor else -it.amountMinor }), 0)
        }
        return FinanceQueryResult(spec, costs, cash, totals, undatedCosts, undatedCash)
    }
}
