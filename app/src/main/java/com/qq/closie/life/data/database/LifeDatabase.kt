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
import com.qq.closie.life.data.database.dao.PlanDao
import com.qq.closie.life.data.database.dao.ReferenceDao
import com.qq.closie.life.data.database.dao.TagDao
import com.qq.closie.life.media.MediaAssetEntity
import com.qq.closie.life.media.MediaLinkEntity
import com.qq.closie.life.media.MediaResourceEntity
import com.qq.closie.life.media.MediaResourceRoleConverter
import com.qq.closie.life.media.MediaTypeConverter
import com.qq.closie.life.plan.PlanItemEntity
import com.qq.closie.life.reference.ReferenceItemEntity
import com.qq.closie.life.reference.ReferenceStatusConverter
import com.qq.closie.life.reference.ReferenceTypeConverter

/**
 * The single Room database for Life OS.
 *
 * Version 2 as of Life OS v0.3.0. Schema changes MUST ship an explicit
 * [androidx.room.migration.Migration] (see [LifeMigrations]) — `fallbackToDestructiveMigration()`
 * is forbidden. Life OS is a long-lived personal database and must never lose user data on a schema
 * change.
 *
 * Schema export is enabled via the project-level KSP block in app/build.gradle.kts
 * (`room.schemaLocation` → app/schemas/), which is committed to the repository.
 */
@Database(
    version = 2,
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
        // --- v2 (v0.3.0) ---
        ReferenceItemEntity::class,
        PlanItemEntity::class,
    ]
)
@TypeConverters(
    MediaTypeConverter::class,
    MediaResourceRoleConverter::class,
    CaptureSourceConverter::class,
    CaptureStatusConverter::class,
    // --- v2 (v0.3.0) ---
    ReferenceTypeConverter::class,
    ReferenceStatusConverter::class,
)
abstract class LifeDatabase : RoomDatabase() {
    abstract fun lifeEntityDao(): LifeEntityDao
    abstract fun lifeRelationDao(): LifeRelationDao
    abstract fun tagDao(): TagDao
    abstract fun mediaDao(): MediaDao
    abstract fun captureDao(): CaptureDao
    abstract fun referenceDao(): ReferenceDao
    abstract fun planDao(): PlanDao

    companion object {
        /**
         * The one and only Life OS database filename. **Single source of truth.**
         *
         * This constant used to read `"life-db"` while [com.qq.closie.life.data.LifeContainer] opened
         * the database under its own private `"life_os.db"`. Two names for one database is not a
         * cosmetic disagreement: the name that *actually* matters is the one passed to
         * `Room.databaseBuilder`, because that is the file Room creates and writes. `"life_os.db"`
         * has been the real file since v0.2 shipped, so it is the name that must win — renaming it
         * now would strand every existing user's data in an orphaned file.
         *
         * The concrete damage the mismatch caused was in backup: `BackupManager` looked the file up
         * via `context.getDatabasePath(LifeDatabase.DATABASE_NAME)`, i.e. it asked for `life-db`,
         * which does not exist. `dbFile.exists()` was therefore false and the Life OS database was
         * **silently omitted from every v2 backup** — a backup that reports success while leaving
         * out the entire Life OS half of the app. Restoring it on a new phone would hand the user
         * back an empty timeline and an empty 资料库, with no error anywhere to explain it.
         *
         * Consumers must reference this constant rather than repeating the string; `LifeContainer`
         * and `BackupManager` both do. A test asserts the two call sites and this value agree.
         */
        const val DATABASE_NAME = "life_os.db"

        /** Current schema version — kept in sync with the @Database annotation by test. */
        const val VERSION = 2
    }
}
