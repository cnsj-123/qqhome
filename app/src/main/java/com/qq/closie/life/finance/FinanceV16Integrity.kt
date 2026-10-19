package com.qq.closie.life.finance

import java.math.BigInteger

internal fun exactSum(values: Iterable<Long>): Long =
    values.fold(BigInteger.ZERO) { sum, value -> sum + value.toBigInteger() }.longValueExact()

/** Same validator for user commands, archive import and post-restore health checks. */
object FinanceV16Integrity {
    const val SELF = "self"

    fun validate(s: FinanceSnapshot) {
        val v = s.v16 ?: return
        FinanceV16References.validate(s, v)
        val events = s.v4?.events.orEmpty().associateBy { it.id }
        val links = s.v4?.links.orEmpty().associateBy { it.id }
        val entries = s.entries.associateBy { it.id }
        val accounts = s.accounts.associateBy { it.id }
        val parties = v.partyRows.associateBy { it.id }
        require(v.partyRows.all { it.name.isNotBlank() && it.isSelf == (it.id == SELF) })
        val economic = v.economicRows.filter { it.voidedAt == null }.associateBy { it.eventId }
        val funding = v.fundingRows.filter { it.voidedAt == null }.associateBy { it.id }
        val shares = v.shareRows.filter { it.voidedAt == null }
        val obligations = v.obligationRows.filter { it.voidedAt == null }
        fun activeEvent(id: String) = requireNotNull(events[id]?.takeIf { it.voidedAt == null }) { "经济事项不可用" }
        fun activeParty(id: String) = requireNotNull(parties[id]?.takeIf { it.voidedAt == null }) { "参与方不可用" }
        economic.values.forEach { detail ->
            val event = activeEvent(detail.eventId)
            require(event.personalShareMinor == null) { "新经济分配与旧现金承担不能同时计费" }
            require(detail.valueMinor == null || detail.valueMinor >= 0)
            require(detail.valueBasis.isNotBlank()) { "经济事项需要确认依据" }
            val portions = shares.filter { it.eventId == event.id }
            val known = exactSum(portions.mapNotNull { it.amountMinor })
            require(detail.valueMinor == null || known <= detail.valueMinor) { "分配超过事项价值" }
            if (detail.allocationsComplete) {
                require(portions.isNotEmpty())
                require(detail.valueMinor == null || (portions.all { it.amountMinor != null } && known == detail.valueMinor)) { "完整分配必须精确守恒" }
            }
        }
        funding.values.forEach { row ->
            require(row.eventId in economic && row.amountMinor > 0 && row.basis.isNotBlank())
            activeParty(row.partyId)
            if (row.partyId == SELF) {
                val link = requireNotNull(links[row.entryLinkId]?.takeIf { it.voidedAt == null })
                require(link.eventId == row.eventId && link.role in setOf(FinanceFlowRole.PAYMENT, FinanceFlowRole.FEE))
                require(row.amountMinor <= link.allocatedMinor) { "本人出资必须有真实付款分配" }
            } else require(row.entryLinkId == null) { "外部付款不能使用本人流水冒充" }
        }
        funding.values.filter { it.entryLinkId != null }.groupBy { it.entryLinkId }.forEach { (id, rows) ->
            require(exactSum(rows.map { it.amountMinor }) <= links.getValue(requireNotNull(id)).allocatedMinor)
        }
        shares.forEach { share ->
            require(share.eventId in economic && (share.amountMinor == null || share.amountMinor > 0))
            activeParty(share.beneficiaryId)
            share.bearerId?.let(::activeParty)
            val source = share.fundingId?.let { requireNotNull(funding[it]) }
            require(source == null || source.eventId == share.eventId)
            val owed = obligations.filter { it.shareId == share.id }
            when (share.relation) {
                FinanceShareRelation.UNRESOLVED -> require(share.bearerId == null && owed.isEmpty())
                FinanceShareRelation.GIFT_CONFIRMED -> {
                    require(source != null && share.bearerId == source.partyId && share.bearerId != share.beneficiaryId)
                    require(owed.isEmpty()) { "赠送不能自动产生应付" }
                }
                FinanceShareRelation.PERSONAL_CONFIRMED -> {
                    require(source != null && share.bearerId == source.partyId && owed.isEmpty())
                }
                FinanceShareRelation.REPAYABLE_CONFIRMED -> {
                    require(source != null && share.amountMinor != null && share.bearerId != null && share.bearerId != source.partyId)
                    require(owed.size == 1 && owed.single().amountMinor == share.amountMinor &&
                        owed.single().creditorId == source.partyId && owed.single().debtorId == share.bearerId) { "归还关系与义务不一致" }
                }
            }
        }
        shares.filter { it.fundingId != null }.groupBy { it.fundingId }.forEach { (id, rows) ->
            require(exactSum(rows.mapNotNull { it.amountMinor }) <= funding.getValue(requireNotNull(id)).amountMinor)
        }
        obligations.forEach { row ->
            val event = activeEvent(row.eventId)
            row.shareId?.let { id ->
                val share = requireNotNull(shares.singleOrNull { it.id == id })
                require(share.eventId == row.eventId && share.relation == FinanceShareRelation.REPAYABLE_CONFIRMED)
            }
            require(row.amountMinor > 0 && row.currencyCode == event.currencyCode)
            require(row.creditorId != row.debtorId && (row.creditorId == SELF || row.debtorId == SELF))
            activeParty(row.creditorId); activeParty(row.debtorId)
            val cash = v.settlementRows.filter { it.obligationId == row.id && it.voidedAt == null }
            val resolutions = v.resolutionRows.filter { it.obligationId == row.id && it.voidedAt == null }
            require(exactSum(cash.map { it.amountMinor } + resolutions.map { it.amountMinor }) <= row.amountMinor) { "结算超过义务金额" }
            cash.forEach {
                val entry = requireNotNull(entries[it.entryId]?.takeIf { e -> e.voidedAt == null })
                require(it.amountMinor > 0 && accounts.getValue(entry.accountId).currencyCode == row.currencyCode)
                require(entry.direction == if (row.creditorId == SELF) FinanceDirection.INFLOW else FinanceDirection.OUTFLOW)
                require(s.transfers.none { t -> t.outflowEntryId == entry.id || t.inflowEntryId == entry.id }) { "内部转账不能冒充外部结算" }
                require(s.v4?.activeLinks?.any { link -> link.entryId == entry.id && link.eventId == row.eventId &&
                    link.role in setOf(FinanceFlowRole.SETTLEMENT, FinanceFlowRole.REIMBURSEMENT) } == true)
            }
            require(resolutions.all { it.amountMinor > 0 && it.note.isNotBlank() })
        }
        v.settlementRows.filter { it.voidedAt == null }.groupBy { it.entryId }.forEach { (id, rows) ->
            require(exactSum(rows.map { it.amountMinor }) <= entries.getValue(id).amountMinor)
            require(rows.all { row -> obligations.any { it.id == row.obligationId } })
            rows.groupBy { row -> obligations.single { it.id == row.obligationId }.eventId }.forEach { (eventId, eventRows) ->
                val allocated = exactSum(s.v4?.activeLinks.orEmpty().filter { it.entryId == id && it.eventId == eventId &&
                    it.role in setOf(FinanceFlowRole.SETTLEMENT, FinanceFlowRole.REIMBURSEMENT) }.map { it.allocatedMinor })
                require(exactSum(eventRows.map { it.amountMinor }) <= allocated) { "真实结算超过该事项的流水分配" }
            }
        }
        v.resolutionRows.filter { it.voidedAt == null }.forEach { row -> require(obligations.any { it.id == row.obligationId }) }
        v.costAdjustmentRows.filter { it.voidedAt == null }.forEach { row ->
            require(row.eventId in economic && row.personalReductionMinor >= 0)
            val link = requireNotNull(links[row.entryLinkId]?.takeIf { it.voidedAt == null })
            require(link.eventId == row.eventId && link.role in setOf(FinanceFlowRole.REFUND, FinanceFlowRole.REBATE, FinanceFlowRole.CASHBACK))
            require(row.personalReductionMinor <= link.allocatedMinor)
            row.lineId?.let { lineId ->
                require(v.purchaseAllocationRows.any { it.lineId == lineId && it.eventId == row.eventId && it.voidedAt == null }) {
                    "退款明细不属于该经济事项"
                }
            }
        }
        s.v4?.activeLinks.orEmpty().filter {it.eventId in economic && it.role in setOf(FinanceFlowRole.REFUND,FinanceFlowRole.REBATE,FinanceFlowRole.CASHBACK)}.forEach {link->
            require(v.costAdjustmentRows.any {it.entryLinkId==link.id && it.voidedAt==null}) {
                "经济事项的实际调整必须明确个人成本冲减额，不能仅关联流水"
            }
        }
        v.costAdjustmentRows.filter { it.voidedAt == null }.groupBy { it.entryLinkId }.forEach { (id, rows) ->
            require(exactSum(rows.map { it.personalReductionMinor }) <= links.getValue(id).allocatedMinor)
        }
        economic.keys.forEach { eventId ->
            val personal = exactSum(shares.filter { it.eventId == eventId && it.bearerId == SELF }.mapNotNull { it.amountMinor })
            val reduction = exactSum(v.costAdjustmentRows.filter { it.eventId == eventId && it.voidedAt == null }.map { it.personalReductionMinor })
            require(reduction <= personal) { "调整超过已确认个人承担，需先核实归属" }
        }
        v.purchaseRows.forEach { require(it.title.isNotBlank()); FinanceMoney.fractionDigits(it.currencyCode) }
        v.purchaseLineRows.forEach { require(it.title.isNotBlank() && it.quantity > 0 && (it.valueMinor == null || it.valueMinor >= 0)) }
        val lines = v.purchaseLineRows.associateBy { it.id }
        val purchases = v.purchaseRows.associateBy { it.id }
        val allocations = v.purchaseAllocationRows.filter { it.voidedAt == null }
        allocations.forEach {
            require(it.eventId in economic && it.valueMinor >= 0 && (it.personalMinor == null || it.personalMinor in 0..it.valueMinor))
            val line = lines.getValue(it.lineId)
            val purchase = purchases.getValue(line.purchaseId)
            require(line.voidedAt == null && purchase.voidedAt == null && purchase.currencyCode == activeEvent(it.eventId).currencyCode)
        }
        allocations.groupBy { it.lineId }.forEach { (id, rows) ->
            lines.getValue(id).valueMinor?.let { require(exactSum(rows.map { it.valueMinor }) <= it) }
            val reductions=v.costAdjustmentRows.filter {it.lineId==id && it.voidedAt==null}
            require(exactSum(reductions.map {it.personalReductionMinor})<=exactSum(rows.mapNotNull {it.personalMinor})) {
                "商品退款超过该明细已确认个人承担"
            }
        }
        allocations.groupBy {it.eventId to it.lineId}.forEach {(key,rows)->
            val reductions=v.costAdjustmentRows.filter {it.eventId==key.first && it.lineId==key.second && it.voidedAt==null}
            require(exactSum(reductions.map {it.personalReductionMinor})<=exactSum(rows.mapNotNull {it.personalMinor})) {
                "此事项的商品调整超过该事项对此商品的确认承担，不能用其他事项承担覆盖"
            }
        }
        allocations.groupBy { it.eventId }.forEach { (id, rows) ->
            economic[id]?.valueMinor?.let { require(exactSum(rows.map { it.valueMinor }) <= it) }
            val actualPersonal = exactSum(shares.filter { it.eventId == id && it.bearerId == SELF }.mapNotNull { it.amountMinor })
            require(exactSum(rows.mapNotNull { it.personalMinor }) <= actualPersonal)
        }
        v.entryTimingRows.forEach {
            require((it.postedAt == null) == (it.postedPrecision == FinanceTimePrecision.UNKNOWN))
            require(it.postedAt == null || it.postedSource.isNotBlank())
            FinanceCivilDate.validate(it.postedPrecision,it.postedAt,it.postedLocalDate,it.postedZoneId,
                it.postedDayStartAt,it.postedDayEndExclusive,it.postedDateBasisVersion)
        }
        v.observationRows.forEach {FinanceCivilDate.validate(it.precision,it.observedAt,it.observedLocalDate,it.observedZoneId,
            it.observedDayStartAt,it.observedDayEndExclusive,it.observedDateBasisVersion)}
        v.accountPolicyRows.filter { it.voidedAt == null }.forEach {
            require(it.liabilityConvention != FinanceLiabilityConvention.POSITIVE_DEBT || accounts.getValue(it.accountId).openingBalanceMinor >= 0) {
                "期初已经是负数时请按账面符号计入，不能再次转换负债起点"
            }
        }
        v.creditCycleRows.forEach { require(it.closingDay in 1..31 && it.dueDay in 1..31); java.time.ZoneId.of(it.zoneId) }
        require(v.reconciliationRows.all {it.confirmationOrder>0} &&
            v.reconciliationRows.map {it.confirmationOrder}.distinct().size==v.reconciliationRows.size)
        v.budgetRows.forEach { require(it.limitMinor >= 0 && it.startAt < it.endExclusive); FinanceMoney.fractionDigits(it.currencyCode) }
        v.accountBindingRows.forEach {
            require(it.validUntil == null || it.validUntil > it.validFrom)
            require(it.voidedAt != null || accounts.getValue(it.accountId).archivedAt == null)
        }
        v.accountBindingRows.filter { it.voidedAt == null }.groupBy { it.instrumentId }.values.forEach { rows ->
            val sorted = rows.sortedBy { it.validFrom }
            sorted.zipWithNext().forEach { (a,b) -> require(a.validUntil != null && a.validUntil <= b.validFrom) { "支付工具绑定区间重叠" } }
        }
        v.evidenceRows.forEach { require(it.summary.length <= 240 && it.provider.isNotBlank() && it.sourceFamily.isNotBlank()) }
        val identities=v.externalIdentityRows.associateBy {it.id}
        val instruments=v.instrumentRows.associateBy {it.id}
        val bindings=v.accountBindingRows.associateBy {it.id}
        v.externalIdentityRows.forEach {require(it.sourceFamily.matches(Regex("[a-z][a-z0-9-]{0,31}")) && it.label.isNotBlank())}
        v.evidenceRows.forEach {e->
            require(e.referenceDigest==null || e.referenceDigest.matches(Regex("[a-f0-9]{64}")))
            require(e.relatedOrderDigest==null || e.relatedOrderDigest.matches(Regex("[a-f0-9]{64}")))
            val identity=e.identityId?.let {identities.getValue(it)}
            require((identity==null)==(e.identityRevision==null))
            require(identity==null || (identity.sourceFamily==e.sourceFamily && e.identityRevision!!>0 &&
                (e.voidedAt!=null || e.identityRevision<=identity.revision)))
            require(e.instrumentId==null || instruments.getValue(e.instrumentId).identityId==e.identityId)
            val binding=e.bindingId?.let {bindings.getValue(it)}
            require((binding==null)==(e.bindingRevision==null))
            require(binding==null || (binding.instrumentId==e.instrumentId && e.bindingRevision!!>0 &&
                (e.voidedAt!=null || e.bindingRevision<=binding.revision)))
        }
        v.extractionRows.forEach { require(it.extractorVersion > 0 && (it.amountMinor == null || it.amountMinor > 0)) }
        v.mediaLinkRows.forEach {link->
            require(listOfNotNull(link.eventId,link.purchaseId,link.proposalId).size==1)
            require(link.mediaAssetId.isNotBlank() && link.label.isNotBlank() && link.label.length<=100 && link.sourceHash.matches(Regex("[a-f0-9]{64}")))
            // Media presence/URI access is deliberately a read-time availability state, not a money invariant.
        }
        v.economicDraftRows.forEach {require(it.epoch>=0 && it.revision>0)}
        v.draftRows.forEach {require(it.editGeneration>=0)}
        v.economicDraftFundingRows.groupBy {it.draftId}.values.forEach {rows->require(rows.size<=100 && rows.all {it.position>=0} && rows.map {it.position}.distinct().size==rows.size)}
        v.economicDraftShareRows.groupBy {it.draftId}.values.forEach {rows->require(rows.size<=100 && rows.all {it.position>=0} && rows.map {it.position}.distinct().size==rows.size)}
        require(v.controlRows.size <= 1 && v.controlRows.all { it.id == 1 && it.restoreEpoch >= 0 })
    }
}
