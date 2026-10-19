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

    private suspend fun backupRoundTrip(legacy:Boolean) {
        val context=ApplicationProvider.getApplicationContext<Context>();val wardrobe=LocalWardrobeRepository(context)
        val zone=ZoneId.of("Asia/Tokyo");val (a,_)=seed(zone)
        val o=observe(a.id,FinanceCivilDate.capture(day.plusDays(1),zone))
        val original=finance.portableSnapshot()
        val encrypted=files.newFile("date-$legacy.lifeos")
        val password="synthetic-civil-date-only".toCharArray()
        if(!legacy) assertTrue(EncryptedBackupManager.export(context,wardrobe,Uri.fromFile(encrypted),db,password.copyOf()).isSuccess)
        else {
            val plain=files.newFile("date-legacy.zip")
            assertTrue(BackupManager.export(context,wardrobe,Uri.fromFile(plain),db).isSuccess)
            val entries=linkedMapOf<String,ByteArray>()
            ZipInputStream(plain.inputStream()).use {zip->while(true) {val part=zip.nextEntry ?: break;entries[part.name]=zip.readBytes()}}
            val payload=JsonParser.parseString(entries.getValue("life/data.json").toString(Charsets.UTF_8)).asJsonObject
            val v=payload.getAsJsonObject("finance").getAsJsonObject("v16")
            for((table,prefix) in listOf("entryTimingRows" to "posted","observationRows" to "observed"))
                v.getAsJsonArray(table).forEach {row->listOf("LocalDate","ZoneId","DayStartAt","DayEndExclusive","DateBasisVersion").forEach {row.asJsonObject.remove(prefix+it)}}
            entries["life/data.json"]=payload.toString().toByteArray(Charsets.UTF_8)
            val old=files.newFile("old-candidate-v5.zip")
            ZipOutputStream(old.outputStream()).use {zip->entries.forEach {(name,bytes)->zip.putNextEntry(ZipEntry(name));zip.write(bytes);zip.closeEntry()}}
            old.inputStream().use {input->encrypted.outputStream().use {output->EncryptedBackupEnvelope.encrypt(input,output,password.copyOf())}}
        }
        val target=Room.inMemoryDatabaseBuilder(context,LifeDatabase::class.java).allowMainThreadQueries().build()
        val defaultZone=TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"))
            assertTrue(EncryptedBackupManager.restore(context,wardrobe,Uri.fromFile(encrypted),target,password.copyOf()).isSuccess)
            val restored=FinanceRepository(target).portableSnapshot()
            assertEquals(original.accounts,restored.accounts);assertEquals(original.entries,restored.entries)
            val posted=restored.v16!!.entryTimingRows.single();val observed=restored.v16!!.observationRows.single()
            assertEquals(o.id,observed.id);assertEquals(original.v16!!.entryTimingRows.single().postedAt,posted.postedAt)
            if(legacy) {
                assertNull(posted.postedCivilDate());assertNull(observed.observedCivilDate())
                assertNull(posted.postedZoneId);assertNull(observed.observedZoneId)
                assertNull(FinanceAccountProjection.reconcile(restored,observed).bookMinor)
                assertTrue(observed.timeLabel().contains("资料不足"))
            } else {
                assertEquals(original.v16!!.entryTimingRows,restored.v16!!.entryTimingRows)
                assertEquals(original.v16!!.observationRows,restored.v16!!.observationRows)
                assertEquals("2026-10-10",posted.postedLocalDate);assertEquals("Asia/Tokyo",posted.postedZoneId)
                assertEquals(FinanceAccountProjection.fingerprint(original,o),FinanceAccountProjection.fingerprint(restored,observed))
                assertEquals(9_900L,FinanceAccountProjection.reconcile(restored,observed).bookMinor)
            }
            FinanceIntegrity.validate(restored)
        } finally {TimeZone.setDefault(defaultZone);target.close();password.fill('\u0000')}
    }
    @Test fun encryptedTypedBackupPreservesDateZoneBoundsAcrossDevices()=runTest {backupRoundTrip(false)}
    @Test fun olderTypedArchiveDoesNotInventDateOrZoneOnRestore()=runTest {backupRoundTrip(true)}
}
