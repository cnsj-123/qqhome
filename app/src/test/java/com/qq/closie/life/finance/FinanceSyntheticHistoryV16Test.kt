package com.qq.closie.life.finance

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import com.qq.closie.ui.lifeos.finance.FinanceRegisterFilter
import java.io.File
import java.math.BigInteger
import java.security.MessageDigest
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Reads the committed fixture and uses the actual staging, commit, DAO and cost projections. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class FinanceSyntheticHistoryV16Test:FinanceRoomTest() {
    @get:Rule val files=TemporaryFolder()
    @Test fun committedTwoThousandRowsImportBalancesCostsNetFiltersAndRepeatAreConsistent()=runTest(timeout=10.minutes) {
        val report=linkedMapOf<String,Any>("status" to "FAIL", "fixture" to "synthetic-history-2000.csv",
            "commitSha" to (System.getenv("GITHUB_SHA") ?: "LOCAL_UNVERIFIED"),
            "processors" to Runtime.getRuntime().availableProcessors(),"memoryMetric" to "JVM used heap samples; not peak or Android frame metrics")
        val start=System.nanoTime()
        val runtime=Runtime.getRuntime()
        report["heapBeforeBytes"]=runtime.totalMemory()-runtime.freeMemory()
        try {
            val root=File(requireNotNull(System.getProperty("lifeos.v16.fixtureDir")){"Gradle must provide the committed fixture directory"})
            val fixture=File(root,"synthetic-history-2000.csv")
            assertTrue("Missing committed fixture",fixture.isFile)
            val manifest=JsonParser.parseString(File(root,"manifest.json").readText()).asJsonObject
            val fileSpec=manifest.getAsJsonArray("files").single {it.asJsonObject.get("file").asString==fixture.name}.asJsonObject
            val hash=MessageDigest.getInstance("SHA-256").digest(fixture.readBytes()).joinToString("") {"%02x".format(it)}
            assertEquals(fileSpec.get("sha256").asString,hash)
            val expected=manifest.getAsJsonObject("historyExpectedAfterFullImportWithZeroOpening")
            val count=expected.get("activeEntries").asInt;assertEquals(2_000,count)
            val imports=FinanceImportRepository(db,finance)
            val stagedAt=System.nanoTime()
            val preview=imports.stage(fixture,fixture.name)
            report["stageNanos"]=System.nanoTime()-stagedAt
            assertEquals(count,preview.rows.size)
            assertTrue(preview.rows.all {it.status==FinanceRowStatus.READY})
            assertTrue(finance.snapshot().entries.isEmpty())
            val mappings=preview.rows.map {it.sourceAccount}.distinct().map {FinanceAccountMapping(it,fromHistory=true)}
            val commitAt=System.nanoTime()
            assertEquals(count,imports.commit(preview.batch.id,mappings,preview.rows.map {it.id}.toSet(),emptySet(),false,false))
            report["commitNanos"]=System.nanoTime()-commitAt
            val s=finance.portableSnapshot()
            assertEquals(count,s.entries.count {it.voidedAt==null});assertEquals(2,s.accounts.size)
            assertEquals(count,db.financeDao().entries().size)
            val projectionAt=System.nanoTime()
            val cost=FinanceCostProjection.total(FinanceCostProjection.rows(s))
            assertTrue(cost.complete)
            assertEquals(expected.get("confirmedPersonalCostMinor").asLong,cost.confirmedMinor)
            assertEquals(mapOf("CNY" to expected.get("netMinor").asLong),FinanceAccountProjection.netByCurrency(s))
            s.accounts.forEach {account->
                val amount=expected.getAsJsonObject("accountBalancesMinor").get(account.name).asLong
                assertEquals(0L,account.openingBalanceMinor)
                assertEquals(amount,FinanceProjection.balance(account,s.entries))
                val oracle=mutableMapOf<String,Long>();var running=BigInteger.ZERO
                s.entries.filter {it.accountId==account.id && it.voidedAt==null}
                    .sortedWith(compareBy<FinanceEntryEntity> {it.occurredAt}.thenBy {it.recordedAt}.thenBy {it.id}).forEach {entry->
                        running+=if(entry.direction==FinanceDirection.INFLOW) entry.amountMinor.toBigInteger() else -entry.amountMinor.toBigInteger()
                        oracle[entry.id]=running.longValueExact()
                    }
                val register=FinanceAccountProjection.register(s,account.id)
                assertEquals(oracle.keys,register.map {it.entry.id}.toSet())
                register.forEach {assertEquals(oracle.getValue(it.entry.id),it.balanceMinor)}
                val filtered=FinanceRegisterFilter(query="合成压力记录001",direction="OUTFLOW").apply(register,s)
                assertFalse(filtered.isEmpty())
                filtered.forEach {assertEquals(oracle.getValue(it.entry.id),it.balanceMinor)}
                assertEquals(oracle.size,register.size)
            }
            val query=FinanceQueryEngine.execute(s,FinanceQuerySpec(text="合成压力记录001",currencyCode="CNY"))
            assertEquals(10,query.costs.size)
            val selected=s.entries.filter {it.description.startsWith("合成压力记录001")}
            assertEquals(selected.sumOf {it.amountMinor},query.totals.getValue("CNY").confirmedMinor)
            report["projectionAndFilterNanos"]=System.nanoTime()-projectionAt
            assertEquals(0,imports.commit(preview.batch.id,mappings,preview.rows.map {it.id}.toSet(),emptySet(),false,false))
            assertEquals(preview.batch.id,imports.stage(fixture,"renamed.csv").batch.id)
            assertEquals(s,finance.portableSnapshot())
            val copy=files.newFile("same-records-crlf.csv")
            copy.writeText(fixture.readText().replace("\n","\r\n"))
            val repeated=imports.stage(copy,copy.name)
            assertEquals(count,repeated.rows.size)
            assertTrue(repeated.rows.all {it.status==FinanceRowStatus.DUPLICATE})
            assertEquals(s.entries,finance.snapshot().entries)
            FinanceIntegrity.validate(finance.portableSnapshot())
            report["activeEntries"]=count;report["netMinor"]=expected.get("netMinor").asLong
            report["status"]="PASS"
        } catch(error:Throwable) {report["failureType"]=error.javaClass.name;throw error}
        finally {
            report["totalNanos"]=System.nanoTime()-start
            report["heapAfterBytes"]=runtime.totalMemory()-runtime.freeMemory()
            val dir=File(requireNotNull(System.getProperty("lifeos.v16.metricsDir")));check(dir.isDirectory || dir.mkdirs())
            File(dir,"synthetic-history-2000.json").writeText(GsonBuilder().setPrettyPrinting().create().toJson(report))
        }
    }
}
