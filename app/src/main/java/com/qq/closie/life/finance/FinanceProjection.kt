package com.qq.closie.life.finance

import java.math.BigInteger
import java.time.LocalDate
import java.time.ZoneId

/** Read-only projections over one coherent Finance snapshot. No independently persisted balances. */
data class FinanceSnapshot(
    val accounts: List<FinanceAccountEntity> = emptyList(),
    val entries: List<FinanceEntryEntity> = emptyList(),
    val transfers: List<FinanceTransferEntity> = emptyList(),
    val categories: List<FinanceCategoryEntity> = emptyList(),
    val tags: List<FinanceTagEntity> = emptyList(),
    val entryTags: List<FinanceEntryTagCrossRef> = emptyList(),
    val v4: FinanceV4Snapshot? = null
)
data class FinanceDateRange(val startInclusive: Long? = null, val endExclusive: Long? = null) {
    init { require(startInclusive == null || endExclusive == null || startInclusive < endExclusive) { "起止日期顺序有误" } }
    fun contains(time: Long) = (startInclusive == null || time >= startInclusive) && (endExclusive == null || time < endExclusive)
    companion object {
        fun days(start: LocalDate, endInclusive: LocalDate, zone: ZoneId = ZoneId.systemDefault()) = FinanceDateRange(
            start.atStartOfDay(zone).toInstant().toEpochMilli(), endInclusive.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli())
        fun month(today: LocalDate = LocalDate.now(), zone: ZoneId = ZoneId.systemDefault()) =
            days(today.withDayOfMonth(1), today.withDayOfMonth(1).plusMonths(1).minusDays(1), zone)
        fun year(today: LocalDate = LocalDate.now(), zone: ZoneId = ZoneId.systemDefault()) =
            days(today.withDayOfYear(1), today.withDayOfYear(1).plusYears(1).minusDays(1), zone)
    }
}
data class FinanceLedgerRow(val entry: FinanceEntryEntity, val account: FinanceAccountEntity,
    val category: String?, val tags: List<String>, val transfer: FinanceTransferEntity? = null,
    val targetAccount: FinanceAccountEntity? = null) {
    val id get() = transfer?.id ?: entry.id
    val currencyCode get() = account.currencyCode
}
data class FinanceTotals(val expenseMinor: Long = 0, val incomeMinor: Long = 0)
data class FinanceDimensionTotal(val label: String, val currencyCode: String, val totals: FinanceTotals)

object FinanceProjection {
    /** Opening balance is the balance at the explicit balance anchor; backfills belong to history only. */
    fun balance(account: FinanceAccountEntity, entries: List<FinanceEntryEntity>): Long = exact(
        entries.filter { it.accountId == account.id && it.voidedAt == null && it.occurredAt >= account.balanceAnchorAt }
            .fold(BigInteger.valueOf(account.openingBalanceMinor)) { total, row ->
            val amount = BigInteger.valueOf(row.amountMinor)
            if (row.direction == FinanceDirection.INFLOW) total + amount else total - amount
        })
    fun ledger(snapshot: FinanceSnapshot, range: FinanceDateRange = FinanceDateRange(), search: String = ""): List<FinanceLedgerRow> {
        val accounts = snapshot.accounts.associateBy { it.id }
        val entries = snapshot.entries.associateBy { it.id }
        val categories = snapshot.categories.associate { it.id to categoryPath(snapshot.categories, it.id) }
        val tags = snapshot.tags.associate { it.id to it.name }
        val entryTags = snapshot.entryTags.groupBy { it.entryId }
        val legs = snapshot.transfers.flatMap { listOf(it.outflowEntryId, it.inflowEntryId) }.toSet()
        val rows = snapshot.entries.filter { it.voidedAt == null && it.id !in legs }.map { entry ->
            FinanceLedgerRow(entry, accounts.getValue(entry.accountId), categories[entry.categoryId],
                entryTags[entry.id].orEmpty().mapNotNull { tags[it.tagId] }.distinct().sorted())
        } + snapshot.transfers.filter { it.voidedAt == null }.mapNotNull { transfer ->
            val out = entries[transfer.outflowEntryId] ?: return@mapNotNull null
            val inflow = entries[transfer.inflowEntryId] ?: return@mapNotNull null
            if (out.voidedAt != null || inflow.voidedAt != null) return@mapNotNull null
            FinanceLedgerRow(out, accounts.getValue(out.accountId), null, emptyList(), transfer, accounts.getValue(inflow.accountId))
        }
        val query = search.trim()
        return rows.filter { row -> range.contains(row.entry.occurredAt) && (query.isEmpty() ||
            listOfNotNull(row.entry.description, row.category, row.account.name, row.account.kind.label, row.targetAccount?.name)
                .plus(row.tags).any { it.contains(query, ignoreCase = true) }) }
            .sortedWith(compareByDescending<FinanceLedgerRow> { it.entry.occurredAt }
                .thenByDescending { it.entry.recordedAt }.thenBy { it.id })
    }
    fun categoryPath(categories: List<FinanceCategoryEntity>, id: String?): String? {
        val leaf = categories.find { it.id == id } ?: return null
        val parent = categories.find { it.id == leaf.parentId }
        return if (parent == null) leaf.name else parent.name + " / " + leaf.name
    }
    /** F1 ordinary flows only; F2 relationships will refine personal-consumption/income projections. */
    fun totals(rows: List<FinanceLedgerRow>): Map<String, FinanceTotals> = rows.filter { it.transfer == null && it.entry.statPolicy != FinanceStatPolicy.EXCLUDE }
        .groupBy { it.currencyCode }.mapValues { (_, values) -> FinanceTotals(
            sum(values.filter { it.entry.direction == FinanceDirection.OUTFLOW }),
            sum(values.filter { it.entry.direction == FinanceDirection.INFLOW })) }
    fun categoryTotals(rows: List<FinanceLedgerRow>): List<FinanceDimensionTotal> = dimensions(rows) { listOf(it.category ?: "未分类") }
    fun accountTotals(rows: List<FinanceLedgerRow>): List<FinanceDimensionTotal> = dimensions(rows) { listOf(it.account.name) }
    fun tagTotals(rows: List<FinanceLedgerRow>): List<FinanceDimensionTotal> = dimensions(rows) { it.tags }
    private fun dimensions(rows: List<FinanceLedgerRow>, names: (FinanceLedgerRow) -> List<String>): List<FinanceDimensionTotal> =
        rows.filter { it.transfer == null && it.entry.statPolicy != FinanceStatPolicy.EXCLUDE }.flatMap { row -> names(row).map { (it to row.currencyCode) to row } }
            .groupBy({ it.first }, { it.second }).map { (key, values) -> FinanceDimensionTotal(key.first, key.second,
                totals(values).getValue(key.second)) }.sortedBy { it.label }
    private fun sum(rows: List<FinanceLedgerRow>) = exact(rows.fold(BigInteger.ZERO) { total, row -> total + BigInteger.valueOf(row.entry.amountMinor) })
    private fun exact(value: BigInteger): Long = try { value.longValueExact() }
        catch (_: ArithmeticException) { throw IllegalArgumentException("余额或统计金额超出可支持范围") }
}
