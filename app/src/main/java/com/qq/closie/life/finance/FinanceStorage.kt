package com.qq.closie.life.finance

import com.qq.closie.life.data.database.LifeDatabase

/** Caller owns the existing restore gate and one DB transaction. */
internal suspend fun readFinanceSnapshot(database: LifeDatabase, includeIntake: Boolean = false): FinanceSnapshot {
    val dao = database.financeDao()
    val intake = database.financeIntakeDao()
    val v4 = FinanceV4Snapshot(events = intake.events(), links = intake.links(), expected = intake.expected())
    return FinanceSnapshot(dao.accounts(), dao.entries(), dao.transfers(), dao.categories(), dao.tags(), dao.entryTags(),
        if (!includeIntake) v4 else v4.copy(batches = intake.batches(), staging = intake.staging(),
            proposals = intake.proposals(), proposalTags = intake.proposalTags(), rules = intake.rules(),
            conditions = intake.conditions(), actions = intake.actions(), changes = intake.changes()))
}
