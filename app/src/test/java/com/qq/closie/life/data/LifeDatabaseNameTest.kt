package com.qq.closie.life.data

import com.google.common.truth.Truth.assertThat
import com.qq.closie.life.data.database.LifeDatabase
import org.junit.Test

/**
 * Proves there is exactly one Life OS database filename, and that it is the one v0.2 already used.
 *
 * ### The bug this pins down
 *
 * [LifeContainer] opened the database under its own literal `"life_os.db"` while
 * [LifeDatabase.DATABASE_NAME] said `"life-db"`. Both sites read correctly in isolation, which is
 * exactly why the mismatch survived review. The damage landed in backup: `BackupManager` resolved
 * the file through `context.getDatabasePath(LifeDatabase.DATABASE_NAME)`, asked for `life-db`,
 * found nothing, and **silently omitted the entire Life OS database from every v2 backup** while
 * still reporting success.
 *
 * ### Why the value must be `life_os.db`
 *
 * `life_os.db` is the name v0.2 shipped with, so it is the file real users already have on disk.
 * Renaming to match the constant instead would make the app open a brand-new empty database, and
 * the user's existing 记录 would become an orphaned file that nothing ever reads — data loss
 * disguised as a tidy-up. The constant had to move, not the file.
 *
 * A plain JVM test: this is a compile-time fact about constants, and asserting it here means a
 * future edit to either side fails the build's test run rather than a user's restore.
 */
class LifeDatabaseNameTest {

    @Test
    fun databaseName_isLifeOsDb_theNameV02AlreadyUsed() {
        assertThat(LifeDatabase.DATABASE_NAME).isEqualTo("life_os.db")
    }

    @Test
    fun lifeContainer_usesTheSameNameAsTheDatabaseClass() {
        // The alias exists precisely so this can never drift again; asserting the equality keeps
        // someone from "simplifying" it back into a second literal.
        assertThat(LifeContainer.DATABASE_NAME).isEqualTo(LifeDatabase.DATABASE_NAME)
    }

    @Test
    fun databaseName_isNotTheStaleLifeDbValue() {
        // The exact regression: `life-db` was never a real file. If this ever passes again, backups
        // are silently empty of Life OS data.
        assertThat(LifeDatabase.DATABASE_NAME).isNotEqualTo("life-db")
    }
}
