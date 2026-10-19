package com.qq.closie.life.finance

import java.security.MessageDigest

data class FinanceRegisterRow(val entry: FinanceEntryEntity, val balanceMinor: Long?)
data class FinanceReconciliationResult(val observation: FinanceObservationEntity, val bookMinor: Long?,
    val differenceMinor: Long?, val fingerprint: String, val invalidated: Boolean)

/** Complete-account scans always precede filtering; the original inclusive anchor is unchanged. */
object FinanceAccountProjection {
    const val RECONCILIATION_ALGORITHM=2
    fun liabilitySignPending(s:FinanceSnapshot,account:FinanceAccountEntity):Boolean =
        account.kind==FinanceAccountKind.CREDIT && account.openingBalanceMinor>0 &&
            s.v16?.accountPolicyRows.orEmpty().none {it.accountId==account.id && it.voidedAt==null}

    fun register(s: FinanceSnapshot, accountId: String): List<FinanceRegisterRow> {
        val account = s.accounts.single { it.id == accountId }
        var balance = account.openingBalanceMinor.toBigInteger()
        return s.entries.filter { it.accountId == accountId && it.voidedAt == null }
            .sortedWith(compareBy<FinanceEntryEntity> { it.occurredAt }.thenBy { it.recordedAt }.thenBy { it.id })
            .map {
                if (it.occurredAt < account.balanceAnchorAt) FinanceRegisterRow(it, null)
                else {
                    balance += if (it.direction == FinanceDirection.INFLOW) it.amountMinor.toBigInteger() else -it.amountMinor.toBigInteger()
                    FinanceRegisterRow(it, balance.longValueExact())
                }
            }.asReversed()
    }

    /** Only the explicitly confirmed legacy opening sign changes. Canonical cash directions never do. */
    fun netContribution(s: FinanceSnapshot, account: FinanceAccountEntity): Long {
        val policy = s.v16?.accountPolicyRows.orEmpty().singleOrNull { it.accountId == account.id && it.voidedAt == null }
        val opening = account.openingBalanceMinor.toBigInteger().let {
            if (policy?.liabilityConvention == FinanceLiabilityConvention.POSITIVE_DEBT) -it else it
        }
        return s.entries.asSequence().filter {
            it.accountId == account.id && it.voidedAt == null && it.occurredAt >= account.balanceAnchorAt
        }.fold(opening) { total, entry ->
            total + if (entry.direction == FinanceDirection.INFLOW) entry.amountMinor.toBigInteger() else -entry.amountMinor.toBigInteger()
        }.longValueExact()
    }

    fun netByCurrency(s: FinanceSnapshot): Map<String, Long> {
        val policies = s.v16?.accountPolicyRows.orEmpty().filter { it.voidedAt == null }.associateBy { it.accountId }
        val included=s.accounts.filter {it.archivedAt==null && policies[it.id]?.includedInNet!=false}.associateBy {it.id}
        val values=included.mapValues {(_,account)->account.openingBalanceMinor.toBigInteger().let {
            if(policies[account.id]?.liabilityConvention==FinanceLiabilityConvention.POSITIVE_DEBT) -it else it
        }}.toMutableMap()
        // One complete entry scan, independent of the number of included accounts or UI pages.
        s.entries.forEach {entry->
            val account=included[entry.accountId]
            if(account!=null && entry.voidedAt==null && entry.occurredAt>=account.balanceAnchorAt) {
                values[account.id]=values.getValue(account.id)+if(entry.direction==FinanceDirection.INFLOW) entry.amountMinor.toBigInteger() else -entry.amountMinor.toBigInteger()
            }
        }
        return included.values.groupBy {it.currencyCode}.mapValues {(_,accounts)->exactSum(accounts.map {values.getValue(it.id).longValueExact()})}
    }

    private data class ScopedFact(val entry:FinanceEntryEntity,val effectiveAt:Long?)
    private data class ReconciliationScope(val account:FinanceAccountEntity,val anchorBasis:FinanceBalanceBasis?,val facts:List<ScopedFact>)

    private fun scope(s:FinanceSnapshot,observation:FinanceObservationEntity):ReconciliationScope {
        val account = s.accounts.single { it.id == observation.accountId }
        val timings = s.v16?.entryTimingRows.orEmpty().filter { it.voidedAt == null }.associateBy { it.entryId }
        val anchorBasis = s.v16?.accountPolicyRows.orEmpty().singleOrNull { it.accountId == account.id && it.voidedAt == null }?.anchorBasis
        val facts=s.entries.filter {it.accountId==account.id && it.voidedAt==null}.mapNotNull {entry->
            val time=if(observation.basis==FinanceBalanceBasis.LEGACY_CASH) entry.occurredAt else timings[entry.id]?.postedAt
            // No reliable posting bounds exist for unknown dates in this model. Cash/recorded time
            // and free-text source descriptions cannot prove exclusion, including before the anchor.
            if(time==null || time in account.balanceAnchorAt..observation.observedAt) ScopedFact(entry,time) else null
        }.sortedBy {it.entry.id}
        return ReconciliationScope(account,anchorBasis,facts)
    }

    private fun fingerprint(scope:ReconciliationScope,observation:FinanceObservationEntity):String {
        val account=scope.account
        val facts=scope.facts.map {
            listOf(it.entry.id,it.entry.amountMinor,it.entry.direction,it.effectiveAt)
            }
        val encoded = com.google.gson.Gson().toJson(listOf(RECONCILIATION_ALGORITHM, account.id, account.currencyCode,
            account.balanceAnchorAt, account.openingBalanceMinor,
            scope.anchorBasis.takeIf {observation.basis==FinanceBalanceBasis.KNOWN_POSTED},observation.basis,
            observation.observedAt,observation.amountMinor,observation.precision,facts))
        return MessageDigest.getInstance("SHA-256").digest(encoded.toByteArray()).joinToString("") { "%02x".format(it) }
    }
    fun fingerprint(s:FinanceSnapshot,observation:FinanceObservationEntity):String=fingerprint(scope(s,observation),observation)

    fun reconcile(s: FinanceSnapshot, observation: FinanceObservationEntity,
        saved: FinanceReconciliationEntity? = null): FinanceReconciliationResult {
        val scope=scope(s,observation);val account=scope.account
        val hash = fingerprint(scope, observation)
        val book = if (observation.observedAt < account.balanceAnchorAt ||
            (observation.basis == FinanceBalanceBasis.KNOWN_POSTED &&
                (scope.anchorBasis != FinanceBalanceBasis.KNOWN_POSTED || scope.facts.any {it.effectiveAt==null}))) null
        else exactSum(listOf(account.openingBalanceMinor) + scope.facts.map {if(it.entry.direction==FinanceDirection.INFLOW) it.entry.amountMinor else -it.entry.amountMinor})
        return FinanceReconciliationResult(observation, book, book?.let { Math.subtractExact(observation.amountMinor, it) },
            hash, saved != null && (saved.algorithmVersion != RECONCILIATION_ALGORITHM || saved.fingerprint != hash))
    }
}
