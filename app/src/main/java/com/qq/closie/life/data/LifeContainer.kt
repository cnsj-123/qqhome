package com.qq.closie.life.data

import android.content.Context
import androidx.room.Room
import com.qq.closie.life.capture.CaptureItemEntity
import com.qq.closie.life.core.EntityTagCrossRef
import com.qq.closie.life.core.LifeEntityEntity
import com.qq.closie.life.core.LifeRelationEntity
import com.qq.closie.life.core.TagEntity
import com.qq.closie.life.data.database.LifeDatabase
import com.qq.closie.life.data.database.LifeMigrations
import com.qq.closie.life.media.MediaAssetEntity
import com.qq.closie.life.media.MediaLinkEntity
import com.qq.closie.life.media.MediaResourceEntity
import com.qq.closie.life.media.MediaStoreImporter
import com.qq.closie.life.plan.PlanItemEntity
import com.qq.closie.life.reference.ReferenceImporter
import com.qq.closie.life.reference.ReferenceItemEntity
import com.qq.closie.life.repository.CaptureRepository
import com.qq.closie.life.repository.LifeRepository
import com.qq.closie.life.repository.MediaRepository
import com.qq.closie.life.repository.PlanRepository
import com.qq.closie.life.repository.ReferenceRepository
import com.qq.closie.life.web.WebMetadataReader

/**
 * Minimal, explicit dependency container for the Life OS layer.
 *
 * There is deliberately no Hilt/Koin here: the project currently has zero DI frameworks, and
 * introducing one just for a handful of repositories would add a KSP-another-codegen surface and a
 * second source of truth for the app's wiring. This container is a plain `by lazy` singleton owned
 * by the Application, which is exactly what the existing [com.qq.closie.data.repository.LocalWardrobeRepository]
 * pattern already does for the Closie side.
 */
class LifeContainer private constructor(
    private val database: LifeDatabase,
    /**
     * Application context, held only for the collaborators that genuinely need one —
     * [MediaStoreImporter] and [ReferenceImporter] both decode bitmaps and read content URIs.
     *
     * The *application* context, never an Activity: a container outlives every Activity in the
     * process, and holding an Activity here would leak it for the lifetime of the app. The
     * container is a process singleton, so this single long-lived reference is expected and safe.
     */
    private val appContext: Context,
) {

    /**
     * The Life OS database, exposed so 备份与恢复 can snapshot it.
     *
     * The container keeps `database` private and hands out repositories; this one accessor exists
     * because a backup is inherently a *whole-database* operation that no single repository owns.
     * Exposing the database rather than adding a `backup()` method to each repository keeps the
     * backup logic in one place ([com.qq.closie.data.backup.BackupManager]) instead of smeared
     * across four.
     */
    val lifeDatabase: LifeDatabase get() = database

    val lifeRepository: LifeRepository by lazy { LifeRepository(database) }
    val mediaRepository: MediaRepository by lazy { MediaRepository(database) }
    val captureRepository: CaptureRepository by lazy { CaptureRepository(database) }

    /** 资料库 — the curated reference archive (v0.3.0). */
    val referenceRepository: ReferenceRepository by lazy {
        ReferenceRepository(database, lifeRepository, mediaRepository)
    }

    /** 计划 — the lightweight plan list (v0.3.0). */
    val planRepository: PlanRepository by lazy { PlanRepository(database, lifeRepository) }

    /** Copies a picked gallery image into Life OS-managed storage and records it as media. */
    val mediaStoreImporter: MediaStoreImporter by lazy {
        MediaStoreImporter(database, mediaRepository)
    }

    /** Fetches a page's title/description/site name for link capture. Never throws. */
    val webMetadataReader: WebMetadataReader by lazy { WebMetadataReader() }

    /**
     * Turns a screenshot / photo / link / record into a 资料库 entry, OCR and metadata included.
     *
     * Needs the Application context because it decodes bitmaps and reads content URIs; that is why
     * the container keeps it rather than letting a ViewModel construct one.
     */
    val referenceImporter: ReferenceImporter by lazy {
        ReferenceImporter(
            context = appContext,
            database = database,
            mediaStoreImporter = mediaStoreImporter,
            referenceRepository = referenceRepository,
            captureRepository = captureRepository,
            webMetadataReader = webMetadataReader
        )
    }

    companion object {
        @Volatile
        private var instance: LifeContainer? = null

        /**
         * Real on-device instance. [fallbackToDestructiveMigration] is intentionally NOT used:
         * a schema bump must have an explicit Migration, otherwise the user silently loses every
         * captured memory. Falling back to destructive migration on a personal-data app is the one
         * failure mode we cannot undo — so [LifeMigrations.ALL] is passed in explicitly instead.
         *
         * enableMultiInstanceInvalidation() is deliberately NOT used either. This app is single-
         * process and opens exactly ONE RoomDatabase per process (the lazy [instance] below), so
         * cross-instance invalidation has nothing to do — but it does cost a bind of Room's
         * MultiInstanceInvalidationService on every database open, an extra asynchronous step on
         * the startup path, and a whole service component whose failure modes (an aggressive OEM
         * background-service policy, a half-completed bind during process restore) exist for zero
         * benefit. The smoke test surfaced exactly that: the invalidation client's
         * onServiceConnected blew up under Robolectric and kept the main looper never-idle.
         * One process, one instance — plain databaseBuilder is the correct, quiet choice.
         */
        fun getInstance(context: Context): LifeContainer {
            return instance ?: synchronized(this) {
                instance ?: run {
                    val db = Room.databaseBuilder(
                        context.applicationContext,
                        LifeDatabase::class.java,
                        DATABASE_NAME
                    )
                        .addMigrations(*LifeMigrations.ALL)
                        .build()
                    LifeContainer(db, context.applicationContext).also { instance = it }
                }
            }
        }

        /**
         * The database filename, aliased to [LifeDatabase.DATABASE_NAME].
         *
         * Kept as a `val` alias rather than deleted outright so existing references keep compiling,
         * but it is now *derived* and can no longer drift. This constant previously held its own
         * literal `"life_os.db"` while [LifeDatabase.DATABASE_NAME] held `"life-db"` — two
         * independent strings naming one database, which is precisely how the backup bug survived
         * review: each site looked correct on its own.
         */
        val DATABASE_NAME: String get() = LifeDatabase.DATABASE_NAME

        /** Entities registered in LifeDatabase v2 — kept here so docs/tests can assert on one list. */
        val ENTITIES: List<Class<*>> = listOf(
            LifeEntityEntity::class.java,
            LifeRelationEntity::class.java,
            TagEntity::class.java,
            EntityTagCrossRef::class.java,
            MediaAssetEntity::class.java,
            MediaResourceEntity::class.java,
            MediaLinkEntity::class.java,
            CaptureItemEntity::class.java,
            ReferenceItemEntity::class.java,
            PlanItemEntity::class.java
        )
    }
}
