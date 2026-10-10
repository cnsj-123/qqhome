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

    /** Additive Finance F1 tables. Existing v2 rows, keys and indexes are untouched. */
    val MIGRATION_2_3: Migration = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS `finance_accounts` (
                    `id` TEXT NOT NULL,
                    `name` TEXT NOT NULL,
                    `kind` TEXT NOT NULL,
                    `currencyCode` TEXT NOT NULL,
                    `openingBalanceMinor` INTEGER NOT NULL,
                    `createdAt` INTEGER NOT NULL,
                    `updatedAt` INTEGER NOT NULL,
                    `archivedAt` INTEGER,
                    PRIMARY KEY(`id`)
                )""".trimIndent()
            )
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS `finance_categories` (
                    `id` TEXT NOT NULL,
                    `name` TEXT NOT NULL,
                    PRIMARY KEY(`id`)
                )""".trimIndent()
            )
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS `finance_tags` (
                    `id` TEXT NOT NULL,
                    `name` TEXT NOT NULL,
                    PRIMARY KEY(`id`)
                )""".trimIndent()
            )
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS `finance_entries` (
                    `id` TEXT NOT NULL,
                    `accountId` TEXT NOT NULL,
                    `direction` TEXT NOT NULL,
                    `amountMinor` INTEGER NOT NULL,
                    `description` TEXT NOT NULL,
                    `occurredAt` INTEGER NOT NULL,
                    `recordedAt` INTEGER NOT NULL,
                    `updatedAt` INTEGER NOT NULL,
                    `categoryId` TEXT,
                    `voidedAt` INTEGER,
                    PRIMARY KEY(`id`),
                    FOREIGN KEY(`accountId`) REFERENCES `finance_accounts`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION,
                    FOREIGN KEY(`categoryId`) REFERENCES `finance_categories`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION
                )""".trimIndent()
            )
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS `finance_transfers` (
                    `id` TEXT NOT NULL,
                    `outflowEntryId` TEXT NOT NULL,
                    `inflowEntryId` TEXT NOT NULL,
                    `recordedAt` INTEGER NOT NULL,
                    `updatedAt` INTEGER NOT NULL,
                    `voidedAt` INTEGER,
                    PRIMARY KEY(`id`),
                    FOREIGN KEY(`outflowEntryId`) REFERENCES `finance_entries`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION,
                    FOREIGN KEY(`inflowEntryId`) REFERENCES `finance_entries`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION
                )""".trimIndent()
            )
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS `finance_entry_tags` (
                    `entryId` TEXT NOT NULL,
                    `tagId` TEXT NOT NULL,
                    PRIMARY KEY(`entryId`,
                    `tagId`),
                    FOREIGN KEY(`entryId`) REFERENCES `finance_entries`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION,
                    FOREIGN KEY(`tagId`) REFERENCES `finance_tags`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION
                )""".trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_finance_categories_name` ON `finance_categories` (`name`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_finance_tags_name` ON `finance_tags` (`name`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_finance_entries_accountId` ON `finance_entries` (`accountId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_finance_entries_categoryId` ON `finance_entries` (`categoryId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_finance_entries_occurredAt` ON `finance_entries` (`occurredAt`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_finance_entries_voidedAt` ON `finance_entries` (`voidedAt`)")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_finance_transfers_outflowEntryId` ON `finance_transfers` (`outflowEntryId`)")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_finance_transfers_inflowEntryId` ON `finance_transfers` (`inflowEntryId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_finance_entry_tags_tagId` ON `finance_entry_tags` (`tagId`)")
        }
    }

    /** Every migration the database must be able to run, in version order. */
    val ALL: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
}
