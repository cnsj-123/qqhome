package com.qq.closie.life.finance

import androidx.room.withTransaction
import com.qq.closie.data.backup.RestoreStartupGate
import com.qq.closie.life.data.database.LifeDatabase
import java.security.MessageDigest
import java.util.UUID

data class FinanceChangePreview(val operationId: String, val restoreEpoch: Long, val fingerprint: String,
    val entryIds: Set<String>, val eventIds: Set<String>, val accountIds: Set<String>,
    val changesByCurrency: Map<String, Long>, val explanation: String, val changeDigest: String = "")
data class FinanceBatchCorrection(val accountId: String? = null, val replaceCategory: Boolean = false,
    val categoryId: String? = null, val addTagIds: Set<String> = emptySet(), val removeTagIds: Set<String> = emptySet())

/** Bounded, typed command preimages with optimistic membership and revision checks. */
class FinanceRecoveryRepository(private val db: LifeDatabase, private val finance: FinanceRepository,
    private val clock: () -> Long = System::currentTimeMillis) {
    private val dao get() = db.financeV16Dao()
    private suspend fun <T> transaction(block: suspend () -> T): T =
        RestoreStartupGate.withBusinessAccessSuspending { db.withTransaction { block() } }
    private fun hash(value: Any?): String = MessageDigest.getInstance("SHA-256")
        .digest(com.google.gson.Gson().toJson(value).toByteArray()).joinToString("") { "%02x".format(it) }
    private fun epoch(s: FinanceSnapshot) = s.v16?.controlRows?.singleOrNull()?.restoreEpoch ?: 0L

    private fun members(s: FinanceSnapshot, scope: FinanceChangePreview, groupId: String): List<FinanceRecoveryMemberEntity> {
        val v = requireNotNull(s.v16)
        val obligationIds = v.obligationRows.filter { it.eventId in scope.eventIds }.map { it.id }.toSet()
        return buildList {
            v.economicRows.filter { it.eventId in scope.eventIds }.forEach { add(FinanceRecoveryMemberEntity(groupId,
                FinanceRecoveryMemberKind.ECONOMIC,it.eventId,it.revision,it.updatedAt,it.voidedAt,it.categoryId)) }
            v.fundingRows.filter { it.eventId in scope.eventIds }.forEach { add(FinanceRecoveryMemberEntity(groupId,
                FinanceRecoveryMemberKind.FUNDING,it.id,it.revision,it.updatedAt,it.voidedAt)) }
            v.shareRows.filter { it.eventId in scope.eventIds }.forEach { add(FinanceRecoveryMemberEntity(groupId,
                FinanceRecoveryMemberKind.SHARE,it.id,it.revision,it.updatedAt,it.voidedAt)) }
            v.obligationRows.filter { it.eventId in scope.eventIds }.forEach { add(FinanceRecoveryMemberEntity(groupId,
                FinanceRecoveryMemberKind.OBLIGATION,it.id,it.revision,it.updatedAt,it.voidedAt)) }
            v.settlementRows.filter { it.obligationId in obligationIds }.forEach { add(FinanceRecoveryMemberEntity(groupId,
                FinanceRecoveryMemberKind.SETTLEMENT,it.id,it.revision,it.updatedAt,it.voidedAt)) }
            v.resolutionRows.filter { it.obligationId in obligationIds }.forEach { add(FinanceRecoveryMemberEntity(groupId,
                FinanceRecoveryMemberKind.RESOLUTION,it.id,it.revision,it.updatedAt,it.voidedAt)) }
            v.costAdjustmentRows.filter { it.eventId in scope.eventIds }.forEach { add(FinanceRecoveryMemberEntity(groupId,
                FinanceRecoveryMemberKind.COST_ADJUSTMENT,it.id,it.revision,it.updatedAt,it.voidedAt)) }
            v.purchaseAllocationRows.filter { it.eventId in scope.eventIds }.forEach { add(FinanceRecoveryMemberEntity(groupId,
                FinanceRecoveryMemberKind.PURCHASE_ALLOCATION,it.id,it.revision,it.updatedAt,it.voidedAt)) }
        }.sortedWith(compareBy<FinanceRecoveryMemberEntity> { it.kind.name }.thenBy { it.memberId })
    }

    private fun fingerprint(s: FinanceSnapshot, scope: FinanceChangePreview): String = hash(listOf(
        s.entries.filter { it.id in scope.entryIds }.sortedBy { it.id },
        s.transfers.filter { it.outflowEntryId in scope.entryIds || it.inflowEntryId in scope.entryIds }.sortedBy { it.id },
        s.v4?.links.orEmpty().filter { it.entryId in scope.entryIds || it.eventId in scope.eventIds }.sortedBy { it.id },
        s.v4?.events.orEmpty().filter { it.id in scope.eventIds }.sortedBy { it.id },
        s.v4?.expected.orEmpty().filter { it.eventId in scope.eventIds }.sortedBy { it.id },
        s.entryTags.filter { it.entryId in scope.entryIds }.sortedWith(compareBy<FinanceEntryTagCrossRef> { it.entryId }.thenBy { it.tagId }),
        s.accounts.filter { it.id in scope.accountIds }.sortedBy { it.id }.map {
            listOf(it.id,it.currencyCode,it.openingBalanceMinor,it.balanceAnchorAt) },
        members(s,scope,"")
    ))

    suspend fun previewVoid(entryId: String? = null, eventId: String? = null): FinanceChangePreview = transaction {
        require((entryId == null) != (eventId == null))
        val s = readFinanceSnapshot(db,true)
        val entries = mutableSetOf<String>()
        val events = mutableSetOf<String>()
        entryId?.let { require(s.entries.any { row -> row.id == it && row.voidedAt == null }); entries.add(it) }
        eventId?.let { require(s.v4?.events?.any { row -> row.id == it && row.voidedAt == null } == true); events.add(it) }
        val economicIds = s.v16?.economicRows.orEmpty().filter { it.voidedAt == null }.map { it.eventId }.toSet()
        var changed: Boolean
        do {
            val count = entries.size + events.size
            s.transfers.filter { it.outflowEntryId in entries || it.inflowEntryId in entries }.forEach {
                entries.add(it.outflowEntryId); entries.add(it.inflowEntryId)
                events.addAll(s.v4?.events.orEmpty().filter { event -> event.relatedTransferId == it.id }.map { event -> event.id })
            }
            s.v4?.activeLinks.orEmpty().forEach {
                if (it.entryId in entries) events.add(it.eventId)
                if (it.eventId in events && (it.eventId == eventId || it.eventId in economicIds ||
                    s.v4?.events.orEmpty().any { e -> e.id == it.eventId && e.relatedTransferId != null })) entries.add(it.entryId)
            }
            changed = count != entries.size + events.size
        } while (changed)
        val accounts = s.entries.filter { it.id in entries }.map { it.accountId }.toSet()
        val accountMap = s.accounts.associateBy { it.id }
        val deltas = s.entries.filter { it.id in entries && it.voidedAt == null &&
            it.occurredAt >= accountMap.getValue(it.accountId).balanceAnchorAt }.groupBy { accountMap.getValue(it.accountId).currencyCode }
            .mapValues { (_, rows) -> exactSum(rows.map { if (it.direction == FinanceDirection.OUTFLOW) it.amountMinor else -it.amountMinor }) }
        val preview = FinanceChangePreview(UUID.randomUUID().toString(),epoch(s),"",entries,events,accounts,deltas,
            "将作废 ${entries.size} 条真实流水；涉及 ${events.size} 个事项。新经济事项的分配、义务、结算和商品成本归属整组退役，可在版本未冲突时整组恢复。")
        preview.copy(fingerprint = fingerprint(s,preview))
    }

    private suspend fun capture(s: FinanceSnapshot, p: FinanceChangePreview, kind: String) {
        val now = clock()
        dao.putRecoveryGroup(listOf(FinanceRecoveryGroupEntity(p.operationId,kind,p.restoreEpoch,now,afterFingerprint = "")))
        val accounts = s.accounts.associateBy { it.id }
        dao.putRecoveryEntry(s.entries.filter { it.id in p.entryIds }.map {
            FinanceRecoveryEntryEntity(p.operationId,it.id,it.accountId,accounts.getValue(it.accountId).currencyCode,
                it.direction,it.amountMinor,it.description,it.occurredAt,it.recordedAt,it.updatedAt,it.categoryId,
                it.voidedAt,it.statPolicy,it.budgetPolicy,it.revision,it.importBatchId)
        })
        dao.putRecoveryLink(s.v4?.links.orEmpty().filter { it.entryId in p.entryIds || it.eventId in p.eventIds }.map {
            FinanceRecoveryLinkEntity(p.operationId,it.id,it.eventId,it.entryId,it.role,it.allocatedMinor,it.createdAt,it.updatedAt,it.revision,it.voidedAt)
        })
        dao.putRecoveryEvent(s.v4?.events.orEmpty().filter { it.id in p.eventIds }.map {
            FinanceRecoveryEventEntity(p.operationId,it.id,it.updatedAt,it.voidedAt) })
        dao.putRecoveryExpected(s.v4?.expected.orEmpty().filter { it.eventId in p.eventIds }.map {
            FinanceRecoveryExpectedEntity(p.operationId,it.id,it.cancelledAt) })
        dao.putRecoveryTransfer(s.transfers.filter { it.outflowEntryId in p.entryIds || it.inflowEntryId in p.entryIds }.map {
            FinanceRecoveryTransferEntity(p.operationId,it.id,it.updatedAt,it.voidedAt) })
        dao.putRecoveryTag(s.entryTags.filter { it.entryId in p.entryIds }.map { FinanceRecoveryTagEntity(p.operationId,it.entryId,it.tagId) })
        dao.putRecoveryMember(members(s,p,p.operationId))
    }

    private suspend fun applyMember(saved: FinanceRecoveryMemberEntity, restore: Boolean) {
        val now = clock()
        val v = readFinanceV16Snapshot(db)
        val voided = if (restore) saved.voidedAt else saved.voidedAt ?: now
        when (saved.kind) {
            FinanceRecoveryMemberKind.ECONOMIC -> {
                val row = v.economicRows.single { it.eventId == saved.memberId }
                dao.putEconomic(listOf(row.copy(voidedAt = voided, updatedAt = now, revision = Math.addExact(row.revision,1L),
                    categoryId = if (restore) saved.categoryId else row.categoryId)))
            }
            FinanceRecoveryMemberKind.FUNDING -> { val row=v.fundingRows.single { it.id==saved.memberId }; dao.putFunding(listOf(row.copy(voidedAt=voided,updatedAt=now,revision=Math.addExact(row.revision,1L)))) }
            FinanceRecoveryMemberKind.SHARE -> { val row=v.shareRows.single { it.id==saved.memberId }; dao.putShare(listOf(row.copy(voidedAt=voided,updatedAt=now,revision=Math.addExact(row.revision,1L)))) }
            FinanceRecoveryMemberKind.OBLIGATION -> { val row=v.obligationRows.single { it.id==saved.memberId }; dao.putObligation(listOf(row.copy(voidedAt=voided,updatedAt=now,revision=Math.addExact(row.revision,1L)))) }
            FinanceRecoveryMemberKind.SETTLEMENT -> { val row=v.settlementRows.single { it.id==saved.memberId }; dao.putSettlement(listOf(row.copy(voidedAt=voided,updatedAt=now,revision=Math.addExact(row.revision,1L)))) }
            FinanceRecoveryMemberKind.RESOLUTION -> { val row=v.resolutionRows.single { it.id==saved.memberId }; dao.putResolution(listOf(row.copy(voidedAt=voided,updatedAt=now,revision=Math.addExact(row.revision,1L)))) }
            FinanceRecoveryMemberKind.COST_ADJUSTMENT -> { val row=v.costAdjustmentRows.single { it.id==saved.memberId }; dao.putCostAdjustment(listOf(row.copy(voidedAt=voided,updatedAt=now,revision=Math.addExact(row.revision,1L)))) }
            FinanceRecoveryMemberKind.PURCHASE_ALLOCATION -> { val row=v.purchaseAllocationRows.single { it.id==saved.memberId }; dao.putPurchaseAllocation(listOf(row.copy(voidedAt=voided,updatedAt=now,revision=Math.addExact(row.revision,1L)))) }
        }
    }

    suspend fun voidConfirmed(p: FinanceChangePreview): String = transaction {
        require(epoch(readFinanceSnapshot(db,true)) == p.restoreEpoch) { "恢复后旧预览已失效，请重新打开" }
        val prior = dao.commandReceiptRows().singleOrNull { it.operationId == p.operationId }
        if (prior != null) {
            require(prior.command == "GROUP_VOID" && prior.inputDigest == hash(p) && prior.epoch == p.restoreEpoch)
            return@transaction prior.resultId
        }
        val s = readFinanceSnapshot(db,true)
        require(epoch(s) == p.restoreEpoch && fingerprint(s,p) == p.fingerprint) { "影响已改变，请重新预览" }
        capture(s,p,"GROUP_VOID")
        val economic = s.v16?.economicRows.orEmpty().filter { it.eventId in p.eventIds && it.voidedAt == null }.map { it.eventId }.toSet()
        members(s,p,p.operationId).filter { member ->
            when (member.kind) {
                FinanceRecoveryMemberKind.ECONOMIC -> member.memberId in economic
                else -> true
            }
        }.forEach { applyMember(it,false) }
        val transfers = s.transfers.filter { it.outflowEntryId in p.entryIds || it.inflowEntryId in p.entryIds }
        val legs = transfers.flatMap { listOf(it.outflowEntryId,it.inflowEntryId) }.toSet()
        transfers.filter { it.voidedAt == null }.forEach { finance.voidTransfer(it.id) }
        s.entries.filter { it.id in p.entryIds && it.id !in legs && it.voidedAt == null }.forEach { finance.voidEntry(it.id) }
        // A genuine noncash event has no Entry through which the old lifecycle could retire it.
        economic.forEach { id ->
            val row = requireNotNull(db.financeIntakeDao().event(id))
            db.financeIntakeDao().updateEvent(row.copy(voidedAt=clock(),updatedAt=maxOf(clock(),row.updatedAt+1)))
            db.financeIntakeDao().activeExpectedForEvent(id).forEach {
                db.financeIntakeDao().updateExpected(it.copy(cancelledAt=clock())) }
        }
        finish(p,"GROUP_VOID",hash(p))
    }

    private suspend fun finish(p: FinanceChangePreview, kind: String, digest: String): String {
        val after = readFinanceSnapshot(db,true)
        FinanceIntegrity.validate(after)
        val group = dao.recoveryGroupRows().single { it.id == p.operationId }
        dao.putRecoveryGroup(listOf(group.copy(afterFingerprint=fingerprint(after,p))))
        dao.putCommandReceipt(listOf(FinanceCommandReceiptEntity(p.operationId,kind,digest,p.operationId,p.restoreEpoch,clock())))
        finance.journal(p.operationId,kind)
        return p.operationId
    }

    suspend fun previewBatch(ids: Set<String>, edit: FinanceBatchCorrection): FinanceChangePreview = transaction {
        require(ids.isNotEmpty())
        val s=readFinanceSnapshot(db,true)
        val rows=s.entries.filter { it.id in ids }
        require(rows.size==ids.size && rows.all { it.voidedAt==null })
        require(s.transfers.none { it.outflowEntryId in ids || it.inflowEntryId in ids }) { "转账请整体编辑" }
        val accounts=s.accounts.associateBy { it.id }
        edit.accountId?.let { target ->
            require(accounts[target]?.archivedAt==null && target in accounts)
            require(rows.all { accounts.getValue(it.accountId).currencyCode==accounts.getValue(target).currencyCode }) { "批量修正不能改币种" }
        }
        require(!edit.replaceCategory || edit.categoryId==null || s.categories.any { it.id==edit.categoryId })
        require((edit.addTagIds+edit.removeTagIds).all { id -> s.tags.any { it.id==id } })
        val events=s.v4?.activeLinks.orEmpty().filter { it.entryId in ids }.map { it.eventId }.toSet()
        if(edit.replaceCategory) {
            require(s.v4?.activeLinks.orEmpty().filter { it.eventId in events }.all { it.entryId in ids }) { "事项跨多条流水，请选全构成后统一修正成本分类" }
        }
        val scope=FinanceChangePreview(UUID.randomUUID().toString(),epoch(s),"",ids,events,
            rows.map { it.accountId }.toSet()+listOfNotNull(edit.accountId),emptyMap(),
            "原子修正 ${rows.size} 条流水；金额和收付时间保留。改账户影响对应账户余额与已有核对结论。")
        scope.copy(fingerprint=fingerprint(s,scope),changeDigest=hash(edit))
    }

    suspend fun correctBatch(p: FinanceChangePreview, edit: FinanceBatchCorrection): String = transaction {
        require(epoch(readFinanceSnapshot(db,true)) == p.restoreEpoch) { "恢复后旧预览已失效，请重新打开" }
        require(p.changeDigest == hash(edit)) { "修正内容已改变，请重新预览" }
        val input=hash(listOf(p,edit))
        val prior=dao.commandReceiptRows().singleOrNull { it.operationId==p.operationId }
        if(prior!=null) { require(prior.command=="BATCH_CORRECT" && prior.inputDigest==input && prior.epoch==p.restoreEpoch); return@transaction prior.resultId }
        val s=readFinanceSnapshot(db,true)
        require(epoch(s)==p.restoreEpoch && fingerprint(s,p)==p.fingerprint) { "记录已改变，请重新预览" }
        // Re-run eligibility against the exact current transaction; no partial write precedes this.
        previewBatch(p.entryIds,edit)
        capture(s,p,"BATCH_CORRECT")
        val now=clock()
        s.entries.filter { it.id in p.entryIds }.forEach { row ->
            db.financeDao().updateEntry(row.copy(accountId=edit.accountId ?: row.accountId,
                categoryId=if(edit.replaceCategory) edit.categoryId else row.categoryId,
                revision=Math.addExact(row.revision,1L),updatedAt=now))
            val tags=(s.entryTags.filter { it.entryId==row.id }.map { it.tagId }.toSet()+edit.addTagIds)-edit.removeTagIds
            db.financeDao().clearEntryTags(row.id)
            db.financeDao().insertEntryTags(tags.map { FinanceEntryTagCrossRef(row.id,it) })
        }
        if(edit.replaceCategory) dao.putEconomic(s.v16?.economicRows.orEmpty().filter { it.eventId in p.eventIds && it.voidedAt==null }
            .map { it.copy(categoryId=edit.categoryId,updatedAt=now,revision=Math.addExact(it.revision,1L)) })
        finish(p,"BATCH_CORRECT",input)
    }

    suspend fun recover(groupId: String, token: FinanceCommandToken): String = transaction {
        require(token.operationId.isNotBlank())
        val s=readFinanceSnapshot(db,true); val v=requireNotNull(s.v16)
        require(token.restoreEpoch==epoch(s))
        val prior=v.commandReceiptRows.singleOrNull { it.operationId==token.operationId }
        if(prior!=null) { require(prior.command=="GROUP_RECOVER" && prior.inputDigest==hash(groupId) && prior.epoch==token.restoreEpoch); return@transaction prior.resultId }
        val group=requireNotNull(v.recoveryGroupRows.singleOrNull { it.id==groupId && it.consumedAt==null })
        require(group.epoch==epoch(s)) { "恢复代际已改变，旧撤销清单不可执行" }
        val entries=v.recoveryEntryRows.filter { it.groupId==groupId }
        val events=v.recoveryEventRows.filter { it.groupId==groupId }
        val p=FinanceChangePreview(groupId,group.epoch,"",entries.map { it.entryId }.toSet(),events.map { it.eventId }.toSet(),
            entries.map { it.accountId }.toSet()+s.entries.filter { row -> entries.any { it.entryId==row.id } }.map { it.accountId },emptyMap(),"")
        require(fingerprint(s,p)==group.afterFingerprint) { "关联组已发生后续修改，未恢复任何记录" }
        val now=clock()
        entries.forEach { old ->
            require(s.accounts.single { it.id==old.accountId }.currencyCode==old.accountCurrencyCode) { "原账户币种已改变，不能恢复金额语义" }
            val current=s.entries.single { it.id==old.entryId }
            db.financeDao().updateEntry(FinanceEntryEntity(old.entryId,old.accountId,old.direction,old.amountMinor,
                old.description,old.occurredAt,old.recordedAt,now,old.categoryId,old.voidedAt,old.statPolicy,old.budgetPolicy,
                Math.addExact(current.revision,1L),old.importBatchId))
            db.financeDao().clearEntryTags(old.entryId)
            db.financeDao().insertEntryTags(v.recoveryTagRows.filter { it.groupId==groupId && it.entryId==old.entryId }.map { FinanceEntryTagCrossRef(it.entryId,it.tagId) })
        }
        val intake=db.financeIntakeDao()
        v.recoveryTransferRows.filter { it.groupId==groupId }.forEach { old ->
            val current=s.transfers.single { it.id==old.transferId }
            db.financeDao().updateTransfer(current.copy(voidedAt=old.voidedAt,updatedAt=maxOf(now,current.updatedAt+1))) }
        events.forEach { old ->
            val current=requireNotNull(intake.event(old.eventId))
            intake.updateEvent(current.copy(voidedAt=old.voidedAt,updatedAt=maxOf(now,current.updatedAt+1))) }
        val savedLinks=v.recoveryLinkRows.filter { it.groupId==groupId }
        val savedLinkIds=savedLinks.map { it.linkId }.toSet()
        s.v4?.activeLinks.orEmpty().filter { it.entryId in p.entryIds && it.id !in savedLinkIds }.forEach {
            intake.updateLink(it.copy(voidedAt=now,updatedAt=now,revision=Math.addExact(it.revision,1L))) }
        savedLinks.forEach { old ->
            val current=s.v4!!.links.single { it.id==old.linkId }
            intake.updateLink(FinanceEventEntryLinkEntity(old.eventId,old.entryId,old.role,old.allocatedMinor,
                old.linkId,old.createdAt,now,old.voidedAt,Math.addExact(current.revision,1L))) }
        v.recoveryExpectedRows.filter { it.groupId==groupId }.forEach { old ->
            val current=s.v4!!.expected.single { it.id==old.expectedId }
            intake.updateExpected(current.copy(cancelledAt=old.cancelledAt)) }
        v.recoveryMemberRows.filter { it.groupId==groupId }.forEach { applyMember(it,true) }
        dao.putRecoveryGroup(listOf(group.copy(consumedAt=now)))
        FinanceIntegrity.validate(readFinanceSnapshot(db,true))
        dao.putCommandReceipt(listOf(FinanceCommandReceiptEntity(token.operationId,"GROUP_RECOVER",hash(groupId),groupId,token.restoreEpoch,now)))
        finance.journal(groupId,"GROUP_RECOVER")
        groupId
    }
}
