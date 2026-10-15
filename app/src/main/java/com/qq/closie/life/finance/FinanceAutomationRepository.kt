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
    val tags: List<String>, val event: FinanceEventInput, val categoryId: String? = null, val reviewedDuplicateIds: Set<String> = emptySet())

/** The only path from a discovered proposal into truth is explicit confirm(), inside one Room transaction. */
class FinanceAutomationRepository(private val database: LifeDatabase, private val finance: FinanceRepository) {
    private val dao get() = database.financeIntakeDao()
    private suspend fun <T> transaction(block: suspend () -> T): T =
        RestoreStartupGate.withBusinessAccessSuspending { database.withTransaction {
            scrubNotificationProposals()
            block()
        } }
    private suspend fun scrubNotificationProposals() {
        val descriptions = FinanceProposalPrivacy.userDescriptions(dao.actions())
        dao.proposals().forEach { old ->
            val safe = FinanceProposalPrivacy.sanitize(old, descriptions)
            if (safe != old) dao.updateProposal(safe.copy(revision = old.revision + 1))
        }
    }
    suspend fun sanitizeExistingProposals() = transaction { Unit }
    private suspend fun rules(): List<FinanceRuleSet> {
        val conditions = dao.conditions().groupBy { it.ruleId }
        val actions = dao.actions().groupBy { it.ruleId }
        return dao.rules().map { FinanceRuleSet(it, conditions[it.id].orEmpty(), actions[it.id].orEmpty()) }
    }
    fun observe() = gateAwareFlow { dao.observeChanges().map { transaction {
        FinanceAutomationSnapshot(dao.proposals(), dao.proposalTags(), rules())
    } } }
    suspend fun discover(proposal: FinanceProposalEntity): FinanceProposalEntity? = discoverWithResult(proposal).proposal
    suspend fun discoverVisible(evidence: NormalizedTransactionEvidence, enabled: () -> Boolean): FinanceParseResult = transaction {
        if (!enabled()) return@transaction FinanceParseResult(DiscoveryResult.MASTER_OFF)
        val proposal = evidence.toProposal()
        val identity = requireNotNull(FinanceEvidenceOrigin.strongReference(proposal))
        val peers = dao.proposals().filter { FinanceEvidenceOrigin.strongReference(it) == identity }
        if (peers.any { FinanceEvidenceOrigin.sameFacts(it, proposal) })
            return@transaction FinanceParseResult(DiscoveryResult.DUPLICATE)
        // Keep conflicting minimized facts in staging. Never mutate an earlier proposal or posted Truth.
        val row = if (peers.isEmpty()) proposal else proposal.copy(sourceKey = FinanceEvidenceOrigin.conflictKey(proposal))
        saveDiscovered(row, "")
    }

    suspend fun discoverWithResult(proposal: FinanceProposalEntity, transientText: String = proposal.evidenceText): FinanceParseResult = transaction {
        require(proposal.status == FinanceProposalStatus.ACTIVE && proposal.canonicalId == null && proposal.revision == 0L)
        require(!FinanceEvidenceOrigin.isVisible(proposal)) { "详情证据必须走规范化入口" }
        require(proposal.source in setOf(FinanceSource.WECHAT, FinanceSource.ALIPAY)) { "通知来源未支持" }
        if (dao.proposalByKey(proposal.sourceKey) != null) return@transaction FinanceParseResult(DiscoveryResult.DUPLICATE)
        if (System.currentTimeMillis() - proposal.occurredAt > 24 * 60 * 60 * 1000L) return@transaction FinanceParseResult(DiscoveryResult.STALE)
        saveDiscovered(proposal, transientText)
    }

    private suspend fun saveDiscovered(proposal: FinanceProposalEntity, transientText: String): FinanceParseResult {
        val ruleSets = rules()
        // App/provider identity cannot select a Finance account. Only explicit user rules may suggest one.
        val result = FinanceRuleEngine.apply(FinanceProposalPrivacy.sanitize(proposal), ruleSets, transientText)
        val account = result.proposal.accountId?.let { database.financeDao().account(it) }
        val row = FinanceProposalPrivacy.sanitize(result.proposal, FinanceProposalPrivacy.userDescriptions(ruleSets.flatMap { it.actions }))
            .copy(accountId = if (FinanceEvidenceOrigin.isVisible(proposal)) null
                else account?.takeIf { it.archivedAt == null && it.currencyCode == "CNY" }?.id)
        // Visible evidence has no approved persistent payment-channel/account binding yet.
        // Even a broad notification rule cannot choose an account for an unrelated shopping app.
        dao.insertProposal(row)
        dao.insertProposalTags(result.tags.map { FinanceProposalTagEntity(row.id, it) })
        return FinanceParseResult(DiscoveryResult.CREATED, row)
    }
    suspend fun confirm(id: String, revision: Long, input: FinanceProposalConfirmation): String = transaction {
        val proposal = requireNotNull(dao.proposal(id)) { "待确认记录已不存在" }
        if (proposal.status == FinanceProposalStatus.CONFIRMED) return@transaction requireNotNull(proposal.canonicalId)
        require(proposal.revision == revision && proposal.status in setOf(FinanceProposalStatus.ACTIVE, FinanceProposalStatus.SNOOZED)) {
            "待确认记录已改变，请重新打开"
        }
        require(input.amountMinor > 0 && input.description.isNotBlank())
        if (FinanceEvidenceOrigin.isVisible(proposal) && proposal.role == FinanceFlowRole.REFUND) {
            require(input.kind == FinanceProposalKind.INCOME && input.event.role == FinanceFlowRole.REFUND &&
                input.event.existingEventId != null) { "退款须保留退款性质并关联原资金事项，不能作为普通收入确认" }
        }
        if (FinanceEvidenceOrigin.isVisible(proposal) && proposal.kind == FinanceProposalKind.TRANSFER) {
            require(input.kind == FinanceProposalKind.TRANSFER) { "还款候选须核对两端账户，不能作为普通收支确认" }
        }
        require(FinanceEvidenceOrigin.blockingPeers(proposal, dao.proposals()).isEmpty()) {
            "同一交易编号存在冲突或已入账记录，请先核对并忽略重复小票；不能作为另一笔交易确认"
        }
        val candidates = FinanceDuplicateReview.candidates(id, input.kind, input.amountMinor, input.occurredAt,
            dao.proposals(), readFinanceSnapshot(database))
        require(input.reviewedDuplicateIds.containsAll(candidates)) {
            "存在金额与时间接近的候选或已入账记录，请核对是否同一笔；同一笔请忽略当前小票"
        }
        val account = requireNotNull(database.financeDao().account(input.accountId)) { "请选择账户" }
        require(account.archivedAt == null && account.currencyCode == "CNY") { "请选择有效的人民币账户" }
        val canonical = if (input.kind == FinanceProposalKind.TRANSFER) {
            finance.saveTransferInTransaction(null, input.accountId, requireNotNull(input.targetAccountId),
                input.amountMinor, input.description, input.occurredAt, authority = "USER_CONFIRMED_PROPOSAL").id
        } else finance.saveEntryInTransaction(null, input.accountId,
            if (input.kind == FinanceProposalKind.INCOME) FinanceDirection.INFLOW else FinanceDirection.OUTFLOW,
            input.amountMinor, input.description, input.occurredAt, input.category, input.tags, input.subcategory,
            input.event, authority = "USER_CONFIRMED_PROPOSAL", categoryId = input.categoryId).id
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
