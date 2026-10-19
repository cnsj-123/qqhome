package com.qq.closie.life.finance

enum class FinanceQueryMode { PERSONAL_COST, CASH_MOVEMENT }

/** Versioned user query configuration. No generated SQL or duplicated financial rows. */
data class FinanceQuerySpec(
    val version: Int = 1, val mode: FinanceQueryMode = FinanceQueryMode.PERSONAL_COST,
    val startInclusive: Long? = null, val endExclusive: Long? = null,
    val accountIds: Set<String> = emptySet(), val categoryIds: Set<String> = emptySet(),
    val tagIds: Set<String> = emptySet(), val eventIds: Set<String> = emptySet(),
    val currencyCode: String? = null, val minimumMinor: Long? = null, val maximumMinor: Long? = null,
    val text: String = ""
) {
    fun validate() {
        require(version == 1 && mode in FinanceQueryMode.entries) { "查询版本不支持" }
        FinanceDateRange(startInclusive, endExclusive)
        require(minimumMinor == null || maximumMinor == null || minimumMinor <= maximumMinor)
        currencyCode?.let(FinanceMoney::fractionDigits)
        require(text.length <= 200 && listOf(accountIds, categoryIds, tagIds, eventIds).all { ids -> ids.all { it.isNotBlank() } })
    }
}
data class FinanceQueryResult(val spec: FinanceQuerySpec, val costs: List<PersonalCostRow>,
    val cash: List<FinanceEntryEntity>, val totals: Map<String, PersonalCostTotal>)

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
        val entryTagIds=snapshot.entryTags.groupBy { it.entryId }.mapValues { it.value.map { row -> row.tagId }.toSet() }
        val categories = spec.categoryIds.flatMap { FinanceCategoryTree(snapshot.categories).descendants(it) }.toSet()
        val entryEvents = snapshot.v4?.activeLinks.orEmpty().groupBy { it.entryId }.mapValues { (_, links) -> links.map { it.eventId }.toSet() }
        val eventAccounts = snapshot.v4?.activeLinks.orEmpty().groupBy { it.eventId }.mapValues { (_, links) ->
            links.mapNotNull { entries[it.entryId]?.takeIf { entry -> entry.voidedAt==null }?.accountId }.toSet()
        }
        fun amountMatches(amount: Long?) = if(amount==null) spec.minimumMinor==null && spec.maximumMinor==null
            else (spec.minimumMinor == null || amount >= spec.minimumMinor) && (spec.maximumMinor == null || amount <= spec.maximumMinor)
        val costs = if (spec.mode != FinanceQueryMode.PERSONAL_COST) emptyList() else FinanceCostProjection.rows(snapshot).filter {
            range.contains(it.occurredAt) && (spec.currencyCode == null || it.currencyCode == spec.currencyCode) &&
                (spec.eventIds.isEmpty() || it.eventId in spec.eventIds) &&
                (spec.accountIds.isEmpty() || eventAccounts[it.eventId].orEmpty().any { account -> account in spec.accountIds } ||
                    entries[it.entryId]?.accountId in spec.accountIds) &&
                (categories.isEmpty() || it.categoryId in categories) &&
                (spec.tagIds.isEmpty() || it.tagIds.containsAll(spec.tagIds)) &&
                (spec.text.isBlank() || (listOf(it.description,FinanceProjection.categoryPath(snapshot.categories,it.categoryId).orEmpty())+
                    it.tags).any { value -> value.contains(spec.text,true) }) && amountMatches(it.amountMinor)
        }
        val cash = if (spec.mode != FinanceQueryMode.CASH_MOVEMENT) emptyList() else snapshot.entries.filter {
            it.voidedAt == null && range.contains(it.occurredAt) && (spec.accountIds.isEmpty() || it.accountId in spec.accountIds) &&
                (spec.currencyCode == null || accounts[it.accountId]?.currencyCode == spec.currencyCode) &&
                (categories.isEmpty() || it.categoryId in categories) &&
                (spec.eventIds.isEmpty() || entryEvents[it.id].orEmpty().any { id -> id in spec.eventIds }) &&
                (spec.tagIds.isEmpty() || entryTagIds[it.id].orEmpty().containsAll(spec.tagIds)) &&
                (spec.text.isBlank() || it.description.contains(spec.text, true)) && amountMatches(it.amountMinor)
        }
        val totals = if (spec.mode == FinanceQueryMode.PERSONAL_COST) costs.groupBy { it.currencyCode }.mapValues { FinanceCostProjection.total(it.value) }
        else cash.groupBy { accounts.getValue(it.accountId).currencyCode }.mapValues { (_, rows) ->
            PersonalCostTotal(exactSum(rows.map { if (it.direction == FinanceDirection.OUTFLOW) it.amountMinor else -it.amountMinor }), 0)
        }
        return FinanceQueryResult(spec, costs, cash, totals)
    }
}
