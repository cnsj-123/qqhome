package com.qq.closie.data.backup

import com.qq.closie.life.data.database.LifeDatabase
import com.qq.closie.life.finance.*

/** Runs inside the existing restore transaction; never opens or replaces a database. */
internal object FinanceV4BackupRecovery {
    suspend fun restore(db: LifeDatabase, saved: FinanceV4Snapshot, restoredEntryIds: Set<String>) {
        val dao = db.financeIntakeDao()
        val current = readFinanceSnapshot(db, true).v4!!
        val now = System.currentTimeMillis()
        current.events.filter { old -> saved.events.none { it.id == old.id } }.forEach {
            dao.updateEvent(it.copy(voidedAt = it.voidedAt ?: now, updatedAt = now))
        }
        reconcile(current.events, saved.events, { it.id }, dao::insertEvent, dao::updateEvent)
        restoredEntryIds.forEach { dao.clearLinks(it) }
        dao.insertLinks(saved.links)
        current.expected.filter { old -> saved.expected.none { it.id == old.id } }.forEach {
            dao.updateExpected(it.copy(cancelledAt = it.cancelledAt ?: now))
        }
        reconcile(current.expected, saved.expected, { it.id }, dao::insertExpected, dao::updateExpected)
        current.batches.filter { old -> saved.batches.none { it.id == old.id } }.forEach {
            dao.updateBatch(it.copy(status = FinanceImportStatus.UNDONE))
        }
        reconcile(current.batches, saved.batches, { it.id }, dao::insertBatch, dao::updateBatch)
        current.staging.filter { old -> saved.staging.none { it.id == old.id } }.forEach {
            dao.updateRow(it.copy(status = FinanceRowStatus.UNDONE))
        }
        reconcile(current.staging, saved.staging, { it.id }, { dao.insertRows(listOf(it)) }, dao::updateRow)
        current.proposals.filter { old -> saved.proposals.none { it.id == old.id } }.forEach {
            dao.updateProposal(it.copy(status = FinanceProposalStatus.STALE, revision = it.revision + 1))
        }
        reconcile(current.proposals, saved.proposals, { it.id }, dao::insertProposal, dao::updateProposal)
        saved.proposals.forEach { dao.clearProposalTags(it.id) }
        dao.insertProposalTags(saved.proposalTags)
        current.rules.filter { old -> saved.rules.none { it.id == old.id } }.forEach {
            dao.updateRule(it.copy(enabled = false, deletedAt = now))
        }
        reconcile(current.rules, saved.rules, { it.id }, dao::insertRule, dao::updateRule)
        saved.rules.forEach { dao.clearConditions(it.id); dao.clearActions(it.id) }
        dao.insertConditions(saved.conditions)
        dao.insertActions(saved.actions)
        val known = current.changes.map { it.id }.toSet()
        saved.changes.filter { it.id !in known }.forEach { dao.insertChange(it) }
    }

    private suspend fun <T> reconcile(current: List<T>, saved: List<T>, id: (T) -> String,
        insert: suspend (T) -> Unit, update: suspend (T) -> Unit) {
        val ids = current.map(id).toSet()
        saved.forEach { if (id(it) in ids) update(it) else insert(it) }
    }
}
