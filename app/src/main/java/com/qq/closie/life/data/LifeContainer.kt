package com.qq.closie.life.data

import android.content.Context
import androidx.room.Room
import com.qq.closie.life.capture.CaptureItemEntity
import com.qq.closie.life.core.EntityTagCrossRef
import com.qq.closie.life.core.LifeEntityEntity
import com.qq.closie.life.core.LifeRelationEntity
import com.qq.closie.life.core.TagEntity
import com.qq.closie.life.data.database.LifeDatabase
import com.qq.closie.life.media.MediaAssetEntity
import com.qq.closie.life.media.MediaLinkEntity
import com.qq.closie.life.media.MediaResourceEntity
import com.qq.closie.life.repository.CaptureRepository
import com.qq.closie.life.repository.LifeRepository
import com.qq.closie.life.repository.MediaRepository

/**
 * Minimal, explicit dependency container for the Life OS layer.
 *
 * There is deliberately no Hilt/Koin here: the project currently has zero DI frameworks, and
 * introducing one just for three repositories would add a KSP-another-codegen surface and a
 * second source of truth for the app's wiring. This container is a plain `by lazy` singleton owned
 * by the Application, which is exactly what the existing [com.qq.closie.data.repository.LocalWardrobeRepository]
 * pattern already does for the Closie side.
 */
class LifeContainer private constructor(private val database: LifeDatabase) {

    val lifeRepository: LifeRepository by lazy { LifeRepository(database) }
    val mediaRepository: MediaRepository by lazy { MediaRepository(database) }
    val captureRepository: CaptureRepository by lazy { CaptureRepository(database) }

    companion object {
        @Volatile
        private var instance: LifeContainer? = null

        /**
         * Real on-device instance. [fallbackToDestructiveMigration] is intentionally NOT used:
         * a schema bump must have an explicit Migration, otherwise the user silently loses every
         * captured memory. Falling back to destructive migration on a personal-data app is the one
         * failure mode we cannot undo.
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
                        .build()
                    LifeContainer(db).also { instance = it }
                }
            }
        }

        /** Test-only hook: build a container on top of an already-constructed database. */
        fun forTesting(database: LifeDatabase): LifeContainer = LifeContainer(database)

        const val DATABASE_NAME = "life_os.db"

        /** Entities registered in LifeDatabase v1 — kept here so docs/tests can assert on one list. */
        val ENTITIES: List<Class<*>> = listOf(
            LifeEntityEntity::class.java,
            LifeRelationEntity::class.java,
            TagEntity::class.java,
            EntityTagCrossRef::class.java,
            MediaAssetEntity::class.java,
            MediaResourceEntity::class.java,
            MediaLinkEntity::class.java,
            CaptureItemEntity::class.java
        )
    }
}
