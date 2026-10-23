package com.qq.closie.life.finance

import com.qq.closie.data.backup.LifeBackupApplier
import com.qq.closie.life.repository.HomePhotoRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HomePhotoReferenceV16Test : FinanceRoomTest() {
    @Test fun explicitlySharedOriginalIsReusedAndDailyGrantDoesNotCopyBytes() = runTest {
        val home = HomePhotoRepository(db)
        val first = home.grantValidated("content://synthetic/photo", "image/png", "生活照片", 1760000000000)
        assertEquals(first, home.grantValidated("content://synthetic/photo", "image/png", "生活照片", 1760000000000))
        val s = LifeBackupApplier.snapshot(db)
        assertEquals(1, s.mediaAssets.size)
        assertEquals(1, s.mediaResources.size)
        assertNull(s.mediaResources.single().row.managedPath)
        assertEquals("content://synthetic/photo", s.mediaResources.single().row.contentUri)
        assertEquals(1, home.observe().first().size)
        val second = home.grantValidated("content://synthetic/photo", "image/png", "生活照片", 1760000000000 + 86400000)
        assertNotEquals(first, second)
        assertEquals(1, LifeBackupApplier.snapshot(db).mediaAssets.size)
        assertEquals(2, home.observe().first().map { it.date }.distinct().size)
    }
    @Test fun removingHomeGrantPreservesSharedAssetAndBackupRoundTripKeepsRemainingGrant() = runTest {
        val home = HomePhotoRepository(db)
        val id = home.grantValidated("content://synthetic/photo", "image/jpeg", null, 1760000000000)
        val saved = LifeBackupApplier.snapshot(db)
        home.remove(id)
        assertTrue(home.observe().first().isEmpty())
        assertEquals(saved.mediaAssets, LifeBackupApplier.snapshot(db).mediaAssets)
        assertEquals(saved.mediaResources, LifeBackupApplier.snapshot(db).mediaResources)
        LifeBackupApplier.restoreSnapshot(db, saved)
        assertEquals(id, home.observe().first().single().id)
        assertTrue(finance.snapshot().entries.isEmpty())
    }
}
