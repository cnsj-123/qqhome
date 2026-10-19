package com.qq.closie.life.finance

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.gson.JsonParser
import com.qq.closie.data.backup.*
import com.qq.closie.data.repository.LocalWardrobeRepository
import com.qq.closie.life.data.database.LifeDatabase
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.util.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** All date facts are confirmed through the actual repository and persisted by Room. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class FinanceCivilDateV16Test:FinanceRoomTest() {
    @get:Rule val files=TemporaryFolder()
    private val day=LocalDate.of(2026,10,10)
    private fun token()=FinanceCommandToken(java.util.UUID.randomUUID().toString(),0)
    private suspend fun seed(zone:ZoneId,date:LocalDate=day,kind:FinanceAccountKind=FinanceAccountKind.BANK_CARD):Pair<FinanceAccountEntity,FinanceEntryEntity> {
        clock=date.plusDays(5).atStartOfDay(zone).toInstant().toEpochMilli()
        val a=finance.saveAccount(name="合成日期账户",kind=kind,openingBalanceMinor=10_000,
            balanceAnchorAt=date.minusDays(2).atStartOfDay(zone).toInstant().toEpochMilli())
        finance.workspace.setAccountPolicy(token(),a.id,true,FinanceLiabilityConvention.SIGNED,FinanceBalanceBasis.KNOWN_POSTED,null)
        val entry=finance.saveEntry(accountId=a.id,direction=FinanceDirection.OUTFLOW,amountMinor=100,description="合成日期付款",
            occurredAt=date.plusDays(3).atTime(12,0).atZone(zone).toInstant().toEpochMilli())
        val civil=FinanceCivilDate.capture(date,zone)
        finance.workspace.setPostingDate(token(),entry.id,entry.revision,civil.startInclusive,FinanceTimePrecision.DATE,"合成日期凭据",civil)
        return a to entry
    }
    private suspend fun observe(a:String,civil:FinanceCivilDate,precision:FinanceTimePrecision=FinanceTimePrecision.DATE,at:Long=civil.startInclusive):FinanceObservationEntity {
        val id=finance.workspace.saveObservation(token(),a,9_900,at,FinanceBalanceBasis.KNOWN_POSTED,precision,"合成观察",
            civil.takeIf {precision==FinanceTimePrecision.DATE})
        return finance.snapshot().v16!!.observationRows.single {it.id==id}
    }
    private suspend fun switches(from:String,to:String) {
        val original=TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone(from))
            val zone=ZoneId.systemDefault();val (a,entry)=seed(zone)
            val late=from=="America/Los_Angeles"
            val civil=FinanceCivilDate.capture(day,zone)
            if(late) finance.workspace.setPostingDate(token(),entry.id,finance.snapshot().entries.single().revision,
                day.atTime(23,30).atZone(zone).toInstant().toEpochMilli(),FinanceTimePrecision.DATE,"合成原始日历日",civil)
            val observationDay=FinanceCivilDate.capture(day.plusDays(1),zone)
            val o=observe(a.id,observationDay,at=if(late) day.plusDays(1).atTime(23,30).atZone(zone).toInstant().toEpochMilli() else observationDay.startInclusive)
            var s=finance.snapshot();val first=FinanceAccountProjection.reconcile(s,o)
            assertEquals(9_900L,first.bookMinor)
            finance.workspace.confirmReconciliation(token(),o.id,first.fingerprint,"")
            s=finance.snapshot();val saved=s.v16!!.reconciliationRows.single()
            val before=s.v16!!.entryTimingRows.single()
            val wronglyReinterpreted=java.time.Instant.ofEpochMilli(before.postedAt!!).atZone(ZoneId.of(to)).toLocalDate()
            assertEquals(if(late) day.plusDays(1) else day.minusDays(1),wronglyReinterpreted)
            TimeZone.setDefault(TimeZone.getTimeZone(to))
            val after=FinanceRepository(db){clock}.snapshot()
            val posted=after.v16!!.entryTimingRows.single();val observation=after.v16!!.observationRows.single()
            assertEquals(before,posted);assertEquals("2026-10-10",posted.postedLocalDate)
            assertEquals(from,posted.postedZoneId);assertEquals("2026-10-11",observation.observedLocalDate)
            assertTrue(posted.postedTimeLabel().contains("2026-10-10"));assertTrue(observation.timeLabel().contains("2026-10-11"))
            assertEquals(entry.occurredAt,after.entries.single().occurredAt)
            assertEquals(entry.recordedAt,after.entries.single().recordedAt)
            assertEquals(a.balanceAnchorAt,after.accounts.single().balanceAnchorAt)
            assertEquals(9_900L,FinanceProjection.balance(after.accounts.single(),after.entries))
            val result=FinanceAccountProjection.reconcile(after,observation,saved)
            assertEquals(first.fingerprint,result.fingerprint);assertEquals(9_900L,result.bookMinor);assertFalse(result.invalidated)
            assertEquals(0L,result.differenceMinor);assertEquals(FinanceTrust.RECONCILED,FinanceAccountStatusProjection.status(after,a.id).trust)
        } finally {TimeZone.setDefault(original)}
    }
    @Test fun tokyoDateSurvivesLosAngelesDeviceZoneAndRepositoryRecreation()=runTest {switches("Asia/Tokyo","America/Los_Angeles")}
    @Test fun losAngelesDateSurvivesTokyoDeviceZoneAndRepositoryRecreation()=runTest {switches("America/Los_Angeles","Asia/Tokyo")}

    @Test fun daylightSavingDaysPersistTwentyThreeAndTwentyFiveHoursAndCannotProveNoonPosting()=runTest {
        val zone=ZoneId.of("America/Los_Angeles")
        for((date,hours) in listOf(LocalDate.of(2026,3,8) to 23L,LocalDate.of(2026,11,1) to 25L)) {
            val (a,entry)=seed(zone,date);val civil=FinanceCivilDate.capture(date,zone)
            val actual=finance.snapshot().v16!!.entryTimingRows.single {it.entryId==entry.id}.postedCivilDate()!!
            assertEquals(hours*3_600_000L,actual.endExclusive-actual.startInclusive)
            val noon=date.atTime(12,0).atZone(zone).toInstant().toEpochMilli()
            val o=observe(a.id,civil,FinanceTimePrecision.INSTANT,noon)
            val uncertain=FinanceAccountProjection.reconcile(finance.snapshot(),o)
            assertNull(uncertain.bookMinor);assertNull(uncertain.differenceMinor)
            finance.workspace.confirmReconciliation(token(),o.id,uncertain.fingerprint,"")
            assertEquals(FinanceTrust.INCOMPLETE,FinanceAccountStatusProjection.status(finance.snapshot(),a.id).trust)
            val next=observe(a.id,FinanceCivilDate.capture(date.plusDays(1),zone),FinanceTimePrecision.INSTANT,civil.endExclusive)
            assertEquals(9_900L,FinanceAccountProjection.reconcile(finance.snapshot(),next).bookMinor)
        }
    }

    @Test fun legacyDateHasNoGuessedZoneAndOnlyExplicitOutsideEvidenceCanExcludeIt()=runTest {
        val zone=ZoneId.of("Asia/Tokyo");val (a,entry)=seed(zone)
        val o=observe(a.id,FinanceCivilDate.capture(day.plusDays(1),zone),FinanceTimePrecision.INSTANT)
        val prior=finance.snapshot().v16!!.entryTimingRows.single()
        // Exact old row shape: epoch exists, original civil date and zone were never recorded.
        val legacy=prior.copy(postedAt=a.balanceAnchorAt-1,postedLocalDate=null,postedZoneId=null,
            postedDayStartAt=null,postedDayEndExclusive=null,postedDateBasisVersion=null)
        db.financeV16Dao().putEntryTiming(listOf(legacy))
        assertNull(FinanceAccountProjection.reconcile(finance.snapshot(),o).bookMinor)
        assertNull(finance.snapshot().v16!!.entryTimingRows.single().postedCivilDate())
        val explicit=FinanceCivilDate.capture(day.minusDays(3),zone)
        finance.workspace.setPostingDate(token(),entry.id,finance.snapshot().entries.single().revision,
            explicit.startInclusive,FinanceTimePrecision.DATE,"明确范围外",explicit)
        assertEquals(10_000L,FinanceAccountProjection.reconcile(finance.snapshot(),o).bookMinor)
        finance.saveEntry(accountId=a.id,direction=FinanceDirection.OUTFLOW,amountMinor=50,description="未知入账",occurredAt=clock+1)
        assertNull(FinanceAccountProjection.reconcile(finance.snapshot(),o).bookMinor)
    }

    @Test fun explicitZoneBasisChangeInvalidatesEvenWithIdenticalBoundsAndInvalidEvidenceRollsBack()=runTest {
        val (a,entry)=seed(ZoneId.of("UTC"))
        val o=observe(a.id,FinanceCivilDate.capture(day.plusDays(1),ZoneId.of("UTC")),FinanceTimePrecision.INSTANT)
        val before=finance.snapshot();val initial=FinanceAccountProjection.reconcile(before,o)
        finance.workspace.confirmReconciliation(token(),o.id,initial.fingerprint,"")
        val saved=finance.snapshot().v16!!.reconciliationRows.single()
        val alternate=FinanceCivilDate.capture(day,ZoneId.of("Etc/UTC"))
        assertEquals(before.v16!!.entryTimingRows.single().postedDayStartAt,alternate.startInclusive)
        finance.workspace.setPostingDate(token(),entry.id,finance.snapshot().entries.single().revision,
            alternate.startInclusive,FinanceTimePrecision.DATE,"明确更正时区依据",alternate)
        val after=finance.snapshot();val result=FinanceAccountProjection.reconcile(after,o,saved)
        assertEquals(9_900L,result.bookMinor);assertTrue(result.invalidated)
        assertNotEquals(initial.fingerprint,result.fingerprint)
        assertTrue(runCatching {finance.workspace.setPostingDate(token(),entry.id,after.entries.single().revision,
            alternate.startInclusive,FinanceTimePrecision.DATE,"错误版本",alternate.copy(basisVersion=99))}.isFailure)
        assertEquals(after,finance.snapshot())
        assertTrue(runCatching {finance.workspace.setPostingDate(token(),entry.id,after.entries.single().revision,
            alternate.startInclusive,FinanceTimePrecision.DATE,"没有日期依据")}.isFailure)
        assertEquals(after,finance.snapshot())
        val observationDay=FinanceCivilDate.capture(day.plusDays(1),ZoneId.of("UTC"))
        val first=observe(a.id,observationDay)
        val last=observe(a.id,observationDay,at=observationDay.startInclusive+3_600_000L)
        assertNull(FinanceAccountStatusProjection.compare(finance.snapshot(),first,last).bookMovementMinor)
    }

    @Test fun integerControlIdentityStillRejectsDuplicatesAndWrongSingletonKey()=runTest {
        account();val s=finance.snapshot();val v=s.v16!!
        assertTrue(runCatching {FinanceIntegrity.validate(s.copy(v16=v.copy(controlRows=listOf(FinanceControlEntity(0,0)))))}.isFailure)
        assertTrue(runCatching {FinanceIntegrity.validate(s.copy(v16=v.copy(controlRows=listOf(FinanceControlEntity(1,0),FinanceControlEntity(1,0)))))}.isFailure)
        FinanceIntegrity.validate(s)
    }

    @Test fun postedFiltersAndStatementsAlsoRequirePersistedWholeDateIntervals()=runTest {
        val (a,entry)=seed(ZoneId.of("Asia/Tokyo"),kind=FinanceAccountKind.CREDIT)
        finance.workspace.saveCreditCycle(token(),a.id,31,10,"Asia/Tokyo")
        var s=finance.snapshot();val civil=s.v16!!.entryTimingRows.single().postedCivilDate()!!
        val all=FinanceAccountProjection.register(s,a.id)
        val whole=com.qq.closie.ui.lifeos.finance.FinanceRegisterFilter(after=civil.startInclusive-1,through=civil.endExclusive-1,posted=true)
        assertEquals(listOf(entry.id),whole.apply(all,s).map {it.entry.id})
        assertTrue(whole.copy(through=civil.startInclusive+3_600_000).apply(all,s).isEmpty())
        assertEquals(100L,FinanceAccountStatusProjection.statement(s,a.id,java.time.YearMonth.of(2026,10)).outflowMinor)
        val timing=s.v16!!.entryTimingRows.single()
        db.financeV16Dao().putEntryTiming(listOf(timing.copy(postedLocalDate=null,postedZoneId=null,postedDayStartAt=null,
            postedDayEndExclusive=null,postedDateBasisVersion=null)))
        s=finance.snapshot();assertTrue(whole.apply(all,s).isEmpty())
        val statement=FinanceAccountStatusProjection.statement(s,a.id,java.time.YearMonth.of(2026,10))
        assertEquals(0L,statement.outflowMinor);assertEquals(1,statement.unknownPostingCount)
    }

    @Test fun contradictoryCalendarDateEvenWithEpochInsideAndMalformedGroupsAreRejectedAtomically()=runTest {
        val (a,entry)=seed(ZoneId.of("Asia/Tokyo"))
        val observation=observe(a.id,FinanceCivilDate.capture(day,ZoneId.of("Asia/Tokyo")))
        val before=finance.snapshot();val v=before.v16!!;val row=v.entryTimingRows.single()
        val valid=row.postedCivilDate()!!
        assertTrue(row.postedAt!!>=valid.startInclusive && row.postedAt!!<valid.endExclusive)
        val malformed=listOf(
            row.copy(postedLocalDate="2026-12-31"), // T01/T03: at is still INSIDE the forged October range.
            row.copy(postedLocalDate=null),row.copy(postedZoneId=null),row.copy(postedDayStartAt=null),
            row.copy(postedDayEndExclusive=null),row.copy(postedDateBasisVersion=null),
            row.copy(postedLocalDate="2026-02-30"),row.copy(postedLocalDate="2026-1-1"),
            row.copy(postedZoneId="Invalid/Zone"),row.copy(postedDateBasisVersion=99),
            row.copy(postedDayEndExclusive=row.postedDayStartAt),
            row.copy(postedDayStartAt=Long.MIN_VALUE,postedDayEndExclusive=Long.MAX_VALUE))
        for(bad in malformed) {
            assertEquals(FinanceCivilDateTrust.INVALID,FinanceCivilDate.assess(bad.postedLocalDate,bad.postedZoneId,
                bad.postedDayStartAt,bad.postedDayEndExclusive,bad.postedDateBasisVersion).trust)
            assertThrows(IllegalArgumentException::class.java) {FinanceIntegrity.validate(before.copy(v16=v.copy(entryTimingRows=listOf(bad))))}
            val badObservation=observation.copy(observedLocalDate=bad.postedLocalDate,observedZoneId=bad.postedZoneId,
                observedDayStartAt=bad.postedDayStartAt,observedDayEndExclusive=bad.postedDayEndExclusive,
                observedDateBasisVersion=bad.postedDateBasisVersion)
            assertThrows(IllegalArgumentException::class.java) {FinanceIntegrity.validate(before.copy(v16=v.copy(observationRows=listOf(badObservation))))}
        }
        val forged=valid.copy(localDate="2026-12-31")
        assertTrue(runCatching {finance.workspace.setPostingDate(token(),entry.id,before.entries.single().revision,
            row.postedAt,FinanceTimePrecision.DATE,"合成矛盾日期",forged)}.isFailure)
        assertTrue(runCatching {finance.workspace.saveObservation(token(),a.id,9_900,row.postedAt!!,
            FinanceBalanceBasis.KNOWN_POSTED,FinanceTimePrecision.DATE,"合成矛盾观察",forged)}.isFailure)
        assertEquals(before,finance.snapshot())
    }

    @Test fun anotherZonesBoundsArePreservedButCannotDetermineMembershipOrBypassNewWriteValidation()=runTest {
        val (a,entry)=seed(ZoneId.of("Asia/Tokyo"),kind=FinanceAccountKind.CREDIT)
        finance.workspace.saveCreditCycle(token(),a.id,31,10,"Asia/Tokyo")
        val o=observe(a.id,FinanceCivilDate.capture(day.plusDays(1),ZoneId.of("Asia/Tokyo")),FinanceTimePrecision.INSTANT)
        val before=finance.snapshot();val row=before.v16!!.entryTimingRows.single()
        val wrong=row.postedCivilDate()!!.copy(zoneId="America/Los_Angeles")
        assertEquals(FinanceCivilDateTrust.HISTORICAL_UNVERIFIED,wrong.assessment().trust)
        assertTrue(runCatching {finance.workspace.setPostingDate(token(),entry.id,before.entries.single().revision,
            row.postedAt,FinanceTimePrecision.DATE,"合成错配时区",wrong)}.isFailure)
        assertEquals(before,finance.snapshot())
        val historical=row.copy(postedZoneId=wrong.zoneId)
        db.financeV16Dao().putEntryTiming(listOf(historical))
        val s=finance.snapshot();FinanceIntegrity.validate(s)
        assertEquals(historical,s.v16!!.entryTimingRows.single());assertNull(historical.postedTimeBounds())
        val result=FinanceAccountProjection.reconcile(s,o)
        assertNull(result.bookMinor);assertNull(result.differenceMinor)
        val all=FinanceAccountProjection.register(s,a.id)
        assertTrue(com.qq.closie.ui.lifeos.finance.FinanceRegisterFilter(after=0L,through=clock,posted=true).apply(all,s).isEmpty())
        val statement=FinanceAccountStatusProjection.statement(s,a.id,java.time.YearMonth.of(2026,10))
        assertEquals(0L,statement.outflowMinor);assertEquals(1,statement.unknownPostingCount)
    }

    @Test fun plausibleHistoricalRuleChangeCannotExcludeAnOutsideRowAndInvalidatesSavedSuccess()=runTest {
        val zone=ZoneId.of("Asia/Tokyo");val (a,entry)=seed(zone)
        val o=observe(a.id,FinanceCivilDate.capture(day.plusDays(1),zone),FinanceTimePrecision.INSTANT)
        val initial=FinanceAccountProjection.reconcile(finance.snapshot(),o)
        finance.workspace.confirmReconciliation(token(),o.id,initial.fingerprint,"")
        val confirmed=finance.snapshot();val saved=confirmed.v16!!.reconciliationRows.single()
        assertEquals(FinanceTrust.RECONCILED,FinanceAccountStatusProjection.status(confirmed,a.id).trust)
        assertTrue(FinanceAccountProjection.reconcile(confirmed,o,saved.copy(algorithmVersion=4)).invalidated)
        val row=confirmed.v16!!.entryTimingRows.single()
        for(date in listOf(day,day.plusDays(10))) {
            val current=FinanceCivilDate.capture(date,zone)
            // Simulates an unrecorded old tzdb rule with a 30-minute boundary difference.
            // This test supplies NO proof of which rules were historically used.
            val historical=row.copy(postedAt=current.startInclusive+1_800_000L,postedLocalDate=date.toString(),
                postedDayStartAt=current.startInclusive+1_800_000L,postedDayEndExclusive=current.endExclusive+1_800_000L)
            db.financeV16Dao().putEntryTiming(listOf(historical))
            val s=finance.snapshot();FinanceIntegrity.validate(s)
            assertEquals(FinanceCivilDateTrust.HISTORICAL_UNVERIFIED,historical.postedCivilDate()!!.assessment().trust)
            assertEquals(historical,s.v16!!.entryTimingRows.single())
            assertEquals(entry.occurredAt,s.entries.single().occurredAt);assertEquals(a.balanceAnchorAt,s.accounts.single().balanceAnchorAt)
            assertEquals(9_900L,FinanceProjection.balance(s.accounts.single(),s.entries))
            val result=FinanceAccountProjection.reconcile(s,o,saved)
            assertNull(result.bookMinor);assertNull(result.differenceMinor);assertTrue(result.invalidated)
            assertNotEquals(initial.fingerprint,result.fingerprint)
            assertEquals(FinanceTrust.INCOMPLETE,FinanceAccountStatusProjection.status(s,a.id).trust)
        }
        val corrected=FinanceCivilDate.capture(day,ZoneId.of("Asia/Seoul"))
        finance.workspace.setPostingDate(token(),entry.id,finance.snapshot().entries.single().revision,
            corrected.startInclusive,FinanceTimePrecision.DATE,"用户明确补正依据",corrected)
        val repaired=FinanceAccountProjection.reconcile(finance.snapshot(),o,saved)
        assertTrue(repaired.invalidated);assertEquals(9_900L,repaired.bookMinor);assertEquals(0L,repaired.differenceMinor)
    }

    @Test fun unverifiedObservationCannotProduceBookDifferenceOrIntervalAndInvalidatesSavedSuccess()=runTest {
        val zone=ZoneId.of("Asia/Tokyo");val (a,_)=seed(zone)
        val valid=observe(a.id,FinanceCivilDate.capture(day.plusDays(1),zone))
        val o=observe(a.id,FinanceCivilDate.capture(day.plusDays(2),zone))
        assertNotEquals(valid.id,o.id)
        val before=finance.snapshot()
        val first=FinanceAccountProjection.reconcile(finance.snapshot(),o)
        finance.workspace.confirmReconciliation(token(),o.id,first.fingerprint,"")
        val saved=finance.snapshot().v16!!.reconciliationRows.single()
        assertEquals(FinanceTrust.RECONCILED,FinanceAccountStatusProjection.status(finance.snapshot(),a.id).trust)
        val historical=o.copy(observedAt=o.observedAt+1_800_000L,observedDayStartAt=o.observedDayStartAt!!+1_800_000L,
            observedDayEndExclusive=o.observedDayEndExclusive!!+1_800_000L)
        db.financeV16Dao().putObservation(listOf(historical))
        val s=finance.snapshot();FinanceIntegrity.validate(s)
        assertEquals(setOf(valid.id,o.id),s.v16!!.observationRows.map {it.id}.toSet())
        val currentFirst=s.v16!!.observationRows.single {it.id==valid.id}
        val currentLast=s.v16!!.observationRows.single {it.id==o.id}
        assertEquals(valid,currentFirst);assertEquals(historical,currentLast)
        assertEquals(9_900L,FinanceAccountProjection.reconcile(s,currentFirst).bookMinor)
        val result=FinanceAccountProjection.reconcile(s,currentLast,saved)
        assertNull(result.bookMinor);assertNull(result.differenceMinor);assertTrue(result.invalidated)
        assertEquals(FinanceTrust.INCOMPLETE,FinanceAccountStatusProjection.status(s,a.id).trust)
        val interval=FinanceAccountStatusProjection.compare(s,currentFirst,currentLast)
        assertEquals(currentFirst,interval.first);assertEquals(currentLast,interval.last)
        assertEquals(0L,interval.observedMovementMinor)
        assertNull(interval.bookMovementMinor);assertNull(interval.unexplainedMinor)
        assertEquals(before.accounts,s.accounts);assertEquals(before.entries,s.entries)
        assertEquals(before.v16!!.entryTimingRows,s.v16!!.entryTimingRows)
        assertEquals(a.balanceAnchorAt,s.accounts.single().balanceAnchorAt)
        assertEquals(9_900L,FinanceProjection.balance(s.accounts.single(),s.entries))
    }

    @Test fun persistedDateLabelsDistinguishAllEvidenceClassesAndSavedAccountConfidence()=runTest {
        val zone=ZoneId.of("Asia/Tokyo");val (a,_)=seed(zone)
        val o=observe(a.id,FinanceCivilDate.capture(day.plusDays(1),zone))
        val first=FinanceAccountProjection.reconcile(finance.snapshot(),o)
        finance.workspace.confirmReconciliation(token(),o.id,first.fingerprint,"")
        val before=finance.snapshot();val original=before.v16!!.entryTimingRows.single()
        val invalid="日期资料无效（不能作为已确认日期）"
        val missing="日期资料不足（历史记录缺少原始日期/时区依据）"
        val labels=mapOf(
            FinanceCivilDateTrust.CONFIRMED to ("2026-10-10 · Asia/Tokyo（仅日期）" to "2026-10-11 · Asia/Tokyo（仅日期）"),
            FinanceCivilDateTrust.LEGACY_UNKNOWN to (missing to missing),
            FinanceCivilDateTrust.HISTORICAL_UNVERIFIED to (
                "2026-10-10 · Asia/Tokyo（历史日期参考；确认区间无法核验，资料不足）" to
                "2026-10-11 · Asia/Tokyo（历史日期参考；确认区间无法核验，资料不足）"),
            FinanceCivilDateTrust.INVALID to (invalid to invalid))
        for((trust,expected) in labels) {
            val posted=when(trust) {
                FinanceCivilDateTrust.CONFIRMED -> original
                FinanceCivilDateTrust.LEGACY_UNKNOWN -> original.copy(postedLocalDate=null,postedZoneId=null,
                    postedDayStartAt=null,postedDayEndExclusive=null,postedDateBasisVersion=null)
                FinanceCivilDateTrust.HISTORICAL_UNVERIFIED -> original.copy(postedAt=original.postedAt!!+1_800_000L,
                    postedDayStartAt=original.postedDayStartAt!!+1_800_000L,postedDayEndExclusive=original.postedDayEndExclusive!!+1_800_000L)
                FinanceCivilDateTrust.INVALID -> original.copy(postedLocalDate="2026-12-31")
            }
            val observed=when(trust) {
                FinanceCivilDateTrust.CONFIRMED -> o
                FinanceCivilDateTrust.LEGACY_UNKNOWN -> o.copy(observedLocalDate=null,observedZoneId=null,
                    observedDayStartAt=null,observedDayEndExclusive=null,observedDateBasisVersion=null)
                FinanceCivilDateTrust.HISTORICAL_UNVERIFIED -> o.copy(observedAt=o.observedAt+1_800_000L,
                    observedDayStartAt=o.observedDayStartAt!!+1_800_000L,observedDayEndExclusive=o.observedDayEndExclusive!!+1_800_000L)
                FinanceCivilDateTrust.INVALID -> o.copy(observedLocalDate="2026-12-31")
            }
            // Persist historical/corrupt fixtures; display reads actual current Room rows.
            db.financeV16Dao().putEntryTiming(listOf(posted));db.financeV16Dao().putObservation(listOf(observed))
            val s=finance.snapshot();val current=s.v16!!
            assertEquals(posted,current.entryTimingRows.single());assertEquals(observed,current.observationRows.single())
            assertEquals(trust,current.entryTimingRows.single().postedCivilDateAssessment().trust)
            assertEquals(trust,current.observationRows.single().observedCivilDateAssessment().trust)
            assertEquals(expected.first,current.entryTimingRows.single().postedTimeLabel())
            assertEquals(expected.second,current.observationRows.single().timeLabel())
            val status=FinanceAccountStatusProjection.status(s,a.id)
            assertEquals(if(trust==FinanceCivilDateTrust.CONFIRMED) FinanceTrust.RECONCILED else FinanceTrust.INCOMPLETE,status.trust)
            val conclusion=requireNotNull(status.conclusion)
            if(trust==FinanceCivilDateTrust.CONFIRMED) {
                assertEquals(9_900L,conclusion.bookMinor);assertEquals(0L,conclusion.differenceMinor);assertFalse(conclusion.invalidated)
            } else {
                assertNull(conclusion.bookMinor);assertNull(conclusion.differenceMinor);assertTrue(conclusion.invalidated)
            }
            assertEquals(before.accounts,s.accounts);assertEquals(before.entries,s.entries)
            assertEquals(9_900L,FinanceProjection.balance(s.accounts.single(),s.entries))
        }
        assertEquals(invalid,original.copy(postedAt=original.postedDayStartAt!!-1).postedTimeLabel())
        assertEquals(invalid,o.copy(observedAt=o.observedDayEndExclusive!!).timeLabel())
    }

    @Test fun unsavedAccountConfidenceDoesNotCertifyUnverifiedPostingOrObservationEvidence()=runTest {
        val zone=ZoneId.of("Asia/Tokyo");val (a,_)=seed(zone)
        val o=observe(a.id,FinanceCivilDate.capture(day.plusDays(1),zone))
        val before=finance.snapshot();val posted=before.v16!!.entryTimingRows.single()
        db.financeV16Dao().putEntryTiming(listOf(posted.copy(postedAt=posted.postedAt!!+1_800_000L,
            postedDayStartAt=posted.postedDayStartAt!!+1_800_000L,postedDayEndExclusive=posted.postedDayEndExclusive!!+1_800_000L)))
        db.financeV16Dao().putObservation(listOf(o.copy(observedAt=o.observedAt+1_800_000L,
            observedDayStartAt=o.observedDayStartAt!!+1_800_000L,observedDayEndExclusive=o.observedDayEndExclusive!!+1_800_000L)))
        val s=finance.snapshot();FinanceIntegrity.validate(s)
        assertTrue(s.v16!!.reconciliationRows.isEmpty())
        val status=FinanceAccountStatusProjection.status(s,a.id)
        assertEquals(FinanceTrust.DERIVED,status.trust);assertNull(status.conclusion)
        assertTrue(status.explanation.contains("尚无已保存的核对结论"))
        assertTrue(status.explanation.contains("不代表入账日期证据已核验"))
        assertTrue(s.v16!!.entryTimingRows.single().postedTimeLabel().contains("确认区间无法核验"))
        assertTrue(s.v16!!.observationRows.single().timeLabel().contains("确认区间无法核验"))
        val result=FinanceAccountProjection.reconcile(s,s.v16!!.observationRows.single())
        assertNull(result.bookMinor);assertNull(result.differenceMinor)
        assertEquals(before.accounts,s.accounts);assertEquals(before.entries,s.entries)
        assertEquals(a.balanceAnchorAt,s.accounts.single().balanceAnchorAt)
        assertEquals(9_900L,FinanceProjection.balance(s.accounts.single(),s.entries))
    }

    @Test fun offsetEnvelopeExtremesAndMidnightGapsAreNotFixedTwentyFourHourGuesses()=runTest {
        val zone=ZoneId.of("America/Sao_Paulo");val gapDay=LocalDate.of(2018,11,4)
        val (a,_)=seed(zone,gapDay);val civil=finance.snapshot().v16!!.entryTimingRows.single().postedCivilDate()!!
        assertEquals(1,java.time.Instant.ofEpochMilli(civil.startInclusive).atZone(zone).hour)
        assertEquals(23L*3_600_000L,civil.endExclusive-civil.startInclusive)
        assertEquals(FinanceCivilDateTrust.CONFIRMED,civil.assessment().trust)
        val o=observe(a.id,FinanceCivilDate.capture(gapDay.plusDays(1),zone),FinanceTimePrecision.INSTANT)
        assertEquals(9_900L,FinanceAccountProjection.reconcile(finance.snapshot(),o).bookMinor)
        for(offset in listOf(java.time.ZoneOffset.MIN,java.time.ZoneOffset.MAX)) {
            val edge=FinanceCivilDate.capture(day,offset)
            assertEquals(FinanceCivilDateTrust.CONFIRMED,edge.assessment().trust)
        }
        val edge=FinanceCivilDate.capture(day,java.time.ZoneOffset.MAX)
        assertEquals(FinanceCivilDateTrust.INVALID,edge.copy(startInclusive=edge.startInclusive-1).assessment().trust)
        val dateLine=FinanceCivilDate.capture(LocalDate.of(2011,12,29),ZoneId.of("Pacific/Apia"))
        assertEquals(FinanceCivilDateTrust.CONFIRMED,dateLine.assessment().trust)
        assertThrows(IllegalArgumentException::class.java) {FinanceCivilDate.capture(LocalDate.of(2011,12,30),ZoneId.of("Pacific/Apia"))}
    }

    @Test fun shiftedFixedNamedZoneEvidenceIsInvalidEvenWithUnchangedEpochInsideTheRange()=runTest {
        val cases=listOf("UTC" to true,"Etc/UTC" to true,"UTC+09:00" to true,"GMT+09:00" to true,
            "Z" to true,"+09:00" to true,"Asia/Tokyo" to false,"America/Los_Angeles" to false)
        for((zoneName,fixed) in cases) {
            val zone=ZoneId.of(zoneName)
            assertEquals(fixed,zone.rules.isFixedOffset)
            if(zoneName in listOf("UTC","Etc/UTC","UTC+09:00","GMT+09:00")) assertFalse(zone is java.time.ZoneOffset)
            val (a,entry)=seed(zone);val civil=FinanceCivilDate.capture(day,zone)
            val revision=finance.snapshot().entries.single {it.id==entry.id}.revision
            finance.workspace.setPostingDate(token(),entry.id,revision,day.atTime(12,0).atZone(zone).toInstant().toEpochMilli(),
                FinanceTimePrecision.DATE,"合成中午原始epoch",civil)
            val observation=observe(a.id,FinanceCivilDate.capture(day.plusDays(1),zone),
                at=day.plusDays(1).atTime(12,0).atZone(zone).toInstant().toEpochMilli())
            val before=finance.snapshot();val v=before.v16!!;val original=v.entryTimingRows.single {it.entryId==entry.id}
            val shifted=original.copy(postedDayStartAt=original.postedDayStartAt!!+1_800_000L,
                postedDayEndExclusive=original.postedDayEndExclusive!!+1_800_000L)
            val shiftedObservation=observation.copy(observedDayStartAt=observation.observedDayStartAt!!+1_800_000L,
                observedDayEndExclusive=observation.observedDayEndExclusive!!+1_800_000L)
            assertEquals(original.postedAt,shifted.postedAt);assertEquals(observation.observedAt,shiftedObservation.observedAt)
            assertTrue(shifted.postedAt!!>shifted.postedDayStartAt!! && shifted.postedAt!!<shifted.postedDayEndExclusive!!)
            assertTrue(shiftedObservation.observedAt>shiftedObservation.observedDayStartAt!! &&
                shiftedObservation.observedAt<shiftedObservation.observedDayEndExclusive!!)
            val expected=if(fixed) FinanceCivilDateTrust.INVALID else FinanceCivilDateTrust.HISTORICAL_UNVERIFIED
            assertEquals(expected,shifted.postedCivilDateAssessment().trust)
            assertEquals(expected,shiftedObservation.observedCivilDateAssessment().trust)
            val withPosting=before.copy(v16=v.copy(entryTimingRows=v.entryTimingRows.map {if(it.entryId==entry.id) shifted else it}))
            val withObservation=before.copy(v16=v.copy(observationRows=v.observationRows.map {if(it.id==observation.id) shiftedObservation else it}))
            if(fixed) {
                assertThrows(IllegalArgumentException::class.java) {FinanceIntegrity.validate(withPosting)}
                assertThrows(IllegalArgumentException::class.java) {FinanceIntegrity.validate(withObservation)}
                assertEquals("日期资料无效（不能作为已确认日期）",shifted.postedTimeLabel())
                assertEquals("日期资料无效（不能作为已确认日期）",shiftedObservation.timeLabel())
            } else {
                FinanceIntegrity.validate(withPosting);FinanceIntegrity.validate(withObservation)
                assertEquals(shifted.postedLocalDate,shifted.postedCivilDate()!!.localDate)
                assertEquals(shifted.postedDayStartAt,shifted.postedCivilDate()!!.startInclusive)
                val result=FinanceAccountProjection.reconcile(withPosting,observation)
                assertNull(result.bookMinor);assertNull(result.differenceMinor)
                val observedResult=FinanceAccountProjection.reconcile(withObservation,shiftedObservation)
                assertNull(observedResult.bookMinor);assertNull(observedResult.differenceMinor)
            }
            assertEquals(before,finance.snapshot())
        }
    }

    private suspend fun backupRoundTrip(legacy:Boolean,historical:Boolean=false,malicious:Boolean=false,forgedFixedZone:String?=null) {
        val context=ApplicationProvider.getApplicationContext<Context>();val wardrobe=LocalWardrobeRepository(context)
        val zone=ZoneId.of(forgedFixedZone ?: "Asia/Tokyo");val (a,entry)=seed(zone)
        if(forgedFixedZone!=null) {
            val revision=finance.snapshot().entries.single {it.id==entry.id}.revision
            finance.workspace.setPostingDate(token(),entry.id,revision,day.atTime(12,0).atZone(zone).toInstant().toEpochMilli(),
                FinanceTimePrecision.DATE,"合成原始epoch",FinanceCivilDate.capture(day,zone))
        }
        val observationDay=FinanceCivilDate.capture(day.plusDays(1),zone)
        val o=observe(a.id,observationDay,at=if(forgedFixedZone==null) observationDay.startInclusive
            else day.plusDays(1).atTime(12,0).atZone(zone).toInstant().toEpochMilli())
        if(historical) finance.workspace.confirmReconciliation(token(),o.id,FinanceAccountProjection.fingerprint(finance.snapshot(),o),"")
        val original=finance.portableSnapshot()
        val rejects=malicious || forgedFixedZone!=null
        val suffix=forgedFixedZone?.replace(Regex("[^A-Za-z0-9]"),"_") ?: "standard"
        val encrypted=files.newFile("date-$legacy-$suffix.lifeos")
        val password="synthetic-civil-date-only".toCharArray()
        if(!legacy && !historical && !rejects) assertTrue(EncryptedBackupManager.export(context,wardrobe,Uri.fromFile(encrypted),db,password.copyOf()).isSuccess)
        else {
            val plain=files.newFile("date-$legacy-$suffix.zip")
            assertTrue(BackupManager.export(context,wardrobe,Uri.fromFile(plain),db).isSuccess)
            val entries=linkedMapOf<String,ByteArray>()
            ZipInputStream(plain.inputStream()).use {zip->while(true) {val part=zip.nextEntry ?: break;entries[part.name]=zip.readBytes()}}
            val payload=JsonParser.parseString(entries.getValue("life/data.json").toString(Charsets.UTF_8)).asJsonObject
            val v=payload.getAsJsonObject("finance").getAsJsonObject("v16")
            for((table,prefix) in listOf("entryTimingRows" to "posted","observationRows" to "observed"))
                v.getAsJsonArray(table).forEach {row->
                    if(legacy) listOf("LocalDate","ZoneId","DayStartAt","DayEndExclusive","DateBasisVersion").forEach {row.asJsonObject.remove(prefix+it)}
                    if(historical) listOf("At","DayStartAt","DayEndExclusive").forEach {suffix->
                        row.asJsonObject.addProperty(prefix+suffix,row.asJsonObject.get(prefix+suffix).asLong+1_800_000L)}
                    if(malicious && prefix=="posted") row.asJsonObject.addProperty("postedLocalDate","2026-12-31")
                    val currentRow=if(prefix=="posted") row.asJsonObject.get("entryId").asString==entry.id
                        else row.asJsonObject.get("id").asString==o.id
                    if(forgedFixedZone!=null && currentRow) {
                        // Only forge this case's row; previous loop cases remain valid source facts.
                        val epoch=row.asJsonObject.get(prefix+"At").asLong
                        assertEquals(forgedFixedZone,row.asJsonObject.get(prefix+"ZoneId").asString)
                        listOf("DayStartAt","DayEndExclusive").forEach {suffix->
                            row.asJsonObject.addProperty(prefix+suffix,row.asJsonObject.get(prefix+suffix).asLong+1_800_000L)}
                        assertEquals(epoch,row.asJsonObject.get(prefix+"At").asLong)
                        assertTrue(epoch>row.asJsonObject.get(prefix+"DayStartAt").asLong &&
                            epoch<row.asJsonObject.get(prefix+"DayEndExclusive").asLong)
                    }
                }
            entries["life/data.json"]=payload.toString().toByteArray(Charsets.UTF_8)
            val old=files.newFile("old-candidate-v5-$suffix.zip")
            ZipOutputStream(old.outputStream()).use {zip->entries.forEach {(name,bytes)->zip.putNextEntry(ZipEntry(name));zip.write(bytes);zip.closeEntry()}}
            old.inputStream().use {input->encrypted.outputStream().use {output->EncryptedBackupEnvelope.encrypt(input,output,password.copyOf())}}
        }
        val target=Room.inMemoryDatabaseBuilder(context,LifeDatabase::class.java).allowMainThreadQueries().build()
        val defaultZone=TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"))
            val targetFinance=FinanceRepository(target){clock}
            if(rejects) {
                val existing=targetFinance.saveAccount(name="目标合成原账",kind=FinanceAccountKind.BANK_CARD,openingBalanceMinor=50_000,balanceAnchorAt=1L)
                targetFinance.saveEntry(accountId=existing.id,direction=FinanceDirection.OUTFLOW,amountMinor=700,description="必须保留",occurredAt=2L)
            }
            val beforeTarget=LifeBackupApplier.snapshot(target);val beforeFinance=targetFinance.snapshot()
            val outcome=EncryptedBackupManager.restore(context,wardrobe,Uri.fromFile(encrypted),target,password.copyOf())
            if(rejects) {
                assertTrue(outcome.isFailure);assertNotNull(outcome.exceptionOrNull())
                if(forgedFixedZone!=null) assertTrue(generateSequence(requireNotNull(outcome.exceptionOrNull())) {it.cause}
                    .any {it is IllegalArgumentException && it.message?.contains("日历日期证据矛盾")==true})
                assertEquals(beforeTarget,LifeBackupApplier.snapshot(target));assertEquals(beforeFinance,targetFinance.snapshot())
                assertEquals(49_300L,FinanceProjection.balance(beforeFinance.accounts.single(),targetFinance.snapshot().entries))
            } else {
                assertTrue(outcome.isSuccess)
                val restored=FinanceRepository(target).portableSnapshot()
                assertEquals(original.accounts,restored.accounts);assertEquals(original.entries,restored.entries)
                assertEquals(a.balanceAnchorAt,restored.accounts.single().balanceAnchorAt)
                assertEquals(9_900L,FinanceProjection.balance(restored.accounts.single(),restored.entries))
                val posted=restored.v16!!.entryTimingRows.single();val observed=restored.v16!!.observationRows.single()
                assertEquals(o.id,observed.id)
                if(!historical) assertEquals(original.v16!!.entryTimingRows.single().postedAt,posted.postedAt)
                if(legacy) {
                    assertNull(posted.postedCivilDate());assertNull(observed.observedCivilDate())
                    assertNull(posted.postedZoneId);assertNull(observed.observedZoneId)
                    val incomplete=FinanceAccountProjection.reconcile(restored,observed)
                    assertNull(incomplete.bookMinor);assertNull(incomplete.differenceMinor)
                    targetFinance.workspace.confirmReconciliation(FinanceCommandToken(java.util.UUID.randomUUID().toString(),
                        restored.v16!!.controlRows.single().restoreEpoch),observed.id,incomplete.fingerprint,"")
                    assertEquals(FinanceTrust.INCOMPLETE,FinanceAccountStatusProjection.status(targetFinance.snapshot(),a.id).trust)
                    assertTrue(observed.timeLabel().contains("资料不足"))
                } else if(historical) {
                    val old=original.v16!!.entryTimingRows.single();val oldObservation=original.v16!!.observationRows.single()
                    assertEquals(old.copy(postedAt=old.postedAt!!+1_800_000L,postedDayStartAt=old.postedDayStartAt!!+1_800_000L,
                        postedDayEndExclusive=old.postedDayEndExclusive!!+1_800_000L),posted)
                    assertEquals(oldObservation.copy(observedAt=oldObservation.observedAt+1_800_000L,
                        observedDayStartAt=oldObservation.observedDayStartAt!!+1_800_000L,
                        observedDayEndExclusive=oldObservation.observedDayEndExclusive!!+1_800_000L),observed)
                    val result=FinanceAccountProjection.reconcile(restored,observed,restored.v16!!.reconciliationRows.single())
                    assertNull(result.bookMinor);assertNull(result.differenceMinor);assertTrue(result.invalidated)
                    assertEquals(FinanceTrust.INCOMPLETE,FinanceAccountStatusProjection.status(restored,a.id).trust)
                } else {
                    assertEquals(original.v16!!.entryTimingRows,restored.v16!!.entryTimingRows)
                    assertEquals(original.v16!!.observationRows,restored.v16!!.observationRows)
                    assertEquals("2026-10-10",posted.postedLocalDate);assertEquals("Asia/Tokyo",posted.postedZoneId)
                    assertEquals(FinanceAccountProjection.fingerprint(original,o),FinanceAccountProjection.fingerprint(restored,observed))
                    assertEquals(9_900L,FinanceAccountProjection.reconcile(restored,observed).bookMinor)
                }
                FinanceIntegrity.validate(restored)
            }
        } finally {TimeZone.setDefault(defaultZone);target.close();password.fill('\u0000')}
    }
    @Test fun encryptedTypedBackupPreservesDateZoneBoundsAcrossDevices()=runTest {backupRoundTrip(false)}
    @Test fun olderTypedArchiveDoesNotInventDateOrZoneOnRestore()=runTest {backupRoundTrip(true)}
    @Test fun plausibleOldRuleBackupPreservesRawEvidenceAndInvalidatesInsteadOfRepairing()=runTest {backupRoundTrip(false,historical=true)}
    @Test fun contradictoryTypedEncryptedBackupIsRejectedBeforeAnyTargetSnapshotChange()=runTest {backupRoundTrip(false,malicious=true)}
    @Test fun forgedFixedNamedZoneEncryptedBackupsCannotChangeAnExistingLedger()=runTest {
        for(zone in listOf("UTC","Etc/UTC","UTC+09:00")) backupRoundTrip(false,forgedFixedZone=zone)
    }
}
