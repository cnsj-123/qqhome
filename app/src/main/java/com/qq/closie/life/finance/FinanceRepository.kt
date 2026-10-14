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
    private fun newId() = UUID.randomUUID().toString()
    private suspend fun <T> transaction(block: suspend () -> T): T =
        RestoreStartupGate.withBusinessAccessSuspending { database.withTransaction { block() } }

    suspend fun snapshot(): FinanceSnapshot = transaction { readSnapshot() }
    fun observeSnapshot(): Flow<FinanceSnapshot> = gateAwareFlow {
        dao.observeChanges().map { transaction { readSnapshot() } }
    }
    private suspend fun readSnapshot() = FinanceSnapshot(dao.accounts(), dao.entries(), dao.transfers(),
        dao.categories(), dao.tags(), dao.entryTags())

    suspend fun saveAccount(id: String? = null, name: String, kind: FinanceAccountKind,
        currencyCode: String = "CNY", openingBalanceMinor: Long = 0): FinanceAccountEntity = transaction {
        require(name.isNotBlank()) { "请填写账户名称" }
        FinanceMoney.fractionDigits(currencyCode)
        val old = id?.let { requireNotNull(dao.account(it)) { "账户已不存在" } }
        require(old == null || old.currencyCode == currencyCode || dao.accountEntryCount(old.id) == 0) {
            "已有流水的账户不能更换币种"
        }
        val now = clock()
        val row = FinanceAccountEntity(old?.id ?: newId(), name.trim(), kind, currencyCode, openingBalanceMinor,
            old?.createdAt ?: now, now, old?.archivedAt)
        if (old == null) dao.insertAccount(row) else dao.updateAccount(row)
        validateBalances()
        row
    }
    suspend fun archiveAccount(id: String) = transaction {
        val account = requireNotNull(dao.account(id)) { "账户已不存在" }
        if (account.archivedAt == null) dao.updateAccount(account.copy(archivedAt = clock(), updatedAt = clock()))
    }

    suspend fun saveEntry(id: String? = null, accountId: String, direction: FinanceDirection,
        amountMinor: Long, description: String, occurredAt: Long,
        category: String = "", tags: List<String> = emptyList()): FinanceEntryEntity = transaction {
        require(amountMinor > 0) { "金额必须大于 0" }
        require(description.isNotBlank()) { "请写下具体事由" }
        val old = id?.let { requireNotNull(dao.entry(it)) { "流水已不存在" } }
        require(old?.voidedAt == null) { "已作废流水不能修改" }
        require(id == null || dao.transferForEntry(id) == null) { "请编辑整笔转账，不能单独修改一侧流水" }
        usableAccount(accountId, old?.accountId)
        val now = clock()
        val categoryId = category.trim().takeIf { it.isNotEmpty() }?.let { name ->
            dao.categoryNamed(name)?.id ?: newId().also { dao.insertCategory(FinanceCategoryEntity(it, name)) }
        }
        val row = FinanceEntryEntity(old?.id ?: newId(), accountId, direction, amountMinor, description.trim(),
            occurredAt, old?.recordedAt ?: now, now, categoryId)
        if (old == null) dao.insertEntry(row) else dao.updateEntry(row)
        dao.clearEntryTags(row.id)
        val tagIds = tags.map(String::trim).filter(String::isNotEmpty).distinct().map { name ->
            dao.tagNamed(name)?.id ?: newId().also { dao.insertTag(FinanceTagEntity(it, name)) }
        }
        dao.insertEntryTags(tagIds.map { FinanceEntryTagCrossRef(row.id, it) })
        validateBalances()
        row
    }

    suspend fun saveTransfer(id: String? = null, sourceAccountId: String, targetAccountId: String,
        amountMinor: Long, description: String, occurredAt: Long): FinanceTransferEntity = transaction {
        require(amountMinor > 0) { "金额必须大于 0" }
        require(sourceAccountId != targetAccountId) { "转出与转入账户必须不同" }
        val old = id?.let { requireNotNull(dao.transfer(it)) { "转账已不存在" } }
        require(old?.voidedAt == null) { "已作废转账不能修改" }
        val oldOut = old?.let { requireNotNull(dao.entry(it.outflowEntryId)) }
        val oldIn = old?.let { requireNotNull(dao.entry(it.inflowEntryId)) }
        val source = usableAccount(sourceAccountId, oldOut?.accountId)
        val target = usableAccount(targetAccountId, oldIn?.accountId)
        require(source.currencyCode == target.currencyCode) { "F1 转账仅支持相同币种的账户" }
        val now = clock()
        val out = FinanceEntryEntity(oldOut?.id ?: newId(), sourceAccountId, FinanceDirection.OUTFLOW, amountMinor,
            description.trim(), occurredAt, oldOut?.recordedAt ?: now, now)
        val inflow = FinanceEntryEntity(oldIn?.id ?: newId(), targetAccountId, FinanceDirection.INFLOW, amountMinor,
            description.trim(), occurredAt, oldIn?.recordedAt ?: now, now)
        val transfer = FinanceTransferEntity(old?.id ?: newId(), out.id, inflow.id, old?.recordedAt ?: now, now)
        if (old == null) {
            dao.insertEntry(out); dao.insertEntry(inflow); dao.insertTransfer(transfer)
        } else {
            dao.updateEntry(out); dao.updateEntry(inflow); dao.updateTransfer(transfer)
        }
        validateBalances()
        transfer
    }

    suspend fun voidEntry(id: String) = transaction {
        val entry = requireNotNull(dao.entry(id)) { "流水已不存在" }
        require(dao.transferForEntry(id) == null) { "请作废整笔转账" }
        if (entry.voidedAt == null) {
            val now = clock()
            dao.updateEntry(entry.copy(voidedAt = now, updatedAt = now))
            validateBalances()
        }
    }
    suspend fun voidTransfer(id: String) = transaction {
        val transfer = requireNotNull(dao.transfer(id)) { "转账已不存在" }
        if (transfer.voidedAt == null) {
            val now = clock()
            for (entryId in listOf(transfer.outflowEntryId, transfer.inflowEntryId)) {
                val entry = requireNotNull(dao.entry(entryId))
                dao.updateEntry(entry.copy(voidedAt = now, updatedAt = now))
            }
            dao.updateTransfer(transfer.copy(voidedAt = now, updatedAt = now))
            validateBalances()
        }
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
