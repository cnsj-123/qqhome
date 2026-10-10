package com.qq.closie.life.finance

import androidx.room.withTransaction
import com.qq.closie.data.backup.RestoreStartupGate
import com.qq.closie.data.backup.gateAwareFlow
import com.qq.closie.life.data.database.LifeDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

/** The manual Finance command boundary. No Capture/AI writes; no universal LifeEntity payload. */
class FinanceRepository(private val database: LifeDatabase, private val clock: () -> Long = System::currentTimeMillis) {
    private val dao get() = database.financeDao()
    private val intake get() = database.financeIntakeDao()
    private fun newId() = UUID.randomUUID().toString()
    private suspend fun <T> transaction(block: suspend () -> T): T =
        RestoreStartupGate.withBusinessAccessSuspending { database.withTransaction { block() } }

    suspend fun snapshot(): FinanceSnapshot = transaction { readSnapshot() }
    fun observeSnapshot(): Flow<FinanceSnapshot> = gateAwareFlow {
        kotlinx.coroutines.flow.combine(dao.observeChanges(), intake.observeChanges()) { _, _ -> transaction { readSnapshot() } }
    }
    private suspend fun readSnapshot() = readFinanceSnapshot(database)

    suspend fun saveAccount(id: String? = null, name: String, kind: FinanceAccountKind,
        currencyCode: String = "CNY", openingBalanceMinor: Long = 0): FinanceAccountEntity = transaction {
        saveAccountInTransaction(id, name, kind, currencyCode, openingBalanceMinor).also { validateBalances() }
    }
    internal suspend fun saveAccountInTransaction(id: String?, name: String, kind: FinanceAccountKind,
        currencyCode: String, openingBalanceMinor: Long, anchor: Long? = null, batchId: String? = null,
        importSourceName: String? = null): FinanceAccountEntity {
        require(name.isNotBlank()) { "请填写账户名称" }
        FinanceMoney.fractionDigits(currencyCode)
        val old = id?.let { requireNotNull(dao.account(it)) { "账户已不存在" } }
        require(old == null || old.currencyCode == currencyCode || dao.accountEntryCount(old.id) == 0) {
            "已有流水的账户不能更换币种"
        }
        val now = clock()
        val row = FinanceAccountEntity(old?.id ?: newId(), name.trim(), kind, currencyCode, openingBalanceMinor,
            old?.createdAt ?: now, if (old == null) now else maxOf(now, old.updatedAt + 1), old?.archivedAt,
            old?.balanceAnchorAt ?: anchor ?: now, old?.importBatchId ?: batchId, old?.importSourceName ?: importSourceName)
        if (old == null) dao.insertAccount(row) else dao.updateAccount(row)
        journal(row.id, "ACCOUNT_SAVE", batchId)
        return row
    }
    suspend fun archiveAccount(id: String) = transaction {
        val account = requireNotNull(dao.account(id)) { "账户已不存在" }
        if (account.archivedAt == null) {
            dao.updateAccount(account.copy(archivedAt = clock(), updatedAt = maxOf(clock(), account.updatedAt + 1)))
            journal(id, "ACCOUNT_ARCHIVE")
        }
    }
    suspend fun unarchiveAccount(id: String) = transaction {
        val account = requireNotNull(dao.account(id))
        dao.updateAccount(account.copy(archivedAt = null, updatedAt = maxOf(clock(), account.updatedAt + 1)))
        journal(id, "ACCOUNT_UNARCHIVE")
    }

    suspend fun saveEntry(id: String? = null, accountId: String, direction: FinanceDirection,
        amountMinor: Long, description: String, occurredAt: Long,
        category: String = "", tags: List<String> = emptyList(), subcategory: String = "",
        event: FinanceEventInput? = null, expectedRevision: Long? = null): FinanceEntryEntity = transaction {
        saveEntryInTransaction(id, accountId, direction, amountMinor, description, occurredAt,
            category, tags, subcategory, event, expectedRevision).also { validateBalances() }
    }
    internal suspend fun saveEntryInTransaction(id: String?, accountId: String, direction: FinanceDirection,
        amountMinor: Long, description: String, occurredAt: Long, category: String, tags: List<String>,
        subcategory: String = "", event: FinanceEventInput? = null, expectedRevision: Long? = null,
        batchId: String? = null, authority: String? = null): FinanceEntryEntity {
        require(amountMinor > 0) { "金额必须大于 0" }
        require(description.isNotBlank()) { "请写下具体事由" }
        val old = id?.let { requireNotNull(dao.entry(it)) { "流水已不存在" } }
        require(old?.voidedAt == null) { "已作废流水不能修改" }
        require(expectedRevision == null || old?.revision == expectedRevision) { "这笔记录已改变，请重新打开后保存" }
        require(id == null || dao.transferForEntry(id) == null) { "请编辑整笔转账，不能单独修改一侧流水" }
        usableAccount(accountId, old?.accountId)
        val now = clock()
        val categoryId = resolveCategory(category, subcategory)
        val row = FinanceEntryEntity(old?.id ?: newId(), accountId, direction, amountMinor, description.trim(),
            occurredAt, old?.recordedAt ?: now, now, categoryId,
            statPolicy = event?.statPolicy ?: old?.statPolicy ?: FinanceStatPolicy.INCLUDE,
            budgetPolicy = event?.budgetPolicy ?: old?.budgetPolicy ?: FinanceBudgetPolicy.INCLUDE,
            revision = (old?.revision ?: 0) + 1, importBatchId = old?.importBatchId ?: batchId)
        if (old == null) dao.insertEntry(row) else dao.updateEntry(row)
        dao.clearEntryTags(row.id)
        val tagIds = tags.map(String::trim).filter(String::isNotEmpty).distinct().map { name ->
            dao.tagNamed(name)?.id ?: newId().also { dao.insertTag(FinanceTagEntity(it, name)) }
        }
        dao.insertEntryTags(tagIds.map { FinanceEntryTagCrossRef(row.id, it) })
        if (event != null) attachEvent(row, event)
        journal(row.id, "ENTRY_SAVE", batchId, old?.revision, row.revision, authority)
        return row
    }

    suspend fun saveTransfer(id: String? = null, sourceAccountId: String, targetAccountId: String,
        amountMinor: Long, description: String, occurredAt: Long, expectedUpdatedAt: Long? = null): FinanceTransferEntity = transaction {
        saveTransferInTransaction(id, sourceAccountId, targetAccountId, amountMinor, description, occurredAt,
            expectedUpdatedAt = expectedUpdatedAt).also { validateBalances() }
    }
    internal suspend fun saveTransferInTransaction(id: String?, sourceAccountId: String, targetAccountId: String,
        amountMinor: Long, description: String, occurredAt: Long, batchId: String? = null,
        authority: String? = null, expectedUpdatedAt: Long? = null): FinanceTransferEntity {
        require(amountMinor > 0) { "金额必须大于 0" }
        require(sourceAccountId != targetAccountId) { "转出与转入账户必须不同" }
        val old = id?.let { requireNotNull(dao.transfer(it)) { "转账已不存在" } }
        require(old?.voidedAt == null) { "已作废转账不能修改" }
        require(expectedUpdatedAt == null || old?.updatedAt == expectedUpdatedAt) { "转账已改变，请重新打开" }
        val oldOut = old?.let { requireNotNull(dao.entry(it.outflowEntryId)) }
        val oldIn = old?.let { requireNotNull(dao.entry(it.inflowEntryId)) }
        val source = usableAccount(sourceAccountId, oldOut?.accountId)
        val target = usableAccount(targetAccountId, oldIn?.accountId)
        require(source.currencyCode == target.currencyCode) { "F1 转账仅支持相同币种的账户" }
        val now = clock()
        val out = FinanceEntryEntity(oldOut?.id ?: newId(), sourceAccountId, FinanceDirection.OUTFLOW, amountMinor,
            description.trim(), occurredAt, oldOut?.recordedAt ?: now, now,
            revision = (oldOut?.revision ?: 0) + 1, importBatchId = oldOut?.importBatchId ?: batchId)
        val inflow = FinanceEntryEntity(oldIn?.id ?: newId(), targetAccountId, FinanceDirection.INFLOW, amountMinor,
            description.trim(), occurredAt, oldIn?.recordedAt ?: now, now,
            revision = (oldIn?.revision ?: 0) + 1, importBatchId = oldIn?.importBatchId ?: batchId)
        val transfer = FinanceTransferEntity(old?.id ?: newId(), out.id, inflow.id, old?.recordedAt ?: now,
            if (old == null) now else maxOf(now, old.updatedAt + 1))
        if (old == null) {
            dao.insertEntry(out); dao.insertEntry(inflow); dao.insertTransfer(transfer)
        } else {
            dao.updateEntry(out); dao.updateEntry(inflow); dao.updateTransfer(transfer)
        }
        journal(transfer.id, "TRANSFER_SAVE", batchId, authority = authority)
        return transfer
    }

    suspend fun voidEntry(id: String) = transaction {
        val entry = requireNotNull(dao.entry(id)) { "流水已不存在" }
        require(dao.transferForEntry(id) == null) { "请作废整笔转账" }
        if (entry.voidedAt == null) {
            val now = clock()
            dao.updateEntry(entry.copy(voidedAt = now, updatedAt = now, revision = entry.revision + 1))
            val links = intake.links()
            for (link in links.filter { it.entryId == id }) {
                val event = requireNotNull(intake.event(link.eventId))
                val active = links.filter { it.eventId == event.id }.any { dao.entry(it.entryId)?.voidedAt == null }
                if (!active) {
                    intake.updateEvent(event.copy(voidedAt = now, updatedAt = maxOf(now, event.updatedAt + 1)))
                    intake.expected().filter { it.eventId == event.id && it.cancelledAt == null }.forEach {
                        intake.updateExpected(it.copy(cancelledAt = now))
                    }
                }
            }
            journal(id, "ENTRY_VOID", before = entry.revision, after = entry.revision + 1)
            validateBalances()
        }
    }
    suspend fun voidTransfer(id: String) = transaction {
        val transfer = requireNotNull(dao.transfer(id)) { "转账已不存在" }
        if (transfer.voidedAt == null) {
            val now = clock()
            for (entryId in listOf(transfer.outflowEntryId, transfer.inflowEntryId)) {
                val entry = requireNotNull(dao.entry(entryId))
                dao.updateEntry(entry.copy(voidedAt = now, updatedAt = now, revision = entry.revision + 1))
            }
            dao.updateTransfer(transfer.copy(voidedAt = now, updatedAt = now))
            val feeEvents = intake.events().filter { it.relatedTransferId == id && it.voidedAt == null }
            val links = intake.links()
            for (event in feeEvents) {
                val fees = links.filter { it.eventId == event.id }
                for (link in fees) {
                    require(links.count { it.entryId == link.entryId } == 1 && link.role == FinanceFlowRole.FEE) {
                        "手续费已关联其他事件，请先调整手续费关联"
                    }
                    val fee = requireNotNull(dao.entry(link.entryId))
                    if (fee.voidedAt == null) {
                        dao.updateEntry(fee.copy(voidedAt = now, updatedAt = now, revision = fee.revision + 1))
                        journal(fee.id, "TRANSFER_FEE_VOID", before = fee.revision, after = fee.revision + 1)
                    }
                }
                intake.updateEvent(event.copy(voidedAt = now, updatedAt = now))
            }
            journal(id, "TRANSFER_VOID")
            validateBalances()
        }
    }

    private suspend fun resolveCategory(parent: String, child: String): String? {
        if (parent.isBlank()) { require(child.isBlank()) { "子分类需要主分类" }; return null }
        val categories = dao.categories()
        val root = categories.find { it.parentId == null && it.name == parent.trim() }?.id
            ?: newId().also { dao.insertCategory(FinanceCategoryEntity(it, parent.trim())) }
        return child.trim().takeIf { it.isNotEmpty() }?.let { name ->
            categories.find { it.parentId == root && it.name == name }?.id
                ?: newId().also { dao.insertCategory(FinanceCategoryEntity(it, name, root)) }
        } ?: root
    }

    private suspend fun attachEvent(entry: FinanceEntryEntity, input: FinanceEventInput) {
        val oldLinks = if (entry.revision == 1L) emptyList() else intake.linksForEntry(entry.id)
        require(oldLinks.size <= 1) { "此流水分配到多个事件，请先在关联编辑中调整分配" }
        val now = clock()
        val priorEvent = oldLinks.singleOrNull()?.eventId?.let { intake.event(it) }
        val target = input.existingEventId?.let { id ->
            requireNotNull(intake.event(id)?.takeIf { it.voidedAt == null }) { "关联事件已不可用" }
        }
        require(input.expectedEventUpdatedAt == null || (target ?: priorEvent)?.updatedAt == input.expectedEventUpdatedAt) {
            "关联事件已改变，请重新打开记录"
        }
        require(input.role !in setOf(FinanceFlowRole.REFUND, FinanceFlowRole.REBATE, FinanceFlowRole.CASHBACK, FinanceFlowRole.REIMBURSEMENT)
            || target != null || priorEvent != null) { "请选择这笔到账对应的原事件" }
        val event = target?.copy(updatedAt = maxOf(now, target.updatedAt + 1)) ?: priorEvent?.copy(description = entry.description, nature = input.nature,
            personalShareMinor = input.personalShareMinor, updatedAt = maxOf(now, priorEvent.updatedAt + 1)) ?: FinanceEventEntity(
            newId(), entry.description, input.nature, requireNotNull(dao.account(entry.accountId)).currencyCode,
            entry.occurredAt, now, now, input.personalShareMinor, relatedTransferId = input.relatedTransferId)
        require(event.currencyCode == dao.account(entry.accountId)?.currencyCode) { "不能关联不同币种的事件" }
        require(input.personalShareMinor == null || input.personalShareMinor >= 0) { "个人承担不能为负数" }
        if (priorEvent?.id == event.id || target != null) intake.updateEvent(event) else intake.insertEvent(event)
        intake.clearLinks(entry.id)
        intake.insertLinks(listOf(FinanceEventEntryLinkEntity(event.id, entry.id, input.role, entry.amountMinor)))
        if (input.cancelExpected) {
            require(input.expectedMinor == null)
            intake.expected().filter { it.eventId == event.id && it.cancelledAt == null }.forEach {
                intake.updateExpected(it.copy(cancelledAt = now))
            }
        }
        if (input.expectedMinor != null) {
            require(input.expectedMinor > 0) { "预计金额必须大于 0" }
            val existing = intake.expected().filter { it.eventId == event.id && it.cancelledAt == null }
            require(existing.size <= 1) { "此事件有多项预期，请在关系中分别调整" }
            val row = existing.singleOrNull()?.copy(role = input.expectedRole, amountMinor = input.expectedMinor)
                ?: FinanceExpectedFlowEntity(newId(), event.id, input.expectedRole, input.expectedMinor, "", now)
            if (existing.isEmpty()) intake.insertExpected(row) else intake.updateExpected(row)
        }
    }

    suspend fun allocateEntry(entryId: String, allocations: List<FinanceAllocation>, expectedRevision: Long) = transaction {
        val entry = requireNotNull(dao.entry(entryId))
        require(entry.voidedAt == null && entry.revision == expectedRevision) { "记录已改变，请重新打开" }
        require(dao.transferForEntry(entryId) == null) { "转账不参与消费事件分配" }
        require(allocations.isNotEmpty() && allocations.map { it.eventId }.distinct().size == allocations.size)
        require(allocations.fold(java.math.BigInteger.ZERO) { sum, it -> sum + it.amountMinor.toBigInteger() } == entry.amountMinor.toBigInteger()) {
            "分配金额之和必须等于整笔真实流水"
        }
        intake.clearLinks(entryId)
        intake.insertLinks(allocations.map { FinanceEventEntryLinkEntity(it.eventId, entryId, it.role, it.amountMinor) })
        allocations.forEach {
            val event = requireNotNull(intake.event(it.eventId))
            intake.updateEvent(event.copy(updatedAt = maxOf(clock(), event.updatedAt + 1)))
        }
        dao.updateEntry(entry.copy(revision = entry.revision + 1, updatedAt = clock()))
        journal(entryId, "ENTRY_ALLOCATE", before = entry.revision, after = entry.revision + 1)
        validateBalances()
    }

    internal suspend fun journal(id: String, operation: String, batchId: String? = null, before: Long? = null, after: Long? = null,
        authority: String? = null) {
        intake.insertChange(FinanceChangeEntity(newId(), id, operation,
            authority ?: if (batchId == null) "USER_DIRECT" else "USER_CONFIRMED_IMPORT", clock(), batchId, before, after))
    }

    private suspend fun usableAccount(id: String, existingAccountId: String?): FinanceAccountEntity {
        val account = requireNotNull(dao.account(id)) { "请选择账户" }
        require(account.archivedAt == null || id == existingAccountId) { "账户已归档，不能新增流水" }
        return account
    }
    private suspend fun validateBalances() {
        // Reject overflow before commit rather than silently wrapping a long-lived balance.
        FinanceIntegrity.validate(readSnapshot())
    }
}
