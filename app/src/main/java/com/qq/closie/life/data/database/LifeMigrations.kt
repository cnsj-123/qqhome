package com.qq.closie.life.data.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Explicit schema migrations for the Life OS database.
 *
 * `fallbackToDestructiveMigration()` is forbidden in this project: Life OS is a personal archive,
 * and a schema bump that silently drops the user's captures, references and plans is the one
 * failure mode there is no undoing. Every version bump therefore ships a hand-written [Migration]
 * here, and the resulting schema is exported to `app/schemas/` and committed.
 */
object LifeMigrations {

    /**
     * v1 → v2 (Life OS v0.3.0).
     *
     * Three changes, all additive:
     *
     *  1. `capture_items` gains `displayTitle` and `note` — the record screen lets the user edit a
     *     capture, and these are real columns rather than keys inside a JSON blob so SQL can index,
     *     search and migrate them.
     *  2. `reference_items` — the curated 资料库 content, backed 1:1 by a LifeEntity.
     *  3. `plan_items` — the 计划 first version, likewise backed by a LifeEntity.
     *
     * No existing column is dropped, renamed or retyped, so every v1 row survives verbatim. The
     * SQL below is copied from Room's own generated `createSql` (see app/schemas/…/2.json) rather
     * than hand-invented, so a migrated database and a freshly created one are byte-identical in
     * structure — which is exactly what `LifeDatabaseMigrationTest` asserts.
     */
    val MIGRATION_1_2: Migration = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // --- 1. capture_items: two explicit, nullable columns -------------------------------
            // Nullable and without a default so old rows keep meaning "the user never set this",
            // which is different from an empty string.
            db.execSQL("ALTER TABLE `capture_items` ADD COLUMN `displayTitle` TEXT")
            db.execSQL("ALTER TABLE `capture_items` ADD COLUMN `note` TEXT")

            // --- 2. reference_items ------------------------------------------------------------
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `reference_items` (
                    `id` TEXT NOT NULL,
                    `lifeEntityId` TEXT NOT NULL,
                    `title` TEXT NOT NULL,
                    `referenceType` TEXT NOT NULL,
                    `summary` TEXT,
                    `ocrText` TEXT,
                    `sourceUrl` TEXT,
                    `sourceName` TEXT,
                    `author` TEXT,
                    `originalCaptureId` TEXT,
                    `status` TEXT NOT NULL,
                    `createdAt` INTEGER NOT NULL,
                    `updatedAt` INTEGER NOT NULL,
                    `organizedAt` INTEGER,
                    PRIMARY KEY(`id`)
                )
                """.trimIndent()
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_reference_items_lifeEntityId` " +
                    "ON `reference_items` (`lifeEntityId`)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_reference_items_status` " +
                    "ON `reference_items` (`status`)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_reference_items_referenceType` " +
                    "ON `reference_items` (`referenceType`)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_reference_items_createdAt` " +
                    "ON `reference_items` (`createdAt`)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_reference_items_status_createdAt` " +
                    "ON `reference_items` (`status`, `createdAt`)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_reference_items_status_updatedAt` " +
                    "ON `reference_items` (`status`, `updatedAt`)"
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_reference_items_originalCaptureId` " +
                    "ON `reference_items` (`originalCaptureId`)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_reference_items_sourceUrl` " +
                    "ON `reference_items` (`sourceUrl`)"
            )

            // --- 3. plan_items -----------------------------------------------------------------
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `plan_items` (
                    `id` TEXT NOT NULL,
                    `lifeEntityId` TEXT NOT NULL,
                    `title` TEXT NOT NULL,
                    `note` TEXT,
                    `dueAt` INTEGER,
                    `completedAt` INTEGER,
                    `createdAt` INTEGER NOT NULL,
                    `updatedAt` INTEGER NOT NULL,
                    `sortOrder` INTEGER NOT NULL,
                    PRIMARY KEY(`id`)
                )
                """.trimIndent()
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_plan_items_lifeEntityId` " +
                    "ON `plan_items` (`lifeEntityId`)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_plan_items_completedAt` " +
                    "ON `plan_items` (`completedAt`)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_plan_items_dueAt` " +
                    "ON `plan_items` (`dueAt`)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_plan_items_dueAt_completedAt` " +
                    "ON `plan_items` (`dueAt`, `completedAt`)"
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_plan_items_createdAt` " +
                    "ON `plan_items` (`createdAt`)"
            )
        }
    }

    /** Every migration the database must be able to run, in version order. */
    val ALL: Array<Migration> = arrayOf(MIGRATION_1_2)
}
