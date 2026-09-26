package com.qq.closie.life.data.database

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Proves the v1 → v2 migration actually preserves user data.
 *
 * Why this test exists at all: `fallbackToDestructiveMigration()` is forbidden in Life OS (see
 * [LifeMigrations]), so every version bump is a hand-written `ALTER`/`CREATE` pair. A typo in one of
 * those statements does not fail the build — Room only discovers the mismatch at runtime, on the
 * user's phone, when the app opens an already-populated database. That is the one class of bug that
 * ships silently and destroys real content, so it gets a test rather than a code review.
 *
 * Two separate guarantees are checked:
 *
 *  1. **Row survival.** A v1 database is populated with real rows, migrated, then read back. The
 *     migration could be structurally perfect and still drop data if it recreated a table without
 *     copying it.
 *  2. **Structural equality with a fresh install.** After migrating, the schema is compared against
 *     a database Room creates from scratch at v2. If the two diverge — a missing index, a column
 *     with the wrong nullability — then migrating users and new users would have subtly different
 *     databases, which is exactly the kind of drift that breaks later migrations.
 *
 * Robolectric is pinned to API 34 because version 4.12.2 does not support 35 (the app's targetSdk).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LifeDatabaseMigrationTest {

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        LifeDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    private var context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @After
    fun tearDown() {
        // MigrationTestHelper closes the databases it hands out; nothing else to release here.
    }

    // ------------------------------------------------------------------
    //  1. Data survives the migration
    // ------------------------------------------------------------------

    @Test
    fun migrate1To2_keepsExistingCaptureRows() {
        // v1 capture_items columns, spelled out rather than interpolated from the entity: if someone
        // renames an entity field, this test must fail loudly instead of silently tracking the change.
        // The full v1 column list is id / source / status / rawText / sourceUrl /
        // primaryMediaAssetId / createdAt / updatedAt / errorMessage (see app/schemas/…/1.json).
        helper.createDatabase(TEST_DB, 1).use { db ->
            db.execSQL(
                """
                INSERT INTO capture_items
                    (id, source, status, rawText, sourceUrl, primaryMediaAssetId,
                     createdAt, updatedAt, errorMessage)
                VALUES
                    ('cap-1', 'MANUAL', 'READY', '买 12 号洗衣液', NULL, NULL,
                     1700000000000, 1700000000000, NULL)
                """.trimIndent()
            )
            db.execSQL(
                """
                INSERT INTO capture_items
                    (id, source, status, rawText, sourceUrl, primaryMediaAssetId,
                     createdAt, updatedAt, errorMessage)
                VALUES
                    ('cap-2', 'SHARE_SHEET', 'READY', 'https://example.com/article',
                     'https://example.com/article', NULL, 1700000001000, 1700000001000, NULL)
                """.trimIndent()
            )
            db.execSQL(
                """
                INSERT INTO capture_items
                    (id, source, status, rawText, sourceUrl, primaryMediaAssetId,
                     createdAt, updatedAt, errorMessage)
                VALUES
                    ('cap-3', 'SCREENSHOT', 'FAILED', '', NULL, NULL,
                     1700000002000, 1700000002000, '识别失败')
                """.trimIndent()
            )
        }

        val migrated = helper.runMigrationsAndValidate(TEST_DB, 2, true, *LifeMigrations.ALL)

        migrated.query(
            "SELECT id, rawText, status, sourceUrl, errorMessage, displayTitle, note " +
                "FROM capture_items ORDER BY id"
        ).use { c ->
            assertThat(c.count).isEqualTo(3)

            assertThat(c.moveToFirst()).isTrue()
            assertThat(c.getString(0)).isEqualTo("cap-1")
            assertThat(c.getString(1)).isEqualTo("买 12 号洗衣液")
            assertThat(c.getString(2)).isEqualTo("READY")
            assertThat(c.isNull(3)).isTrue()
            assertThat(c.isNull(4)).isTrue()
            // The two new columns must exist and read back as NULL, not "" — "the user never titled
            // this" is genuinely different from "the user typed an empty title".
            assertThat(c.isNull(5)).isTrue()
            assertThat(c.isNull(6)).isTrue()

            assertThat(c.moveToNext()).isTrue()
            assertThat(c.getString(0)).isEqualTo("cap-2")
            assertThat(c.getString(1)).isEqualTo("https://example.com/article")
            assertThat(c.getString(3)).isEqualTo("https://example.com/article")
            assertThat(c.isNull(5)).isTrue()
            assertThat(c.isNull(6)).isTrue()

            assertThat(c.moveToNext()).isTrue()
            // A failed capture keeps its error message across the migration — this is exactly the
            // row a user cares about not losing, because it is the one they have to retry.
            assertThat(c.getString(0)).isEqualTo("cap-3")
            assertThat(c.getString(2)).isEqualTo("FAILED")
            assertThat(c.getString(4)).isEqualTo("识别失败")

            assertThat(c.moveToNext()).isFalse()
        }
    }

    @Test
    fun migrate1To2_keepsLifeEntitiesAndTheirRevisions() {
        helper.createDatabase(TEST_DB, 1).use { db ->
            db.execSQL(
                """
                INSERT INTO life_entities (id, entityType, createdAt, updatedAt, deletedAt, revision)
                VALUES ('ent-1', 'wardrobe_item', 1000, 2000, NULL, 3)
                """.trimIndent()
            )
            db.execSQL(
                """
                INSERT INTO life_entities (id, entityType, createdAt, updatedAt, deletedAt, revision)
                VALUES ('ent-2', 'plant', 1000, 1500, 1800, 2)
                """.trimIndent()
            )
        }

        val migrated = helper.runMigrationsAndValidate(TEST_DB, 2, true, *LifeMigrations.ALL)

        migrated.query("SELECT id, entityType, revision, deletedAt FROM life_entities ORDER BY id").use { c ->
            assertThat(c.count).isEqualTo(2)

            assertThat(c.moveToFirst()).isTrue()
            assertThat(c.getString(0)).isEqualTo("ent-1")
            assertThat(c.getString(1)).isEqualTo("wardrobe_item")
            assertThat(c.getLong(2)).isEqualTo(3L)
            assertThat(c.isNull(3)).isTrue()

            assertThat(c.moveToNext()).isTrue()
            assertThat(c.getString(0)).isEqualTo("ent-2")
            // A soft-deleted row must stay soft-deleted, with its tombstone timestamp intact.
            assertThat(c.getLong(3)).isEqualTo(1800L)
        }
    }

    // ------------------------------------------------------------------
    //  2. The new tables are usable, not merely present
    // ------------------------------------------------------------------

    @Test
    fun migrate1To2_newTablesAcceptRows() {
        helper.createDatabase(TEST_DB, 1).close()

        val migrated = helper.runMigrationsAndValidate(TEST_DB, 2, true, *LifeMigrations.ALL)

        migrated.execSQL(
            """
            INSERT INTO reference_items
                (id, lifeEntityId, title, referenceType, summary, ocrText, sourceUrl, sourceName,
                 author, originalCaptureId, status, createdAt, updatedAt, organizedAt)
            VALUES
                ('ref-1', 'ent-1', 'React 19 的新 hook', 'ARTICLE', '一篇讲 use() 的文章', NULL,
                 'https://example.com/react', 'example.com', NULL, 'cap-2', 'INBOX', 1000, 1000, NULL)
            """.trimIndent()
        )
        migrated.execSQL(
            """
            INSERT INTO plan_items
                (id, lifeEntityId, title, note, dueAt, completedAt, createdAt, updatedAt, sortOrder)
            VALUES
                ('plan-1', 'ent-1', '换季收衣服', NULL, 1699999999999, NULL, 1000, 1000, 0)
            """.trimIndent()
        )

        migrated.query("SELECT title, referenceType, status FROM reference_items").use { c ->
            assertThat(c.moveToFirst()).isTrue()
            assertThat(c.getString(0)).isEqualTo("React 19 的新 hook")
            assertThat(c.getString(1)).isEqualTo("ARTICLE")
            assertThat(c.getString(2)).isEqualTo("INBOX")
        }
        migrated.query("SELECT title, dueAt, completedAt FROM plan_items").use { c ->
            assertThat(c.moveToFirst()).isTrue()
            assertThat(c.getString(0)).isEqualTo("换季收衣服")
            assertThat(c.getLong(1)).isEqualTo(1699999999999L)
            assertThat(c.isNull(2)).isTrue()
        }
    }

    // ------------------------------------------------------------------
    //  3. Migrated schema == freshly created schema
    // ------------------------------------------------------------------

    /**
     * The `lifeEntityId` indexes on both v2 tables must be UNIQUE after a migration, not merely
     * present.
     *
     * This is the bug #12 regression guard. The index existed from the start, so a test that only
     * checked "the index is there" passed while the constraint it was supposed to enforce did not —
     * and the constraint is the entire reason it exists. A reference and its LifeEntity are 1:1 (see
     * [com.qq.closie.life.reference.ReferenceItemEntity]); a non-unique index lets a second reference
     * point at an entity another one already owns, and from then on every tag / media / relation
     * lookup for that entity returns the wrong row's data. No query errors — the life graph just
     * silently lies.
     *
     * Checked on the *migrated* database specifically, because the migration is a hand-written SQL
     * string: it is entirely possible for the entity annotation to say `unique = true` while the
     * MIGRATION_1_2 DDL still says `CREATE INDEX` (not `CREATE UNIQUE INDEX`), and only devices that
     * upgraded would have the wrong schema.
     */
    @Test
    fun migrate1To2_lifeEntityIdIndexesAreUnique() {
        helper.createDatabase(TEST_DB, 1).close()
        val migrated = helper.runMigrationsAndValidate(TEST_DB, 2, true, *LifeMigrations.ALL)

        listOf("reference_items", "plan_items").forEach { table ->
            val indexName = "index_${table}_lifeEntityId"
            migrated.query("PRAGMA index_list(`$table`)").use { c ->
                val nameIdx = c.getColumnIndex("name")
                val uniqueIdx = c.getColumnIndex("unique")
                var found = false
                var unique = -1
                while (c.moveToNext()) {
                    if (c.getString(nameIdx) == indexName) {
                        found = true
                        unique = c.getInt(uniqueIdx)
                    }
                }
                assertThat(found).isTrue()
                assertThat(unique).isEqualTo(1)
            }
        }
    }

    /**
     * And the constraint actually bites: inserting a second reference (or plan) pointing at an
     * already-owned LifeEntity must be rejected by SQLite itself.
     *
     * The previous test proves the schema says UNIQUE; this one proves the schema *does* what it
     * says. Checking both matters because `PRAGMA index_list` reports the DDL, while a failing insert
     * exercises the code path a real duplicate would take.
     */
    @Test
    fun migrate1To2_duplicateLifeEntityIdIsRejected() {
        helper.createDatabase(TEST_DB, 1).close()
        val migrated = helper.runMigrationsAndValidate(TEST_DB, 2, true, *LifeMigrations.ALL)

        fun insertReference(id: String, lifeEntityId: String) {
            migrated.execSQL(
                """
                INSERT INTO reference_items
                    (id, lifeEntityId, title, referenceType, summary, ocrText, sourceUrl, sourceName,
                     author, originalCaptureId, status, createdAt, updatedAt, organizedAt)
                VALUES
                    ('$id', '$lifeEntityId', '标题', 'NOTE', NULL, NULL, NULL, NULL,
                     NULL, NULL, 'INBOX', 1000, 1000, NULL)
                """.trimIndent()
            )
        }

        insertReference("ref-a", "ent-shared")

        val threw = runCatching { insertReference("ref-b", "ent-shared") }.exceptionOrNull()
        assertThat(threw).isNotNull()
    }

    /**
     * One capture may produce at most one organised reference, enforced by the database.
     *
     * The importer already checks `findByOriginalCaptureId` before creating, but a check is not a
     * guarantee: two coroutines can both read "not filed yet" before either writes. Without a UNIQUE
     * index the user ends up with two references for one capture — each needing separate tagging and
     * deletion, with nothing to distinguish them. The index makes SQLite the arbiter, so the losing
     * writer fails loudly instead of the data quietly doubling.
     *
     * Soft-deleted and hand-written references are unaffected: SQLite treats multiple NULLs in a
     * UNIQUE index as distinct, so any number of references may have `originalCaptureId = NULL`.
     */
    @Test
    fun migrate1To2_originalCaptureIdIndexIsUnique() {
        helper.createDatabase(TEST_DB, 1).close()
        val migrated = helper.runMigrationsAndValidate(TEST_DB, 2, true, *LifeMigrations.ALL)

        val indexName = "index_reference_items_originalCaptureId"
        migrated.query("PRAGMA index_list(`reference_items`)").use { c ->
            val nameIdx = c.getColumnIndex("name")
            val uniqueIdx = c.getColumnIndex("unique")
            var found = false
            var unique = -1
            while (c.moveToNext()) {
                if (c.getString(nameIdx) == indexName) {
                    found = true
                    unique = c.getInt(uniqueIdx)
                }
            }
            assertThat(found).isTrue()
            assertThat(unique).isEqualTo(1)
        }
    }

    @Test
    fun migrate1To2_duplicateOriginalCaptureIdIsRejected_butNullsAreFine() {
        helper.createDatabase(TEST_DB, 1).close()
        val migrated = helper.runMigrationsAndValidate(TEST_DB, 2, true, *LifeMigrations.ALL)

        fun insertReference(id: String, captureId: String?) {
            val capture = captureId?.let { "'$it'" } ?: "NULL"
            migrated.execSQL(
                """
                INSERT INTO reference_items
                    (id, lifeEntityId, title, referenceType, summary, ocrText, sourceUrl, sourceName,
                     author, originalCaptureId, status, createdAt, updatedAt, organizedAt)
                VALUES
                    ('$id', '$id-ent', '标题', 'NOTE', NULL, NULL, NULL, NULL,
                     NULL, $capture, 'INBOX', 1000, 1000, NULL)
                """.trimIndent()
            )
        }

        // Two references may share a capture id only if the database forbids a third.
        insertReference("ref-x", "cap-shared")
        val threw = runCatching { insertReference("ref-y", "cap-shared") }.exceptionOrNull()
        assertThat(threw).isNotNull()

        // Multiple NULLs are allowed — hand-written references have no source capture, and there is
        // no limit on how many of those a user may create.
        insertReference("ref-plain-1", null)
        insertReference("ref-plain-2", null)
        migrated.query("SELECT COUNT(*) FROM reference_items WHERE originalCaptureId IS NULL").use { c ->
            c.moveToFirst()
            assertThat(c.getInt(0)).isEqualTo(2)
        }
    }

    @Test
    fun migratedSchema_matchesFreshlyCreatedSchema() {
        helper.createDatabase(TEST_DB, 1).close()
        val migrated = helper.runMigrationsAndValidate(TEST_DB, 2, true, *LifeMigrations.ALL)
        val migratedSchema = schemaFingerprint(migrated)
        migrated.close()

        // A brand-new v2 database, built by Room itself from the current entities.
        val fresh = Room.databaseBuilder(context, LifeDatabase::class.java, FRESH_DB)
            .allowMainThreadQueries()
            .build()
        val freshSchema = schemaFingerprint(fresh.openHelper.writableDatabase)
        fresh.close()

        assertThat(migratedSchema).isEqualTo(freshSchema)
    }

    /**
     * A comparable description of a database's structure.
     *
     * Deliberately NOT a comparison of raw `sqlite_master.sql` text. That text differs between a
     * migrated and a freshly created database for two reasons, neither of which is a defect:
     *
     *  1. **Column order.** `ALTER TABLE … ADD COLUMN` appends, so a migrated `capture_items` ends
     *     `…, errorMessage, displayTitle, note`, while a fresh one follows the entity's declaration
     *     order (`displayTitle, note, …, errorMessage`). SQLite addresses columns by name; order has
     *     no semantic weight, and Room's own validation (run by [MigrationTestHelper]) already
     *     confirms the migrated schema is acceptable.
     *  2. **Formatting.** The migration's SQL is written by hand with indentation; Room's generated
     *     SQL is on one line.
     *
     * So the fingerprint is the *structure*: for each table, its column names and types as a set,
     * plus its indexes by name and column list. That is what "structurally identical" actually
     * means, and it still catches the failures that matter — a missing column, a wrong type, a
     * dropped index, a column that should be NOT NULL but is not.
     */
    private fun schemaFingerprint(db: SupportSQLiteDatabase): List<String> {
        val tables = mutableListOf<String>()

        db.query(
            "SELECT name FROM sqlite_master WHERE type = 'table' " +
                "AND name NOT LIKE 'sqlite_%' AND name != 'android_metadata' ORDER BY name"
        ).use { c ->
            while (c.moveToNext()) tables += c.getString(0)
        }

        val fingerprint = mutableListOf<String>()
        for (table in tables) {
            // Columns as a sorted set of "name type notnull".
            val columns = mutableListOf<String>()
            db.query("PRAGMA table_info(`$table`)").use { c ->
                val nameIdx = c.getColumnIndex("name")
                val typeIdx = c.getColumnIndex("type")
                val notNullIdx = c.getColumnIndex("notnull")
                val pkIdx = c.getColumnIndex("pk")
                while (c.moveToNext()) {
                    columns += "${c.getString(nameIdx)} ${c.getString(typeIdx)} " +
                        "notnull=${c.getInt(notNullIdx)} pk=${c.getInt(pkIdx)}"
                }
            }
            fingerprint += "table|$table|cols:${columns.sorted().joinToString(",")}"

            // Indexes as a sorted set of "name on columns".
            val indexes = mutableListOf<String>()
            db.query("PRAGMA index_list(`$table`)").use { c ->
                val nameIdx = c.getColumnIndex("name")
                val uniqueIdx = c.getColumnIndex("unique")
                val indexNames = mutableListOf<Pair<String, Int>>()
                while (c.moveToNext()) {
                    indexNames += c.getString(nameIdx) to c.getInt(uniqueIdx)
                }
                for ((name, unique) in indexNames) {
                    val cols = mutableListOf<String>()
                    db.query("PRAGMA index_info(`$name`)").use { ic ->
                        val colNameIdx = ic.getColumnIndex("name")
                        while (ic.moveToNext()) cols += ic.getString(colNameIdx) ?: ""
                    }
                    indexes += "$name unique=$unique on:${cols.joinToString(",")}"
                }
            }
            fingerprint += "table|$table|idx:${indexes.sorted().joinToString(",")}"
        }

        return fingerprint.sorted()
    }

    private companion object {
        const val TEST_DB = "life-os-migration-test.db"
        const val FRESH_DB = "life-os-fresh-test.db"
    }
}
