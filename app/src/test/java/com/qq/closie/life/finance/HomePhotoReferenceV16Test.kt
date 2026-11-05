package com.qq.closie.life.finance

import com.qq.closie.data.backup.LifeBackupApplier
import com.qq.closie.data.backup.RestoreStartupGate
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
    @Test fun explicitPhotoCaptionUsesOneCuratedNoteAndSurvivesBackupWithoutCopyingTheOriginal() = runTest {
        val home = HomePhotoRepository(db)
        val id = home.grantValidated("content://synthetic/photo", "image/jpeg", "not-a-caption.jpg", 1760000000000)
        val generation = RestoreStartupGate.state.value.restoreGeneration
        assertNull(home.observe().first().single().caption)
        home.setCaption(id, "那天的晚霞", null, generation)
        assertEquals("那天的晚霞", home.observe().first().single().caption)
        home.setCaption(id, "回家的晚霞", "那天的晚霞", generation)
        val saved = LifeBackupApplier.snapshot(db)
        assertEquals(1, saved.mediaAssets.size)
        assertEquals(1, saved.mediaResources.size)
        assertEquals("回家的晚霞", db.referenceDao().getAllOnce().single().summary)
        assertTrue(runCatching { home.setCaption(id, "旧编辑器覆盖", "那天的晚霞", generation) }.isFailure)
        assertEquals("回家的晚霞", home.observe().first().single().caption)
        home.setCaption(id, "", "回家的晚霞", generation)
        assertNull(home.observe().first().single().caption)
        assertEquals("回家的晚霞", db.referenceDao().getAllOnce().single().summary)
        LifeBackupApplier.restoreSnapshot(db, saved)
        assertEquals("回家的晚霞", home.observe().first().single().caption)
        home.remove(id)
        assertTrue(home.observe().first().isEmpty())
        assertEquals(1, db.referenceDao().getAllOnce().size)
        home.grantValidated("content://synthetic/photo", "image/jpeg", null, 1760000000000)
        assertNull(home.observe().first().single().caption)
    }
    @Test fun restoringOrRevokingHomeConsentRejectsAnOpenCaptionEditor() = runTest {
        val home = HomePhotoRepository(db)
        val id = home.grantValidated("content://synthetic/photo", "image/jpeg", null, 1760000000000)
        val generation = RestoreStartupGate.state.value.restoreGeneration
        RestoreStartupGate.beginRestore(); RestoreStartupGate.markReady()
        assertTrue(runCatching { home.setCaption(id, "过期编辑", null, generation) }.isFailure)
        home.remove(id)
        assertTrue(runCatching { home.setCaption(id, "撤销后编辑", null, RestoreStartupGate.state.value.restoreGeneration) }.isFailure)
        assertTrue(db.referenceDao().getAllOnce().isEmpty())
    }
    @Test fun aSharedPhotoDoesNotImplicitlyShareAPrivateReferenceUsingTheSameAsset() = runTest {
        val home = HomePhotoRepository(db)
        val id = home.grantValidated("content://synthetic/photo", "image/jpeg", "private-filename.jpg", 1760000000000)
        val asset = home.observe().first().single().assetId
        val media = com.qq.closie.life.repository.MediaRepository(db)
        val references = com.qq.closie.life.repository.ReferenceRepository(db,
            com.qq.closie.life.repository.LifeRepository(db), media, com.qq.closie.life.repository.CaptureRepository(db))
        val private = references.create("私密资料", com.qq.closie.life.reference.ReferenceType.NOTE, summary = "不能自动展示的私密备注")
        media.linkMedia(asset, private.lifeEntityId, "reference-attachment")
        assertNull(home.observe().first().single().caption)
        home.setCaption(id, "明确展示的回忆", null, RestoreStartupGate.state.value.restoreGeneration)
        assertEquals("明确展示的回忆", home.observe().first().single().caption)
        assertEquals("不能自动展示的私密备注", references.getById(private.id)?.summary)
    }
    @Test fun reclassifyingTheCaptionNoteInvalidatesAnAlreadyOpenCaptionEditor() = runTest {
        val home = HomePhotoRepository(db)
        val id = home.grantValidated("content://synthetic/photo", "image/jpeg", null, 1760000000000)
        val generation = RestoreStartupGate.state.value.restoreGeneration
        home.setCaption(id, "原备注", null, generation)
        val note = db.referenceDao().getAllOnce().single()
        db.referenceDao().update(note.copy(referenceType = com.qq.closie.life.reference.ReferenceType.ARTICLE))
        assertNull(home.observe().first().single().caption)
        assertTrue(runCatching { home.setCaption(id, "旧编辑器修改", "原备注", generation) }.isFailure)
        assertEquals("原备注", db.referenceDao().getById(note.id)?.summary)
    }
}
