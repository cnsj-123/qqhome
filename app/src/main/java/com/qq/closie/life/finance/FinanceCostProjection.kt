package com.qq.closie.life.finance

/** No synthetic Entry: cash and noncash costs share only this read model. */
data class PersonalCostRow(
    val id: String, val eventId: String?, val entryId: String?, val currencyCode: String,
    val amountMinor: Long?, val occurredAt: Long, val categoryId: String?, val description: String,
    val tags: Set<String> = emptySet(), val adjustment: Boolean = false,
    val budgetIncluded: Boolean = true, val tagIds: Set<String> = emptySet()
)
data class PersonalCostTotal(val confirmedMinor: Long, val unknownCount: Int) {
    val complete: Boolean get() = unknownCount == 0
}

object FinanceCostProjection {
    fun rows(snapshot: FinanceSnapshot,forBudget:Boolean=false,includeExcluded:Boolean=false): List<PersonalCostRow> {
        val v = snapshot.v16
        val events = snapshot.v4?.events.orEmpty().associateBy { it.id }
        val knownLegacy = FinancePersonalProjection.rows(snapshot, FinanceProjection.ledger(snapshot),forBudget,includeExcluded)
            .filter { it.entry.direction == FinanceDirection.OUTFLOW }.map {
                PersonalCostRow("cash:" + it.entry.id + ":" + (it.costEventId ?: "ordinary"), it.costEventId, it.entry.id, it.currencyCode,
                    it.entry.amountMinor, if (it.entry.amountMinor < 0) it.entry.occurredAt else events[it.costEventId]?.occurredAt ?: it.entry.occurredAt, it.entry.categoryId, it.entry.description,
                    it.tags.toSet(), adjustment = it.entry.amountMinor < 0,
                    budgetIncluded = it.entry.budgetPolicy == FinanceBudgetPolicy.INCLUDE,tagIds=it.tagIds)
            }
        val entryById=snapshot.entries.associateBy {it.id}
        val tagByEntry=snapshot.entryTags.groupBy {it.entryId}
        val tagNamesById=snapshot.tags.associate {it.id to it.name}
        val linked=snapshot.v4?.activeLinks.orEmpty().groupBy {it.eventId}
        val typedEvents=v?.economicRows.orEmpty().filter {it.voidedAt==null}.map {it.eventId}.toSet()
        val unknownLegacy=events.values.filter {event->event.voidedAt==null && event.id !in typedEvents &&
            ((event.nature in setOf(FinanceNature.SPLIT,FinanceNature.REIMBURSABLE) && event.personalShareMinor==null) ||
                event.nature in setOf(FinanceNature.PROXY_PURCHASE,FinanceNature.OTHER))}.mapNotNull {event->
            val payments=linked[event.id].orEmpty().filter {it.role in setOf(FinanceFlowRole.PAYMENT,FinanceFlowRole.FEE)}.mapNotNull {entryById[it.entryId]}
                .filter {it.voidedAt==null && it.direction==FinanceDirection.OUTFLOW && (includeExcluded ||
                    if(forBudget) it.budgetPolicy==FinanceBudgetPolicy.INCLUDE else it.statPolicy==FinanceStatPolicy.INCLUDE)}
            if(payments.isEmpty()) return@mapNotNull null
            val categories=payments.map {it.categoryId}.distinct()
            val tags=payments.flatMap {tagByEntry[it.id].orEmpty()}.map {it.tagId}.toSet()
            PersonalCostRow("legacy-unknown:"+event.id,event.id,null,event.currencyCode,null,event.occurredAt,
                categories.singleOrNull(),event.description,tags.mapNotNull {tagNamesById[it]}.toSet(),
                budgetIncluded=payments.any {it.budgetPolicy==FinanceBudgetPolicy.INCLUDE},tagIds=tags)
        }
        val legacy=knownLegacy+unknownLegacy
        if (v == null) return legacy
        val shares = v.shareRows.filter { it.voidedAt == null }.groupBy { it.eventId }
        val links = snapshot.v4?.activeLinks.orEmpty().associateBy { it.id }
        val entries = snapshot.entries.filter { it.voidedAt == null }.associateBy { it.id }
        val entryTags=snapshot.entryTags.groupBy { it.entryId }
        val tagNames=snapshot.tags.associate { it.id to it.name }
        val adjustmentsByEvent=v.costAdjustmentRows.filter { it.voidedAt==null }.groupBy { it.eventId }
        val eventTags = snapshot.v4?.activeLinks.orEmpty().groupBy { it.eventId }.mapValues { (_, linksForEvent) ->
            linksForEvent.flatMap { entryTags[it.entryId].orEmpty() }.mapNotNull { tagNames[it.tagId] }.toSet()
        }
        val eventTagIds=snapshot.v4?.activeLinks.orEmpty().groupBy { it.eventId }.mapValues { (_,eventLinks) ->
            eventLinks.flatMap { entryTags[it.entryId].orEmpty() }.map { it.tagId }.toSet()
        }
        val economic = v.economicRows.filter { it.voidedAt == null &&
            (includeExcluded || if(forBudget) it.budgetPolicy==FinanceBudgetPolicy.INCLUDE else it.statPolicy == FinanceStatPolicy.INCLUDE) }
            .flatMap { detail ->
                val event = events[detail.eventId]?.takeIf { it.voidedAt == null } ?: return@flatMap emptyList()
                val portions = shares[event.id].orEmpty()
                val base = portions.map { part ->
                    val personal = when {
                        part.relation == FinanceShareRelation.UNRESOLVED -> null
                        part.bearerId == FinanceV16Integrity.SELF -> part.amountMinor
                        else -> 0L
                    }
                    PersonalCostRow("share:" + part.id, event.id, null, event.currencyCode, personal,
                        event.occurredAt, detail.categoryId, event.description, eventTags[event.id].orEmpty(),
                        budgetIncluded = detail.budgetPolicy == FinanceBudgetPolicy.INCLUDE,tagIds=eventTagIds[event.id].orEmpty())
                }
                val unallocated = if (detail.allocationsComplete) emptyList() else listOf(
                    PersonalCostRow("unallocated:" + event.id, event.id, null, event.currencyCode, null,
                        event.occurredAt, detail.categoryId, event.description, eventTags[event.id].orEmpty(),
                        budgetIncluded = detail.budgetPolicy == FinanceBudgetPolicy.INCLUDE,tagIds=eventTagIds[event.id].orEmpty()))
                val adjustments = adjustmentsByEvent[event.id].orEmpty()
                    .mapNotNull { adjustment ->
                        val link = links[adjustment.entryLinkId] ?: return@mapNotNull null
                        val cash = entries[link.entryId] ?: return@mapNotNull null
                        if (!includeExcluded && (if(forBudget) cash.budgetPolicy==FinanceBudgetPolicy.EXCLUDE else cash.statPolicy == FinanceStatPolicy.EXCLUDE)) return@mapNotNull null
                        PersonalCostRow("adjustment:" + adjustment.id, event.id, cash.id, event.currencyCode,
                            -adjustment.personalReductionMinor, cash.occurredAt, detail.categoryId,
                            cash.description, eventTags[event.id].orEmpty(), adjustment = true,
                            budgetIncluded = detail.budgetPolicy == FinanceBudgetPolicy.INCLUDE &&
                                cash.budgetPolicy == FinanceBudgetPolicy.INCLUDE,tagIds=eventTagIds[event.id].orEmpty())
                    }
                base + unallocated + adjustments
            }
        return (legacy + economic).sortedWith(compareByDescending<PersonalCostRow> { it.occurredAt }.thenBy { it.id })
    }

    fun total(rows: List<PersonalCostRow>): PersonalCostTotal =
        PersonalCostTotal(exactSum(rows.mapNotNull { it.amountMinor }), rows.count { it.amountMinor == null })

    fun eventTotal(snapshot: FinanceSnapshot, eventId: String): PersonalCostTotal =
        total(rows(snapshot).filter { it.eventId == eventId })

    fun eventBaseTotal(snapshot: FinanceSnapshot, eventId: String): PersonalCostTotal =
        total(rows(snapshot).filter { it.eventId == eventId && !it.adjustment })
}
