package com.qq.closie.life.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.qq.closie.data.backup.RestoreStartupGate
import com.qq.closie.life.data.database.LifeDatabase
import com.qq.closie.life.media.MediaResourceRole
import com.qq.closie.life.media.MediaType
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
class MediaRepositoryTest {

    private lateinit var db: LifeDatabase
    private lateinit var media: MediaRepository
    private lateinit var life: LifeRepository

    @Before
    fun setUp() {
        // This file asserts repository semantics, not the restore barrier, so it resolves the gate as a
        // precondition. The gate starts BLOCKED by design and every repository re-checks it per call, so
        // without this each test would fail with "恢复尚未完成" for a reason unrelated to what it asserts.
        // `resetForTesting` first: the gate is process-wide, and a barrier test may have closed it for
        // the rest of this JVM.
        RestoreStartupGate.resetForTesting()
        RestoreStartupGate.markReady()
        val context: Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, LifeDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        media = MediaRepository(db)
        life = LifeRepository(db)
    }

    @After
    fun tearDown() {
        db.close()
        RestoreStartupGate.resetForTesting()
    }

    @Test
    fun oneAssetSupportsManyResources() = runTest {
        val asset = media.createMediaAsset(MediaType.IMAGE, takenAt = 1_000L, timestamp = 2_000L)

        val original = media.addMediaResource(
            mediaAssetId = asset.id,
            role = MediaResourceRole.ORIGINAL,
            mimeType = "image/jpeg",
            sha256 = "sha-original",
            sizeBytes = 2_048L,
            width = 1920,
            height = 1080,
            timestamp = 3_000L
        )
        media.addMediaResource(
            mediaAssetId = asset.id,
            role = MediaResourceRole.THUMBNAIL,
            mimeType = "image/webp",
            sha256 = "sha-thumb",
            sizeBytes = 128L,
            width = 320,
            height = 180,
            timestamp = 4_000L
        )

        val resources = media.getResourcesForAsset(asset.id)
        assertThat(resources).hasSize(2)
        assertThat(resources.map { it.role }).containsExactly(
            MediaResourceRole.ORIGINAL,
            MediaResourceRole.THUMBNAIL
        )
        assertThat(resources.first().id).isEqualTo(original.id)
    }

    @Test
    fun findResourceBySha256_returnsStoredResource() = runTest {
        val asset = media.createMediaAsset(MediaType.IMAGE, timestamp = 1_000L)
        media.addMediaResource(
            mediaAssetId = asset.id,
            role = MediaResourceRole.ORIGINAL,
            mimeType = "image/jpeg",
            sha256 = "deadbeef",
            timestamp = 2_000L
        )

        val found = media.findResourceBySha256("deadbeef")

        assertThat(found).isNotNull()
        assertThat(found!!.mediaAssetId).isEqualTo(asset.id)
        assertThat(media.findResourceBySha256("missing")).isNull()
    }

    @Test
    fun linkMedia_createsLinkVisibleFromBothSides() = runTest {
        val owner = life.createEntity("journal", timestamp = 1_000L)
        val asset = media.createMediaAsset(MediaType.IMAGE, timestamp = 2_000L)

        val link = media.linkMedia(asset.id, owner.id, role = "cover", sortOrder = 0, timestamp = 3_000L)

        assertThat(media.getLinksForOwner(owner.id).map { it.id }).containsExactly(link.id)
        assertThat(media.getLinksForAsset(asset.id).map { it.id }).containsExactly(link.id)
        assertThat(media.countLinksForOwner(owner.id)).isEqualTo(1)
    }

    @Test
    fun linkMedia_rejectsMissingOwnerEntity() = runTest {
        val asset = media.createMediaAsset(MediaType.IMAGE, timestamp = 1_000L)

        val error = runCatching {
            media.linkMedia(asset.id, "entity-that-does-not-exist", role = "cover")
        }.exceptionOrNull()

        assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(media.getLinksForAsset(asset.id)).isEmpty()
    }

    @Test
    fun linkMedia_rejectsMissingMediaAsset() = runTest {
        val owner = life.createEntity("journal", timestamp = 1_000L)

        val error = runCatching {
            media.linkMedia("asset-that-does-not-exist", owner.id, role = "cover")
        }.exceptionOrNull()

        assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(media.getLinksForOwner(owner.id)).isEmpty()
    }

    @Test
    fun unlinkMedia_removesOnlyTargetedLink() = runTest {
        val owner = life.createEntity("journal", timestamp = 1_000L)
        val assetA = media.createMediaAsset(MediaType.IMAGE, timestamp = 2_000L)
        val assetB = media.createMediaAsset(MediaType.IMAGE, timestamp = 3_000L)
        media.linkMedia(assetA.id, owner.id, role = "cover")
        media.linkMedia(assetB.id, owner.id, role = "inline")

        media.unlinkMedia(assetA.id, owner.id)

        assertThat(media.getLinksForOwner(owner.id).map { it.mediaAssetId })
            .containsExactly(assetB.id)
    }
}
