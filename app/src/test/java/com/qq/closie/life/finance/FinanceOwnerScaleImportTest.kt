package com.qq.closie.life.finance

import com.google.gson.GsonBuilder
import java.io.File
import java.math.BigDecimal
import java.time.LocalDate
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Deterministic synthetic owner-scale input, never the owner's private workbook. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FinanceOwnerScaleImportTest : FinanceRoomTest() {
    @get:Rule val files = TemporaryFolder()

    @Test fun fourThousandPlusRowsAcrossTwentyAccountsPreserveBalancesRelationsAndUnresolvedDates() = runTest(timeout = 10.minutes) {
        val report = linkedMapOf<String, Any>("status" to "FAIL", "sourceRows" to 4_332, "sourceAccounts" to 20,
            "fixture" to "deterministically generated from FinanceOwnerScaleImportTest", "commitSha" to (System.getenv("GITHUB_SHA") ?: "LOCAL_UNVERIFIED"),
            "environment" to "Robolectric JVM; durations are not Android device/frame measurements")
        val started = System.nanoTime()
        try {
            val balances = mutableMapOf<String, Long>(); var personalCost = 0L
            val rows = (1..4_332).map { index ->
                val account = "合成规模账户${index % 20}"
                val minor = 100L + index % 1_000
                val missingDate = index % 361 == 0
                val income = index % 17 == 0
                if (!missingDate) {
                    balances[account] = (balances[account] ?: 0) + if (income) minor else -minor
                    if (!income) personalCost += minor
                }
                SyntheticLegacyLedger.row("账户" to account, "金额" to BigDecimal.valueOf(minor, 2).toPlainString(),
                    "类型" to if (income) "收入" else "支出", "分类" to if (income) "合成收入" else "合成消费",
                    "子分类" to if (income) "" else "合成子类", "标签" to "规模验收",
                    "日期" to if (missingDate) "" else "${LocalDate.of(2025, 1, 1).plusDays((index % 365).toLong())} 12:00:00",
                    "备注" to "合成规模唯一记录$index")
            }
            val source = files.newFile("synthetic-4332-20-accounts.csv")
            SyntheticLegacyLedger.csv(source, rows)
            val imports = FinanceImportRepository(db, finance)
            val stageAt = System.nanoTime(); val preview = imports.stage(source, source.name)
            report["stageNanos"] = System.nanoTime() - stageAt
            assertEquals(4_332, preview.rows.size)
            val known = preview.rows.filter { it.status == FinanceRowStatus.READY }
            val unresolved = preview.rows.filter { it.occurredAt == null }
            assertEquals(4_320, known.size); assertEquals(12, unresolved.size)
            assertTrue("Missing dates must remain individually blocked, with their original null date", unresolved.all {
                it.status == FinanceRowStatus.INVALID && it.requiresIndividualReview && it.issue.isNotBlank() && it.canonicalId == null
            })
            val mappings = preview.rows.map { it.sourceAccount }.distinct().map { FinanceAccountMapping(it, fromHistory = true) }
            assertEquals(20, mappings.size)
            val commitAt = System.nanoTime()
            assertEquals(4_320, imports.commit(preview.batch.id, mappings, known.map { it.id }.toSet(), emptySet(), false, false))
            report["commitNanos"] = System.nanoTime() - commitAt
            val projectionAt = System.nanoTime(); val snapshot = finance.portableSnapshot()
            assertEquals(20, snapshot.accounts.size); assertEquals(4_320, snapshot.entries.size)
            snapshot.accounts.forEach { account ->
                assertEquals(0L, account.openingBalanceMinor)
                assertEquals(balances.getValue(account.name), FinanceProjection.balance(account, snapshot.entries))
                assertEquals(snapshot.entries.count { it.accountId == account.id }, FinanceAccountProjection.register(snapshot, account.id).size)
            }
            assertEquals(4_320, snapshot.v4!!.activeLinks.size)
            assertEquals(snapshot.entries.map { it.id }.toSet(), snapshot.v4!!.activeLinks.map { it.entryId }.toSet())
            assertEquals(PersonalCostTotal(personalCost, 0), FinanceCostProjection.total(FinanceCostProjection.rows(snapshot)))
            assertEquals(balances.values.sum(), FinanceAccountProjection.netByCurrency(snapshot).getValue("CNY"))
            val after = imports.preview(preview.batch.id, mappings)
            assertTrue(after.rows.filter { it.id in unresolved.map { row -> row.id } }.all {
                it.occurredAt == null && it.canonicalId == null && it.status == FinanceRowStatus.INVALID && it.requiresIndividualReview
            })
            report["projectionNanos"] = System.nanoTime() - projectionAt
            assertEquals(0, imports.commit(preview.batch.id, mappings, known.map { it.id }.toSet(), emptySet(), false, false))
            assertEquals(preview.batch.id, imports.stage(source, "renamed-4332.csv").batch.id)
            assertEquals(snapshot, finance.portableSnapshot())
            FinanceIntegrity.validate(snapshot)
            report["canonicalEntries"] = 4_320; report["unresolvedRows"] = 12
            report["confirmedPersonalCostMinor"] = personalCost; report["netMinor"] = balances.values.sum()
            report["status"] = "PASS"
        } catch (failure: Throwable) { report["failureType"] = failure.javaClass.name; throw failure }
        finally {
            report["totalNanos"] = System.nanoTime() - started
            val directory = File(requireNotNull(System.getProperty("lifeos.v16.metricsDir")))
            check(directory.isDirectory || directory.mkdirs())
            File(directory, "synthetic-history-4332-20-accounts.json").writeText(GsonBuilder().setPrettyPrinting().create().toJson(report))
        }
    }
}
