package com.qq.closie.life.finance

import androidx.room.withTransaction
import com.qq.closie.data.backup.RestoreStartupGate
import com.qq.closie.data.backup.gateAwareFlow
import com.qq.closie.life.data.database.LifeDatabase
import kotlinx.coroutines.flow.map
import java.util.UUID

data class FinanceAutomationSnapshot(val proposals: List<FinanceProposalEntity>, val tags: List<FinanceProposalTagEntity>,
    val rules: List<FinanceRuleSet>)
data class FinanceProposalConfirmation(val kind: FinanceProposalKind, val accountId: String, val targetAccountId: String?,
    val amountMinor: Long, val description: String, val occurredAt: Long, val category: String, val subcategory: String,
    val tags: List<String>, val event: FinanceEventInput)

/** The only path from a discovered proposal into truth is explicit confirm(), inside one Room transaction. */
class FinanceAutomationRepository(private val database: LifeDatabase, private val finance: FinanceRepository) {
    private val dao get() = database.financeIntakeDao()
    private suspend fun <T> transaction(block: suspend () -> T): T =
        RestoreStartupGate.withBusinessAccessSuspending { database.withTransaction { block() } }
    private suspend fun rules(): List<FinanceRuleSet> {
        val conditions = dao.conditions().groupBy { it.ruleId }
        val actions = dao.actions().groupBy { it.ruleId }
        return dao.rules().map { FinanceRuleSet(it, conditions[it.id].orEmpty(), actions[it.id].orEmpty()) }
    }
    fun observe() = gateAwareFlow { dao.observeChanges().map { transaction {
        FinanceAutomationSnapshot(dao.proposals(), dao.proposalTags(), rules())
    } } }
    suspend fun discover(proposal: FinanceProposalEntity): FinanceProposalEntity? = transaction {
        require(proposal.status == FinanceProposalStatus.ACTIVE && proposal.canonicalId == null && proposal.revision == 0L)
        if (dao.proposalByKey(proposal.sourceKey) != null) return@transaction null
        // Old notifications are not replayed by the listener; this also bounds delayed system deliveries.
        if (System.currentTimeMillis() - proposal.occurredAt > 24 * 60 * 60 * 1000L) return@transaction null
        val kind = if (proposal.source == FinanceSource.WECHAT) FinanceAccountKind.WECHAT else FinanceAccountKind.ALIPAY
        val suggested = database.financeDao().accounts().filter { it.kind == kind && it.currencyCode == "CNY" && it.archivedAt == null }.singleOrNull()?.id
        val result = FinanceRuleEngine.apply(proposal.copy(accountId = proposal.accountId ?: suggested), rules())
        val account = result.proposal.accountId?.let { database.financeDao().account(it) }
        val row = result.proposal.copy(accountId = account?.takeIf { it.archivedAt == null && it.currencyCode == "CNY" }?.id)
        dao.insertProposal(row)
        dao.insertProposalTags(result.tags.map { FinanceProposalTagEntity(row.id, it) })
        row
    }
    suspend fun confirm(id: String, revision: Long, input: FinanceProposalConfirmation): String = transaction {
        val proposal = requireNotNull(dao.proposal(id)) { "待确认记录已不存在" }
        if (proposal.status == FinanceProposalStatus.CONFIRMED) return@transaction requireNotNull(proposal.canonicalId)
        require(proposal.revision == revision && proposal.status in setOf(FinanceProposalStatus.ACTIVE, FinanceProposalStatus.SNOOZED)) {
            "待确认记录已改变，请重新打开"
        }
        require(input.amountMinor > 0 && input.description.isNotBlank())
        val account = requireNotNull(database.financeDao().account(input.accountId)) { "请选择账户" }
        require(account.archivedAt == null && account.currencyCode == "CNY") { "请选择有效的人民币账户" }
        val canonical = if (input.kind == FinanceProposalKind.TRANSFER) {
            finance.saveTransferInTransaction(null, input.accountId, requireNotNull(input.targetAccountId),
                input.amountMinor, input.description, input.occurredAt, authority = "USER_CONFIRMED_PROPOSAL").id
        } else finance.saveEntryInTransaction(null, input.accountId,
            if (input.kind == FinanceProposalKind.INCOME) FinanceDirection.INFLOW else FinanceDirection.OUTFLOW,
            input.amountMinor, input.description, input.occurredAt, input.category, input.tags, input.subcategory,
            input.event, authority = "USER_CONFIRMED_PROPOSAL").id
        dao.updateProposal(proposal.copy(status = FinanceProposalStatus.CONFIRMED, canonicalId = canonical, revision = revision + 1))
        FinanceIntegrity.validate(readFinanceSnapshot(database))
        canonical
    }
    suspend fun dismiss(id: String, revision: Long, status: FinanceProposalStatus) = transaction {
        require(status in setOf(FinanceProposalStatus.SNOOZED, FinanceProposalStatus.REJECTED, FinanceProposalStatus.STALE))
        val old = requireNotNull(dao.proposal(id))
        require(old.revision == revision && old.status in setOf(FinanceProposalStatus.ACTIVE, FinanceProposalStatus.SNOOZED)) { "记录状态已改变" }
        dao.updateProposal(old.copy(status = status, revision = revision + 1))
    }
    suspend fun saveRule(set: FinanceRuleSet) = transaction {
        FinanceRuleEngine.validate(set)
        require(set.conditions.all { it.ruleId == set.rule.id } && set.actions.all { it.ruleId == set.rule.id })
        val row = set.rule.copy(updatedAt = System.currentTimeMillis(), deletedAt = null)
        if (dao.rules().none { it.id == row.id }) dao.insertRule(row) else dao.updateRule(row)
        dao.clearConditions(row.id); dao.clearActions(row.id)
        dao.insertConditions(set.conditions.mapIndexed { index, c -> c.copy(position = index) })
        dao.insertActions(set.actions.mapIndexed { index, a -> a.copy(position = index) })
    }
    suspend fun deleteRule(id: String) = transaction {
        val old = requireNotNull(dao.rules().find { it.id == id })
        dao.updateRule(old.copy(enabled = false, deletedAt = System.currentTimeMillis(), updatedAt = System.currentTimeMillis()))
    }
}
