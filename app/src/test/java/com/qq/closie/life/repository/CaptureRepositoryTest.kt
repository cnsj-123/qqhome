package com.qq.closie.life.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.qq.closie.life.capture.CaptureSource
import com.qq.closie.life.capture.CaptureStatus
import com.qq.closie.life.data.database.LifeDatabase
import com.qq.closie.life.media.MediaType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Robolectric 4.12.2 tops out at API 34, while the app targets 35. Without an explicit sdk the
// runner reads targetSdkVersion from the merged manifest and aborts with
// "Package targetSdkVersion=35 > maxSdkVersion=34". Pinning 34 keeps the JVM suite runnable
// without bumping the Robolectric version; nothing under test depends on API 35 behaviour.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CaptureRepositoryTest {

    private lateinit var db: LifeDatabase
    private lateinit var repo: CaptureRepository
    private lateinit var media: MediaRepository

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, LifeDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = CaptureRepository(db)
        media = MediaRepository(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun create_startsNewWithNullableMedia() = runTest {
        val created = repo.create(
            id = "cap-1",
            source = CaptureSource.CLIPBOARD,
            rawText = "hello",
            now = 1_000L
        )

        assertThat(created).isTrue()

        val item = repo.getById("cap-1")!!
        assertThat(item.status).isEqualTo(CaptureStatus.NEW)
        assertThat(item.source).isEqualTo(CaptureSource.CLIPBOARD)
        assertThat(item.rawText).isEqualTo("hello")
        // Text-only captures have no media: the column is nullable and must stay null.
        assertThat(item.primaryMediaAssetId).isNull()
        assertThat(item.errorMessage).isNull()
    }

    @Test
    fun create_rejectsDuplicateId() = runTest {
        assertThat(repo.create(id = "cap-1", source = CaptureSource.MANUAL, now = 1_000L)).isTrue()
        assertThat(repo.create(id = "cap-1", source = CaptureSource.MANUAL, now = 2_000L)).isFalse()
        assertThat(repo.count()).isEqualTo(1)
    }

    @Test
    fun statusTransitions_walkThePipeline() = runTest {
        repo.create(id = "cap-1", source = CaptureSource.SCREENSHOT, now = 1_000L)

        assertThat(repo.markProcessing("cap-1")).isTrue()
        assertThat(repo.getById("cap-1")!!.status).isEqualTo(CaptureStatus.PROCESSING)

        assertThat(repo.markNeedsReview("cap-1")).isTrue()
        assertThat(repo.getById("cap-1")!!.status).isEqualTo(CaptureStatus.NEEDS_REVIEW)

        assertThat(repo.markConfirmed("cap-1")).isTrue()
        assertThat(repo.getById("cap-1")!!.status).isEqualTo(CaptureStatus.CONFIRMED)
    }

    @Test
    fun markFailed_recordsErrorMessage() = runTest {
        repo.create(id = "cap-1", source = CaptureSource.SHARE, now = 1_000L)

        assertThat(repo.markFailed("cap-1", "OCR failed")).isTrue()

        val failed = repo.getById("cap-1")!!
        assertThat(failed.status).isEqualTo(CaptureStatus.FAILED)
        assertThat(failed.errorMessage).isEqualTo("OCR failed")
        assertThat(repo.observeByStatus(CaptureStatus.FAILED).first().map { it.id })
            .containsExactly("cap-1")
    }

    @Test
    fun retryAfterFailure_clearsErrorMessage() = runTest {
        repo.create(id = "cap-1", source = CaptureSource.SHARE, now = 1_000L)
        repo.markFailed("cap-1", "network down")

        repo.markProcessing("cap-1")

        val retrying = repo.getById("cap-1")!!
        assertThat(retrying.status).isEqualTo(CaptureStatus.PROCESSING)
        assertThat(retrying.errorMessage).isNull()
    }

    @Test
    fun dismiss_isTerminalAndKeepsHistory() = runTest {
        repo.create(id = "cap-1", source = CaptureSource.NOTIFICATION, now = 1_000L)

        assertThat(repo.dismiss("cap-1")).isTrue()

        assertThat(repo.getById("cap-1")!!.status).isEqualTo(CaptureStatus.DISMISSED)
        assertThat(repo.count()).isEqualTo(1)
        assertThat(repo.observePending().first()).isEmpty()
    }

    @Test
    fun transitionsOnMissingId_returnFalse() = runTest {
        assertThat(repo.markProcessing("nope")).isFalse()
        assertThat(repo.markConfirmed("nope")).isFalse()
        assertThat(repo.dismiss("nope")).isFalse()
        assertThat(repo.markFailed("nope", "boom")).isFalse()
        assertThat(repo.updateRawText("nope", "x")).isFalse()
        // No capture with that id — rejected before the asset is even looked up.
        assertThat(repo.attachMedia("nope", "asset")).isFalse()
    }

    @Test
    fun attachMedia_setsPrimaryAsset() = runTest {
        val asset = media.createMediaAsset(mediaType = MediaType.IMAGE)
        repo.create(id = "cap-1", source = CaptureSource.CAMERA, now = 1_000L)

        assertThat(repo.attachMedia("cap-1", asset.id)).isTrue()
        assertThat(repo.getById("cap-1")!!.primaryMediaAssetId).isEqualTo(asset.id)
    }

    /**
     * The core integrity rule: `primaryMediaAssetId` has no physical foreign key, so the repository
     * is the only thing standing between a capture and a dangling media reference.
     */
    @Test
    fun attachMedia_rejectsMissingMediaAsset() = runTest {
        repo.create(id = "cap-1", source = CaptureSource.CAMERA, now = 1_000L)

        assertThat(repo.attachMedia("cap-1", "asset-does-not-exist")).isFalse()

        // The capture itself survives untouched — media is rejected, history is never dropped.
        val item = repo.getById("cap-1")!!
        assertThat(item.primaryMediaAssetId).isNull()
        assertThat(repo.count()).isEqualTo(1)
    }

    @Test
    fun create_rejectsMissingPrimaryMediaAsset() = runTest {
        val created = repo.create(
            id = "cap-1",
            source = CaptureSource.SCREENSHOT,
            primaryMediaAssetId = "asset-does-not-exist",
            now = 1_000L
        )

        assertThat(created).isFalse()
        assertThat(repo.getById("cap-1")).isNull()
        assertThat(repo.count()).isEqualTo(0)
    }

    @Test
    fun create_acceptsExistingPrimaryMediaAsset() = runTest {
        val asset = media.createMediaAsset(mediaType = MediaType.IMAGE)

        val created = repo.create(
            id = "cap-1",
            source = CaptureSource.SCREENSHOT,
            primaryMediaAssetId = asset.id,
            now = 1_000L
        )

        assertThat(created).isTrue()
        assertThat(repo.getById("cap-1")!!.primaryMediaAssetId).isEqualTo(asset.id)
    }

    /**
     * v0.2.0 Timeline delete: removing one record must remove exactly that record — the sibling
     * captured one second later must survive untouched.
     */
    @Test
    fun delete_existingCapture_removesIt() = runTest {
        repo.create(id = "cap-1", source = CaptureSource.CLIPBOARD, rawText = "delete me", now = 1_000L)
        repo.create(id = "cap-2", source = CaptureSource.CLIPBOARD, rawText = "keep me", now = 2_000L)

        repo.delete("cap-1")

        assertThat(repo.getById("cap-1")).isNull()
        // The delete scope is exactly one row: no cascade, no neighbour lost.
        val survivor = repo.getById("cap-2")
        assertThat(survivor).isNotNull()
        assertThat(survivor!!.rawText).isEqualTo("keep me")
        assertThat(repo.count()).isEqualTo(1)
    }

    /** Deleting an id that never existed (or was already deleted) must be a silent no-op. */
    @Test
    fun delete_missingCapture_doesNotCrash() = runTest {
        repo.create(id = "cap-1", source = CaptureSource.MANUAL, now = 1_000L)

        // Never-existed id: silent no-op, and the real record is untouched.
        repo.delete("ghost-id")
        assertThat(repo.getById("cap-1")).isNotNull()

        // Double delete: the second call sees a missing row and must still be silent.
        repo.delete("cap-1")
        repo.delete("cap-1")

        assertThat(repo.count()).isEqualTo(0)
    }
}
