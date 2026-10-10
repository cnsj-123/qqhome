package com.qq.closie.life.finance

import androidx.room.withTransaction
import com.qq.closie.data.backup.RestoreStartupGate
import com.qq.closie.life.data.database.LifeDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class FinanceAccountMapping(val source: String, val existingId: String? = null,
    val name: String = source, val kind: FinanceAccountKind = FinanceAccountKind.OTHER,
    val fromHistory: Boolean = true, val openingMinor: Long = 0, val anchorAt: Long = System.currentTimeMillis(),
    val openingValid: Boolean = true)
data class FinanceImportPreview(val batch: FinanceImportBatchEntity, val rows: List<FinanceImportRowEntity>,
    val createdMappings: List<FinanceAccountMapping> = emptyList())
data class FinanceUndoResult(val voided: Int, val retained: Int)

/** Import owns staging; only a user-confirmed batch may invoke the Finance domain commands. */
class FinanceImportRepository(private val database: LifeDatabase, private val finance: FinanceRepository) {
    private val dao get() = database.financeIntakeDao()
    private suspend fun <T> transaction(block: suspend () -> T): T =
        RestoreStartupGate.withBusinessAccessSuspending { database.withTransaction { block() } }

    suspend fun history(): List<FinanceImportBatchEntity> = transaction { dao.batches() }
    suspend fun open(id: String): FinanceImportPreview = transaction {
        FinanceImportPreview(requireNotNull(dao.batches().find { it.id == id }), dao.rows(id),
            database.financeDao().accounts().filter { it.importBatchId == id && it.importSourceName != null }
                .map { FinanceAccountMapping(it.importSourceName!!, existingId = it.id, name = it.name, kind = it.kind) })
    }
    suspend fun stage(file: File, name: String): FinanceImportPreview = withContext(Dispatchers.IO) {
        val digest = LegacyLedgerReader.digest(file)
        val existing = transaction { dao.batches().find { it.digest == digest } }
        if (existing != null) return@withContext open(existing.id)
        val table = LegacyLedgerReader.read(file, name)
        val batch = FinanceImportBatchEntity("legacy-$digest", digest, name, System.currentTimeMillis())
        val rows = LegacyLedgerReader.stage(table, batch.id)
        transaction {
            val again = dao.batches().find { it.digest == digest }
            if (again != null) return@transaction FinanceImportPreview(again, dao.rows(again.id))
            val prior = dao.staging().filter { it.status == FinanceRowStatus.COMMITTED }.map { it.fingerprint }.toSet()
            val staged = rows.map { if (it.fingerprint in prior && it.status != FinanceRowStatus.INVALID)
                it.copy(status = FinanceRowStatus.DUPLICATE, issue = it.issue + "；其他导入批次存在相同行") else it }
            dao.insertBatch(batch); dao.insertRows(staged)
            FinanceImportPreview(batch, staged)
        }
    }

    suspend fun preview(id: String, mappings: List<FinanceAccountMapping>): FinanceImportPreview = transaction {
        val batch = requireNotNull(dao.batches().find { it.id == id })
        val rows = dao.rows(id)
        validateMappings(rows, mappings)
        val duplicates = duplicateIds(rows, mappings, readFinanceSnapshot(database))
        FinanceImportPreview(batch, rows.map { if (it.id in duplicates && it.canonicalId == null && it.status != FinanceRowStatus.INVALID)
            it.copy(status = FinanceRowStatus.DUPLICATE, issue = it.issue + "；可能与已有流水重复") else it })
    }

    suspend fun commit(id: String, mappings: List<FinanceAccountMapping>, selectedIds: Set<String>,
        acceptedDuplicateIds: Set<String>, splitTagsOnSpaces: Boolean, feesAreAdditional: Boolean): Int = withContext(Dispatchers.IO) {
        transaction {
            val batch = requireNotNull(dao.batches().find { it.id == id })
            require(batch.status != FinanceImportStatus.UNDONE) { "此批次已撤销，不能再次直接提交" }
            val allRows = dao.rows(id)
            val rows = allRows.filter { it.id in selectedIds && it.canonicalId == null }
            if (rows.isEmpty()) return@transaction 0
            validateMappings(rows, mappings)
            require(rows.all { it.kind != null && it.amountMinor != null && it.occurredAt != null &&
                it.status != FinanceRowStatus.INVALID && it.status != FinanceRowStatus.UNDONE }) { "所选记录仍有未解决的格式或类型" }
            val duplicates = duplicateIds(rows, mappings, readFinanceSnapshot(database))
            require(rows.none { (it.id in duplicates || it.status == FinanceRowStatus.DUPLICATE) && it.id !in acceptedDuplicateIds }) {
                "存在尚未确认的重复候选，请重新检查预览"
            }
            require(rows.none { it.feeMinor > 0 } || feesAreAdditional) { "请先确认手续费为转账金额之外的实际支出" }
            val now = System.currentTimeMillis()
            val mapped = mutableMapOf<String, String>()
            val previousAccounts = database.financeDao().accounts().filter { it.importBatchId == id }.associateBy { it.importSourceName }
            val usedNames = rows.flatMap { listOf(it.sourceAccount, it.targetAccount) }.filter(String::isNotBlank).toSet()
            for (mapping in mappings.filter { it.source in usedNames }) {
                val existing = if (mapping.existingId != null) database.financeDao().account(mapping.existingId) else previousAccounts[mapping.source]
                if (mapping.existingId != null || existing != null) {
                    require(existing != null && existing.archivedAt == null && existing.currencyCode == "CNY") { "映射账户已不可用或币种不符，请重新选择" }
                    mapped[mapping.source] = existing.id
                } else {
                    val earliest = allRows.filter { it.sourceAccount == mapping.source || it.targetAccount == mapping.source }.mapNotNull { it.occurredAt }.min()
                    mapped[mapping.source] = finance.saveAccountInTransaction(null, mapping.name, mapping.kind, "CNY",
                        if (mapping.fromHistory) 0 else mapping.openingMinor, if (mapping.fromHistory) earliest else mapping.anchorAt, id, mapping.source).id
                }
            }
            for (row in rows) {
                val accountId = mapped.getValue(row.sourceAccount)
                val description = row.description.ifBlank { "旧账记录（原备注为空）" }
                val tags = if (splitTagsOnSpaces) row.tagsText.split(Regex("\\s+")).filter(String::isNotBlank)
                    else listOf(row.tagsText).filter(String::isNotBlank)
                val canonical = if (row.kind == FinanceProposalKind.TRANSFER) {
                    val transfer = finance.saveTransferInTransaction(null, accountId, mapped.getValue(row.targetAccount),
                        row.amountMinor!!, description, row.occurredAt!!, id)
                    if (row.feeMinor > 0) finance.saveEntryInTransaction(null, accountId, FinanceDirection.OUTFLOW,
                        row.feeMinor, "转账手续费", row.occurredAt, "手续费", tags,
                        event = FinanceEventInput(role = FinanceFlowRole.FEE, relatedTransferId = transfer.id), batchId = id)
                    transfer.id
                } else {
                    // Received amounts embedded in source columns NEVER manufacture real inflows.
                    val uncertainIncome = row.hasRefund && row.kind == FinanceProposalKind.INCOME
                    finance.saveEntryInTransaction(null, accountId,
                        if (row.kind == FinanceProposalKind.INCOME) FinanceDirection.INFLOW else FinanceDirection.OUTFLOW,
                        row.amountMinor!!, description, row.occurredAt!!, row.category, tags, row.subcategory,
                        FinanceEventInput(nature = if (row.reimbursable) FinanceNature.REIMBURSABLE else if (uncertainIncome) FinanceNature.OTHER else FinanceNature.PERSONAL,
                            role = if (row.kind == FinanceProposalKind.INCOME) FinanceFlowRole.OTHER else FinanceFlowRole.PAYMENT,
                            statPolicy = if (uncertainIncome) FinanceStatPolicy.EXCLUDE else row.statPolicy, budgetPolicy = row.budgetPolicy),
                        batchId = id).id
                }
                dao.updateRow(row.copy(status = FinanceRowStatus.COMMITTED, canonicalId = canonical))
            }
            FinanceIntegrity.validate(readFinanceSnapshot(database))
            dao.updateBatch(batch.copy(status = FinanceImportStatus.COMMITTED, committedAt = now))
            rows.size
        }
    }

    /** Keep later edits/links; void only the unchanged objects created by this batch. */
    suspend fun undo(id: String): FinanceUndoResult = transaction {
        val batch = requireNotNull(dao.batches().find { it.id == id })
        if (batch.status == FinanceImportStatus.UNDONE) return@transaction FinanceUndoResult(0, 0)
        require(batch.status == FinanceImportStatus.COMMITTED)
        val snapshot = readFinanceSnapshot(database)
        val v4 = snapshot.v4!!
        val batchEntries = snapshot.entries.filter { it.importBatchId == id && it.voidedAt == null }
        val safe = batchEntries.filter { it.revision == 1L }.map { it.id }.toMutableSet()
        val byEvent = v4.links.groupBy { it.eventId }
        for (event in v4.events) {
            val linked = byEvent[event.id].orEmpty().map { it.entryId }
            if (event.updatedAt != event.createdAt || linked.any { entryId -> snapshot.entries.any {
                it.id == entryId && it.voidedAt == null && (it.importBatchId != id || it.revision != 1L)
            } }) safe.removeAll(linked.toSet())
        }
        // A transfer and its real fee are one undo unit.
        for (transfer in snapshot.transfers) {
            val feeEvents = v4.events.filter { it.relatedTransferId == transfer.id }.map { it.id }.toSet()
            val ids = setOf(transfer.outflowEntryId, transfer.inflowEntryId) +
                v4.links.filter { it.eventId in feeEvents }.map { it.entryId }
            if (!safe.containsAll(ids)) safe.removeAll(ids)
        }
        val now = System.currentTimeMillis()
        val storage = database.financeDao()
        batchEntries.filter { it.id in safe }.forEach {
            storage.updateEntry(it.copy(voidedAt = now, updatedAt = now, revision = it.revision + 1))
            finance.journal(it.id, "IMPORT_UNDO", id, it.revision, it.revision + 1)
        }
        snapshot.transfers.filter { it.outflowEntryId in safe && it.inflowEntryId in safe }.forEach {
            storage.updateTransfer(it.copy(voidedAt = now, updatedAt = now))
        }
        v4.events.filter { event -> byEvent[event.id].orEmpty().isNotEmpty() &&
            byEvent[event.id].orEmpty().all { it.entryId in safe } }.forEach {
            dao.updateEvent(it.copy(voidedAt = now, updatedAt = now))
            v4.expected.filter { expected -> expected.eventId == it.id && expected.cancelledAt == null }.forEach { expected ->
                dao.updateExpected(expected.copy(cancelledAt = now))
            }
        }
        snapshot.accounts.filter { it.importBatchId == id && it.createdAt == it.updatedAt &&
            snapshot.entries.none { entry -> entry.accountId == it.id && entry.voidedAt == null && entry.id !in safe } }.forEach {
            storage.updateAccount(it.copy(archivedAt = now, updatedAt = now))
        }
        val transferIds = snapshot.transfers.filter { it.outflowEntryId in safe }.map { it.id }.toSet()
        dao.rows(id).filter { it.canonicalId in safe || it.canonicalId in transferIds }.forEach { dao.updateRow(it.copy(status = FinanceRowStatus.UNDONE)) }
        dao.updateBatch(batch.copy(status = FinanceImportStatus.UNDONE))
        FinanceIntegrity.validate(readFinanceSnapshot(database))
        FinanceUndoResult(safe.size, batchEntries.size - safe.size)
    }

    private fun validateMappings(rows: List<FinanceImportRowEntity>, mappings: List<FinanceAccountMapping>) {
        require(mappings.all { it.existingId != null || (it.name.isNotBlank() && it.openingValid) }) { "请检查新账户名称与当前余额" }
        require(mappings.map { it.source }.distinct().size == mappings.size) { "账户映射重复" }
        val names = mappings.map { it.source }.toSet()
        require(rows.all { it.sourceAccount in names && (it.kind != FinanceProposalKind.TRANSFER || it.targetAccount in names) }) { "请完成全部账户映射" }
    }

    private fun duplicateIds(rows: List<FinanceImportRowEntity>, mappings: List<FinanceAccountMapping>, snapshot: FinanceSnapshot): Set<String> {
        val created = snapshot.accounts.filter { it.importBatchId == rows.firstOrNull()?.batchId }.associateBy { it.importSourceName }
        val map = mappings.associate { it.source to (it.existingId ?: created[it.source]?.id ?: "new:${it.source}") }
        fun key(account: String, target: String?, kind: FinanceProposalKind?, amount: Long?, time: Long?, text: String) =
            listOf(account, target.orEmpty(), kind?.name.orEmpty(), amount.toString(), time.toString(), text.trim())
        val known = FinanceProjection.ledger(snapshot).map { row -> key(row.account.id, row.targetAccount?.id,
            if (row.transfer != null) FinanceProposalKind.TRANSFER else if (row.entry.direction == FinanceDirection.INFLOW) FinanceProposalKind.INCOME else FinanceProposalKind.EXPENSE,
            row.entry.amountMinor, row.entry.occurredAt, row.entry.description) }.toHashSet()
        val result = mutableSetOf<String>()
        rows.filter { it.canonicalId == null }.forEach {
            val fingerprint = key(map[it.sourceAccount].orEmpty(), if (it.kind == FinanceProposalKind.TRANSFER) map[it.targetAccount] else null,
                it.kind, it.amountMinor, it.occurredAt, it.description.ifBlank { "旧账记录（原备注为空）" })
            if (!known.add(fingerprint)) result += it.id
        }
        return result
    }

    companion object {
        fun suggestKind(name: String): FinanceAccountKind = when {
            name.contains("微信") -> FinanceAccountKind.WECHAT
            name.contains("支付宝") -> FinanceAccountKind.ALIPAY
            listOf("花呗", "白条", "月付", "信用").any(name::contains) -> FinanceAccountKind.CREDIT
            listOf("基金", "股票", "理财", "投资").any(name::contains) -> FinanceAccountKind.INVESTMENT
            name.contains("银行") -> FinanceAccountKind.BANK_CARD
            name.contains("现金") -> FinanceAccountKind.CASH
            else -> FinanceAccountKind.OTHER
        }
    }
}
