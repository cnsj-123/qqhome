package com.qq.closie.ui.lifeos.settings

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import coil.annotation.ExperimentalCoilApi
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.qq.closie.life.finance.FinanceRoomTest
import com.qq.closie.life.media.MediaResourceRole
import com.qq.closie.life.media.MediaType
import com.qq.closie.life.repository.MediaRepository
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import androidx.room.Room
import androidx.room.withTransaction
import com.qq.closie.life.data.database.LifeDatabase
import com.qq.closie.data.backup.RestoreStartupGate
import com.qq.closie.life.media.MediaAssetEntity
import com.qq.closie.life.media.MediaResourceEntity
import java.util.concurrent.Executor

@OptIn(ExperimentalCoilApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsStorageControlsTest : FinanceRoomTest() {
    @Test fun largeGalleryUsesTwoBatchReadsAndReleasesRestoreLeaseBeforeInspectingFiles() = runBlocking {
        val queries=mutableListOf<String>()
        val context:Context=ApplicationProvider.getApplicationContext()
        val gallery=Room.inMemoryDatabaseBuilder(context,LifeDatabase::class.java).allowMainThreadQueries()
            .setQueryCallback({sql,_->if(sql.trimStart().startsWith("SELECT",ignoreCase=true)) queries+=sql},Executor {it.run()}).build()
        try {
            gallery.withTransaction {
                repeat(1000) {index->
                    val id="gallery-$index"
                    gallery.mediaDao().insertAsset(MediaAssetEntity(id,MediaType.IMAGE,createdAt=1,updatedAt=1))
                    if(index<800) gallery.mediaDao().insertResource(MediaResourceEntity("original-$index",id,
                        MediaResourceRole.ORIGINAL,"image/jpeg",managedPath="synthetic-shared-original",createdAt=1))
                }
            }
            queries.clear()
            var inspected=0
            val summary=readSettingsMediaStorage(MediaRepository(gallery)) {
                org.junit.Assert.assertEquals("Filesystem checks must not hold a restore lease",0,RestoreStartupGate.activeBusinessOps)
                inspected++;true
            }
            assertEquals(1000,summary.assets)
            assertEquals(1,summary.localOriginals)
            assertEquals(200,summary.missingOriginals)
            assertEquals(1,inspected)
            assertEquals("Storage summary must not query each asset individually",2,queries.size)
        } finally {gallery.close()}
    }
    @Test fun clearingTheActualImageCachePreservesBackupScratchAndOriginals() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val root = File(context.cacheDir, "settings-cache-test-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val backup = File(root, "backup-pending.bin").apply { writeText("backup awaiting verification") }
            val financeExport = File(root, "finance-export-pending.zip").apply { writeText("export awaiting save") }
            val original = File(root, "managed-original.jpg").apply { writeText("shared original") }
            val memory = MemoryCache.Builder(context).maxSizeBytes(1024 * 1024).build()
            memory[MemoryCache.Key("thumbnail")] = MemoryCache.Value(Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888))
            val disk = DiskCache.Builder().directory(File(root, "coil")).maxSizeBytes(1024 * 1024).build()
            val editor = requireNotNull(disk.openEditor("thumbnail"))
            disk.fileSystem.write(editor.data) { writeUtf8("thumbnail cache bytes") }
            editor.commit()
            val cache = SettingsImageCache(memory, disk)
            assertTrue(cache.sizeBytes() > 0)
            cache.clear()
            assertEquals(0L, cache.sizeBytes())
            assertEquals("backup awaiting verification", backup.readText())
            assertEquals("export awaiting save", financeExport.readText())
            assertEquals("shared original", original.readText())
        } finally { root.deleteRecursively() }
    }

    @Test fun mediaStorageReadsCurrentOriginalAvailabilityWithoutWritingMediaFacts() = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        val local = File(context.cacheDir, "settings-original-test-${UUID.randomUUID()}").apply { writeText("original") }
        try {
            val media = MediaRepository(db)
            val managed = media.createMediaAsset(MediaType.IMAGE)
            val external = media.createMediaAsset(MediaType.IMAGE)
            val missing = media.createMediaAsset(MediaType.IMAGE)
            val original = media.addMediaResource(managed.id, MediaResourceRole.ORIGINAL, "image/jpeg", managedPath = local.path)
            val reference = media.addMediaResource(external.id, MediaResourceRole.ORIGINAL, "image/jpeg", contentUri = "content://synthetic/selected-photo")
            media.addMediaResource(managed.id, MediaResourceRole.THUMBNAIL, "image/jpeg", managedPath = local.path + ".missing-thumb")
            val before = db.mediaDao().getAllResources()
            val summary = readSettingsMediaStorage(media)
            assertEquals(3, summary.assets)
            assertEquals(1, summary.localOriginals)
            assertEquals(1, summary.externalReferences)
            assertEquals(1, summary.missingOriginals)
            assertEquals(listOf(reference.contentUri), summary.externalUris)
            assertTrue(local.delete())
            val unavailable = readSettingsMediaStorage(media)
            assertEquals(0, unavailable.localOriginals)
            assertEquals(2, unavailable.missingOriginals)
            assertEquals(before, db.mediaDao().getAllResources())
            assertEquals(original, media.getResourcesForAsset(managed.id).first { it.id == original.id })
            assertEquals(emptyList<Any>(), media.getResourcesForAsset(missing.id))
        } finally { local.delete() }
    }
}
