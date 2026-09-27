package com.qq.closie.life.data

import android.content.Context
import androidx.room.Room
import com.qq.closie.data.backup.RestoreStartupGate
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
     *
     * ### Why this one accessor is deliberately **not** gated
     *
     * Every other accessor below refuses to hand anything out while recovery is unfinished (see
     * [gate]). This one cannot, and the reason is a deadlock rather than a preference: recovery *itself*
     * needs the database. It runs at startup while the gate is still BLOCKED — that is the whole point
     * of the barrier — and it obtains the live database through this accessor to replay the pre-restore
     * snapshot. Gating it would mean recovery could not open the database until recovery had finished.
     *
     * That is safe because a `LifeDatabase` is not, by itself, a view of the user's data. It is a handle:
     * it reads nothing until a query runs, and the only thing that runs during recovery is the snapshot
     * replay, which is the repair. Gating the *repositories* — the objects that actually query — is what
     * protects the user. See [gate].
     */
    val lifeDatabase: LifeDatabase get() = database

    /**
     * Fails closed unless the startup barrier has finished repairing the data.
     *
     * Business accessors call this **before** constructing their target, so a half-restored Closet is
     * never read into a repository's in-memory state. The check is shared with
     * [com.qq.closie.data.repository.LocalWardrobeRepository] via
     * [com.qq.closie.data.backup.RestoreStartupGate.requireReady] so both sides of the app make the
     * identical decision.
     */
    private fun gate() = RestoreStartupGate.requireReady()

    val lifeRepository: LifeRepository by lazy { gate(); LifeRepository(database) }
    val mediaRepository: MediaRepository by lazy { gate(); MediaRepository(database) }
    val captureRepository: CaptureRepository by lazy { gate(); CaptureRepository(database) }

    /** 资料库 — the curated reference archive (v0.3.0). */
    val referenceRepository: ReferenceRepository by lazy {
        gate()
        ReferenceRepository(database, lifeRepository, mediaRepository, captureRepository)
    }

    /** 计划 — the lightweight plan list (v0.3.0). */
    val planRepository: PlanRepository by lazy { gate(); PlanRepository(database, lifeRepository) }

    /** Copies a picked gallery image into Life OS-managed storage and records it as media. */
    val mediaStoreImporter: MediaStoreImporter by lazy {
        gate()
        MediaStoreImporter(database, mediaRepository)
    }

    /**
     * Fetches a page's title/description/site name for link capture. Never throws.
     *
     * Not gated, because it touches no user data at all: it is a stateless HTTP/metadata reader with no
     * database, no filesystem and no in-memory state to be inconsistent. Gating it would protect
     * nothing and would only add a way for a capture flow to fail for a reason that does not apply to it.
     */
    val webMetadataReader: WebMetadataReader by lazy { WebMetadataReader() }

    /**
     * Turns a screenshot / photo / link / record into a 资料库 entry, OCR and metadata included.
     *
     * Needs the Application context because it decodes bitmaps and reads content URIs; that is why
     * the container keeps it rather than letting a ViewModel construct one.
     */
    val referenceImporter: ReferenceImporter by lazy {
        gate()
        ReferenceImporter(
            context = appContext,
            database = database,
            mediaStoreImporter = mediaStoreImporter,
            referenceRepository = referenceRepository,
            captureRepository = captureRepository,
            mediaRepository = mediaRepository,
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
         * Builds a container around an arbitrary database, bypassing the process singleton.
         *
         * Exists so a test can exercise the startup gate against an in-memory database. The singleton is
         * the wrong shape for that: it is process state, so a test that went through [getInstance] would
         * share one container with every other test in the same JVM and would depend on execution order
         * to say anything about the gate. Injecting the database keeps the question local — "given a
         * container and a gate in state X, what does this accessor do?" — and leaves [instance]
         * untouched.
         *
         * `internal`, so it is not part of the app's shipped surface. It does not weaken the gate:
         * accessors are gated the same way regardless of how the container was built.
         */
        @androidx.annotation.VisibleForTesting
        internal fun createForTesting(context: Context, database: LifeDatabase): LifeContainer =
            LifeContainer(database, context.applicationContext)

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
