package com.qq.closie.data.backup

import com.qq.closie.life.data.database.LifeDatabase
import com.qq.closie.life.finance.*

/** Only the existing authorized recovery transaction calls this; no user-level canonical DELETE. */
internal object FinanceBackupRecovery {
    suspend fun restore(database: LifeDatabase, saved: FinanceSnapshot) {
        FinanceIntegrity.validate(saved)
        val dao = database.financeDao()
        val current = FinanceSnapshot(dao.accounts(), dao.entries(), dao.transfers(), dao.categories(), dao.tags(), dao.entryTags())
        val now = System.currentTimeMillis()
        // Reconcile a full Finance snapshot by preserving retired rows, including rollback of an
        // interrupted restore. A legacy payload has finance=null and never enters this owner.
        current.transfers.filter { old -> saved.transfers.none { it.id == old.id } }.forEach {
            dao.updateTransfer(it.copy(voidedAt = it.voidedAt ?: now, updatedAt = now))
        }
        current.entries.filter { old -> saved.entries.none { it.id == old.id } }.forEach {
            dao.updateEntry(it.copy(voidedAt = it.voidedAt ?: now, updatedAt = now))
        }
        current.accounts.filter { old -> saved.accounts.none { it.id == old.id } }.forEach {
            dao.updateAccount(it.copy(archivedAt = it.archivedAt ?: now, updatedAt = now))
        }
        saved.accounts.forEach { row ->
            val old = current.accounts.find { it.id == row.id }
            require(old == null || old.currencyCode == row.currencyCode || current.entries.none { it.accountId == row.id }) {
                "恢复不能改变已有历史流水的币种"
            }
            if (old == null) dao.insertAccount(row) else dao.updateAccount(row)
        }
        saved.categories.sortedBy { it.parentId != null }.forEach { if (current.categories.any { old -> old.id == it.id }) dao.updateCategory(it) else dao.insertCategory(it) }
        saved.tags.forEach { if (current.tags.any { old -> old.id == it.id }) dao.updateTag(it) else dao.insertTag(it) }
        saved.entries.forEach { if (current.entries.any { old -> old.id == it.id }) dao.updateEntry(it) else dao.insertEntry(it) }
        saved.transfers.forEach { if (current.transfers.any { old -> old.id == it.id }) dao.updateTransfer(it) else dao.insertTransfer(it) }
        saved.entries.forEach { dao.clearEntryTags(it.id) }
        dao.insertEntryTags(saved.entryTags)
        saved.v4?.let { FinanceV4BackupRecovery.restore(database, it, saved.entries.map { row -> row.id }.toSet()) }
        FinanceIntegrity.validate(readFinanceSnapshot(database, true))
    }
}
