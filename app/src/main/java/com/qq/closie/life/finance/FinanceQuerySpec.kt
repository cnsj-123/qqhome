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
    fun execute(snapshot: FinanceSnapshot, spec: FinanceQuerySpec, includeAccountText:Boolean=true): FinanceQueryResult {
        validateFinanceQueryReferences(snapshot,spec)
        if(spec.mode==FinanceQueryMode.CASH_MOVEMENT) {
            val selection=FinanceLedgerQuery.execute(snapshot,spec)
            return FinanceQueryResult(spec,emptyList(),selection.cash,selection.totals,undatedCash=selection.undatedCash)
        }
        val range = FinanceDateRange(spec.startInclusive, spec.endExclusive)
        val accounts = snapshot.accounts.associateBy { it.id }
        val entries=snapshot.entries.associateBy { it.id }
        val events=snapshot.v4?.events.orEmpty().filter {it.voidedAt==null}.associateBy {it.id}
        val categories = spec.categoryIds.flatMap { FinanceCategoryTree(snapshot.categories).descendants(it) }.toSet()
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
                    it.tags+(if(includeAccountText) (eventAccounts[it.eventId].orEmpty()+listOfNotNull(entries[it.entryId]?.accountId))
                        .mapNotNull {id->accounts[id]?.name} else emptyList())).any { value -> value.contains(spec.text.trim(),true) }) && amountMatches(it.amountMinor)
        }
        val costPeriod=FinanceCostProjection.period(snapshot,costCandidates,range)
        val undatedCosts=costPeriod.undatedCosts
        val costs=costPeriod.rows
        val totals=costs.groupBy {it.currencyCode}.mapValues {FinanceCostProjection.total(it.value)}
        return FinanceQueryResult(spec,costs,emptyList(),totals,undatedCosts=undatedCosts)

    }
}
