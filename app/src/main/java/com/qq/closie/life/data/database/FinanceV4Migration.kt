package com.qq.closie.life.data.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Additive expansion only. Existing balances retain their original F1 anchor. */
val MIGRATION_3_4: Migration = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE finance_accounts ADD COLUMN balanceAnchorAt INTEGER NOT NULL DEFAULT 0")
        db.execSQL("UPDATE finance_accounts SET balanceAnchorAt = createdAt")
        db.execSQL("ALTER TABLE finance_accounts ADD COLUMN importBatchId TEXT")
        db.execSQL("ALTER TABLE finance_categories ADD COLUMN parentId TEXT")
        db.execSQL("CREATE INDEX index_finance_categories_parentId ON finance_categories(parentId)")
        db.execSQL("ALTER TABLE finance_entries ADD COLUMN statPolicy TEXT NOT NULL DEFAULT 'INCLUDE'")
        db.execSQL("ALTER TABLE finance_entries ADD COLUMN budgetPolicy TEXT NOT NULL DEFAULT 'INCLUDE'")
        db.execSQL("ALTER TABLE finance_entries ADD COLUMN revision INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE finance_entries ADD COLUMN importBatchId TEXT")
        db.execSQL("CREATE INDEX index_finance_entries_importBatchId ON finance_entries(importBatchId)")
        db.execSQL("""CREATE TABLE IF NOT EXISTS finance_events (
            id TEXT NOT NULL PRIMARY KEY, description TEXT NOT NULL, nature TEXT NOT NULL,
            currencyCode TEXT NOT NULL, occurredAt INTEGER NOT NULL, createdAt INTEGER NOT NULL,
            updatedAt INTEGER NOT NULL, personalShareMinor INTEGER, voidedAt INTEGER)""")
        db.execSQL("CREATE INDEX index_finance_events_occurredAt ON finance_events(occurredAt)")
        db.execSQL("CREATE INDEX index_finance_events_voidedAt ON finance_events(voidedAt)")
        db.execSQL("""CREATE TABLE IF NOT EXISTS finance_event_entries (
            eventId TEXT NOT NULL, entryId TEXT NOT NULL, role TEXT NOT NULL, allocatedMinor INTEGER NOT NULL,
            PRIMARY KEY(eventId, entryId),
            FOREIGN KEY(eventId) REFERENCES finance_events(id) ON UPDATE NO ACTION ON DELETE NO ACTION,
            FOREIGN KEY(entryId) REFERENCES finance_entries(id) ON UPDATE NO ACTION ON DELETE NO ACTION)""")
        db.execSQL("CREATE INDEX index_finance_event_entries_entryId ON finance_event_entries(entryId)")
        db.execSQL("""CREATE TABLE IF NOT EXISTS finance_expected_flows (
            id TEXT NOT NULL PRIMARY KEY, eventId TEXT NOT NULL, role TEXT NOT NULL, amountMinor INTEGER,
            note TEXT NOT NULL, createdAt INTEGER NOT NULL, cancelledAt INTEGER,
            FOREIGN KEY(eventId) REFERENCES finance_events(id) ON UPDATE NO ACTION ON DELETE NO ACTION)""")
        db.execSQL("CREATE INDEX index_finance_expected_flows_eventId ON finance_expected_flows(eventId)")
        db.execSQL("""CREATE TABLE IF NOT EXISTS finance_import_batches (
            id TEXT NOT NULL PRIMARY KEY, digest TEXT NOT NULL, fileName TEXT NOT NULL, createdAt INTEGER NOT NULL,
            status TEXT NOT NULL, committedAt INTEGER)""")
        db.execSQL("CREATE UNIQUE INDEX index_finance_import_batches_digest ON finance_import_batches(digest)")
        db.execSQL("""CREATE TABLE IF NOT EXISTS finance_import_rows (
            id TEXT NOT NULL PRIMARY KEY, batchId TEXT NOT NULL, rowNumber INTEGER NOT NULL,
            fingerprint TEXT NOT NULL, rawPayload TEXT NOT NULL, status TEXT NOT NULL, issue TEXT NOT NULL,
            sourceAccount TEXT NOT NULL, targetAccount TEXT NOT NULL, kind TEXT, amountMinor INTEGER,
            occurredAt INTEGER, description TEXT NOT NULL, category TEXT NOT NULL, subcategory TEXT NOT NULL,
            tagsText TEXT NOT NULL, feeMinor INTEGER NOT NULL, reimbursable INTEGER NOT NULL, hasRefund INTEGER NOT NULL,
            statPolicy TEXT NOT NULL, budgetPolicy TEXT NOT NULL, canonicalId TEXT,
            FOREIGN KEY(batchId) REFERENCES finance_import_batches(id) ON UPDATE NO ACTION ON DELETE NO ACTION)""")
        db.execSQL("CREATE INDEX index_finance_import_rows_batchId ON finance_import_rows(batchId)")
        db.execSQL("CREATE INDEX index_finance_import_rows_fingerprint ON finance_import_rows(fingerprint)")
        db.execSQL("""CREATE TABLE IF NOT EXISTS finance_proposals (
            id TEXT NOT NULL PRIMARY KEY, source TEXT NOT NULL, sourceKey TEXT NOT NULL, evidenceText TEXT NOT NULL,
            createdAt INTEGER NOT NULL, occurredAt INTEGER NOT NULL, kind TEXT NOT NULL, amountMinor INTEGER,
            description TEXT NOT NULL, accountId TEXT, targetAccountId TEXT, category TEXT NOT NULL,
            subcategory TEXT NOT NULL, nature TEXT NOT NULL, status TEXT NOT NULL, canonicalId TEXT, revision INTEGER NOT NULL)""")
        db.execSQL("CREATE UNIQUE INDEX index_finance_proposals_sourceKey ON finance_proposals(sourceKey)")
        db.execSQL("CREATE INDEX index_finance_proposals_status ON finance_proposals(status)")
        db.execSQL("CREATE INDEX index_finance_proposals_createdAt ON finance_proposals(createdAt)")
        db.execSQL("""CREATE TABLE IF NOT EXISTS finance_proposal_tags (
            proposalId TEXT NOT NULL, name TEXT NOT NULL, PRIMARY KEY(proposalId, name),
            FOREIGN KEY(proposalId) REFERENCES finance_proposals(id) ON UPDATE NO ACTION ON DELETE NO ACTION)""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS finance_rules (
            id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, priority INTEGER NOT NULL,
            enabled INTEGER NOT NULL, updatedAt INTEGER NOT NULL, deletedAt INTEGER)""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS finance_rule_conditions (
            ruleId TEXT NOT NULL, position INTEGER NOT NULL, kind TEXT NOT NULL, value TEXT NOT NULL,
            PRIMARY KEY(ruleId, position),
            FOREIGN KEY(ruleId) REFERENCES finance_rules(id) ON UPDATE NO ACTION ON DELETE NO ACTION)""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS finance_rule_actions (
            ruleId TEXT NOT NULL, position INTEGER NOT NULL, kind TEXT NOT NULL, value TEXT NOT NULL,
            PRIMARY KEY(ruleId, position),
            FOREIGN KEY(ruleId) REFERENCES finance_rules(id) ON UPDATE NO ACTION ON DELETE NO ACTION)""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS finance_changes (
            id TEXT NOT NULL PRIMARY KEY, objectId TEXT NOT NULL, operation TEXT NOT NULL,
            authority TEXT NOT NULL, createdAt INTEGER NOT NULL, batchId TEXT, beforeRevision INTEGER, afterRevision INTEGER)""")
        db.execSQL("CREATE INDEX index_finance_changes_objectId ON finance_changes(objectId)")
        db.execSQL("CREATE INDEX index_finance_changes_batchId ON finance_changes(batchId)")
    }
}
