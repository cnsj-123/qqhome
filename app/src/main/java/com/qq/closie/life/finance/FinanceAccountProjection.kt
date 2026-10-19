package com.qq.closie.life.finance

import java.security.MessageDigest

data class FinanceRegisterRow(val entry: FinanceEntryEntity, val balanceMinor: Long?)
data class FinanceReconciliationResult(val observation: FinanceObservationEntity, val bookMinor: Long?,
    val differenceMinor: Long?, val fingerprint: String, val invalidated: Boolean)

/** Complete-account scans always precede filtering; the original inclusive anchor is unchanged. */
object FinanceAccountProjection {
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

    fun fingerprint(s: FinanceSnapshot, observation: FinanceObservationEntity): String {
        val account = s.accounts.single { it.id == observation.accountId }
        val timings = s.v16?.entryTimingRows.orEmpty().filter { it.voidedAt == null }.associateBy { it.entryId }
        val anchorBasis = s.v16?.accountPolicyRows.orEmpty().singleOrNull { it.accountId == account.id && it.voidedAt == null }?.anchorBasis
        val facts = s.entries.filter { it.accountId == account.id && it.voidedAt == null }
            .sortedBy { it.id }.map {
                // Include membership outside the old conclusion too: a new/backfilled fact invalidates it.
                listOf(it.id, it.amountMinor, it.direction, it.occurredAt, it.recordedAt,
                    if (observation.basis == FinanceBalanceBasis.KNOWN_POSTED) timings[it.id]?.postedAt else null)
            }
        val encoded = com.google.gson.Gson().toJson(listOf(1, account.id, account.currencyCode,
            account.balanceAnchorAt, account.openingBalanceMinor, anchorBasis, observation.basis, observation.observedAt, facts))
        return MessageDigest.getInstance("SHA-256").digest(encoded.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    fun reconcile(s: FinanceSnapshot, observation: FinanceObservationEntity,
        saved: FinanceReconciliationEntity? = null): FinanceReconciliationResult {
        val account = s.accounts.single { it.id == observation.accountId }
        val active = s.entries.filter { it.accountId == account.id && it.voidedAt == null }
        val timings = s.v16?.entryTimingRows.orEmpty().filter { it.voidedAt == null }.associateBy { it.entryId }
        val anchorBasis = s.v16?.accountPolicyRows.orEmpty().singleOrNull { it.accountId == account.id && it.voidedAt == null }?.anchorBasis
        val hash = fingerprint(s, observation)
        val book = if (observation.observedAt < account.balanceAnchorAt ||
            (observation.basis == FinanceBalanceBasis.KNOWN_POSTED &&
                (anchorBasis != FinanceBalanceBasis.KNOWN_POSTED || active.any { timings[it.id]?.postedAt == null }))) null
        else exactSum(listOf(account.openingBalanceMinor) + active.filter {
            val time = if (observation.basis == FinanceBalanceBasis.LEGACY_CASH) it.occurredAt else requireNotNull(timings[it.id]?.postedAt)
            time >= account.balanceAnchorAt && time <= observation.observedAt
        }.map { if (it.direction == FinanceDirection.INFLOW) it.amountMinor else -it.amountMinor })
        return FinanceReconciliationResult(observation, book, book?.let { Math.subtractExact(observation.amountMinor, it) },
            hash, saved != null && (saved.algorithmVersion != 1 || saved.fingerprint != hash))
    }
}
