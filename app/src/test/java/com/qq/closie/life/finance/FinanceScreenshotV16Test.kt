package com.qq.closie.life.finance

import com.qq.closie.data.backup.LifeBackupApplier
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FinanceScreenshotV16Test : FinanceRoomTest() {
    private fun token() = FinanceCommandToken(java.util.UUID.randomUUID().toString(), 0)
    private val fields = FinanceOcrFields(1234, null, FinanceProposalKind.EXPENSE, null, "交易时间需补充", currencyCode = "CNY")

    @Test fun screenshotCreatesOnlyOneTypedCandidateAndNeverKeepsAnImageOrWritesCash() = runTest {
        val operation = token()
        val id = finance.media.createScreenshotCandidate(operation, fields) { true }
        assertEquals(id, finance.media.createScreenshotCandidate(operation, fields) { true })
        val s = finance.snapshot()
        assertTrue(s.entries.isEmpty())
        assertTrue(s.transfers.isEmpty())
        assertTrue(s.v16!!.mediaLinkRows.isEmpty())
        assertTrue(LifeBackupApplier.snapshot(db).mediaAssets.isEmpty())
        assertEquals(FinanceSource.SCREENSHOT, s.v4!!.proposals.single().source)
        assertEquals("", s.v4!!.proposals.single().evidenceText)
        assertNull(s.v4!!.proposals.single().accountId)
        assertEquals("user-screenshot", s.v16!!.evidenceRows.single().sourceFamily)
        assertEquals("", s.v16!!.reviewSessionRows.single().occurredText)
        assertTrue(runCatching { finance.reviews.confirm(token(), id, 1, System.currentTimeMillis()) }.isFailure)
    }
    @Test fun cancelledOrEmptyCaptureDoesNotChangeDatabase() = runTest {
        val before = finance.snapshot()
        assertTrue(runCatching { finance.media.createScreenshotCandidate(token(), fields) { false } }.isFailure)
        val empty = FinanceOcrFields(null, null, null, null, "未读到字段")
        assertTrue(runCatching { finance.media.createScreenshotCandidate(token(), empty) { true } }.isFailure)
        assertEquals(before, finance.snapshot())
    }
    @Test fun screenshotSourceAndIncompleteReviewSurviveBackupWithoutInventingTime() = runTest {
        finance.media.createScreenshotCandidate(token(), fields) { true }
        LifeBackupApplier.restoreSnapshot(db, LifeBackupApplier.snapshot(db))
        val s = finance.snapshot()
        assertEquals(FinanceSource.SCREENSHOT, s.v4!!.proposals.single().source)
        assertEquals("", s.v16!!.reviewSessionRows.single().occurredText)
        assertTrue(s.entries.isEmpty())
        FinanceIntegrity.validate(s)
    }
}
