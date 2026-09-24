package com.qq.closie.life.data.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.qq.closie.life.capture.CaptureItemEntity
import com.qq.closie.life.capture.CaptureSourceConverter
import com.qq.closie.life.capture.CaptureStatusConverter
import com.qq.closie.life.core.EntityTagCrossRef
import com.qq.closie.life.core.LifeEntityEntity
import com.qq.closie.life.core.LifeRelationEntity
import com.qq.closie.life.core.TagEntity
import com.qq.closie.life.data.database.dao.CaptureDao
import com.qq.closie.life.data.database.dao.LifeEntityDao
import com.qq.closie.life.data.database.dao.LifeRelationDao
import com.qq.closie.life.data.database.dao.MediaDao
import com.qq.closie.life.data.database.dao.TagDao
import com.qq.closie.life.media.MediaAssetEntity
import com.qq.closie.life.media.MediaLinkEntity
import com.qq.closie.life.media.MediaResourceEntity
import com.qq.closie.life.media.MediaResourceRoleConverter
import com.qq.closie.life.media.MediaTypeConverter

/**
 * The single Room database for Life OS.
 *
 * Version 1. Future schema changes MUST ship an explicit [androidx.room.migration.Migration].
 * `fallbackToDestructiveMigration()` is forbidden — Life OS is a long-lived personal database and
 * must never lose user data on a schema change.
 *
 * Schema export is enabled via the project-level KSP block in app/build.gradle.kts
 * (`room.schemaLocation` → app/schemas/), which is committed to the repository.
 */
@Database(
    version = 1,
    exportSchema = true,
    entities = [
        LifeEntityEntity::class,
        LifeRelationEntity::class,
        TagEntity::class,
        EntityTagCrossRef::class,
        MediaAssetEntity::class,
        MediaResourceEntity::class,
        MediaLinkEntity::class,
        CaptureItemEntity::class,
    ]
)
@TypeConverters(
    MediaTypeConverter::class,
    MediaResourceRoleConverter::class,
    CaptureSourceConverter::class,
    CaptureStatusConverter::class,
)
abstract class LifeDatabase : RoomDatabase() {
    abstract fun lifeEntityDao(): LifeEntityDao
    abstract fun lifeRelationDao(): LifeRelationDao
    abstract fun tagDao(): TagDao
    abstract fun mediaDao(): MediaDao
    abstract fun captureDao(): CaptureDao

    companion object {
        const val DATABASE_NAME = "life-db"
    }
}
