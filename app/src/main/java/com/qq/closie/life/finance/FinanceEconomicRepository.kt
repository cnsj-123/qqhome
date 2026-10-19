package com.qq.closie.life.finance

import androidx.room.withTransaction
import com.qq.closie.data.backup.RestoreStartupGate
import com.qq.closie.life.data.database.LifeDatabase
import java.security.MessageDigest
import java.util.UUID

data class FinanceCommandToken(val operationId: String, val restoreEpoch: Long)
data class FinanceFundingInput(val id: String, val partyId: String, val amountMinor: Long,
    val entryLinkId: String? = null, val basis: String)
data class FinanceShareInput(val id: String, val amountMinor: Long?, val beneficiaryId: String,
    val bearerId: String?, val fundingId: String?, val relation: FinanceShareRelation)
data class FinanceEconomicInput(val eventId: String? = null, val expectedRevision: Long? = null,
    val description: String, val currencyCode: String, val occurredAt: Long, val valueMinor: Long?,
    val valueBasis: String, val categoryId: String?, val allocationsComplete: Boolean,
    val funding: List<FinanceFundingInput>, val shares: List<FinanceShareInput>,
    val statPolicy:FinanceStatPolicy?=null,val budgetPolicy:FinanceBudgetPolicy?=null)
data class FinanceCashInput(val accountId: String, val amountMinor: Long, val occurredAt: Long,
    val description: String = "")

/** Only explicit user commands call this owner. Provider/Extraction receive no instance of it. */
class FinanceEconomicRepository(private val db: LifeDatabase,
    private val finance: FinanceRepository, private val clock: () -> Long = System::currentTimeMillis) {
    private val dao get() = db.financeV16Dao()
    private val intake get() = db.financeIntakeDao()
    private fun id() = UUID.randomUUID().toString()

    suspend fun token(): FinanceCommandToken = RestoreStartupGate.withBusinessAccessSuspending {
        db.withTransaction { FinanceCommandToken(id(), epoch()) }
    }
    private suspend fun epoch() = dao.controlRows().singleOrNull()?.restoreEpoch ?: 0L

    /** Hash a typed command encoding; only its digest is durable, never a shadow payload. */
    private fun digest(parts: List<Any?>): String {
        return MessageDigest.getInstance("SHA-256").digest(com.google.gson.Gson().toJson(parts).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
    private suspend fun command(token: FinanceCommandToken, name: String, input: List<Any?>,
        block: suspend () -> String): String = RestoreStartupGate.withBusinessAccessSuspending {
        db.withTransaction {
            require(token.operationId.isNotBlank() && token.restoreEpoch == epoch()) { "恢复后请重新打开编辑器" }
            val hash = digest(input)
            val prior = dao.commandReceiptRows().firstOrNull { it.operationId == token.operationId }
            if (prior != null) {
                require(prior.command == name && prior.inputDigest == hash && prior.epoch == token.restoreEpoch) { "操作标识已用于其他内容" }
                prior.resultId
            } else {
                val result = block()
                FinanceIntegrity.validate(readFinanceSnapshot(db, true))
                dao.putCommandReceipt(listOf(FinanceCommandReceiptEntity(token.operationId, name, hash, result, token.restoreEpoch, clock())))
                finance.journal(result, name)
                result
            }
        }
    }

    suspend fun saveParty(token: FinanceCommandToken, name: String): String =
        command(token, "PARTY_CREATE", listOf(name)) {
            require(name.isNotBlank() && name.length <= 100)
            val now = clock()
            val result = id()
            dao.putParty(listOf(FinancePartyEntity(result, name.trim(), false, now, now)))
            result
        }

    suspend fun confirmEconomic(token: FinanceCommandToken, input: FinanceEconomicInput): String =
        command(token, "ECONOMIC_CONFIRM", listOf(input)) {
            FinanceMoney.fractionDigits(input.currencyCode)
            require(input.valueBasis.isNotBlank())
            require(input.funding.map { it.id }.distinct().size == input.funding.size)
            require(input.shares.map { it.id }.distinct().size == input.shares.size)
            val before = readFinanceSnapshot(db, true)
            val v = requireNotNull(before.v16)
            val oldEvent = input.eventId?.let { eventId -> requireNotNull(intake.event(eventId)) }
            val old = v.economicRows.singleOrNull { it.eventId == input.eventId }
            require(oldEvent?.voidedAt == null && old?.voidedAt == null)
            require(if (old == null) input.expectedRevision == null else old.revision == input.expectedRevision) { "事项已改变，请查看影响后重试" }
            if (oldEvent != null && old == null) {
                require(v.obligationRows.none {it.eventId==oldEvent.id && it.voidedAt==null}) {
                    "此事项已有独立确认的往来义务，请保留现有口径，不能另生成重复承担义务"
                }
                require(before.v4?.activeLinks?.none { it.eventId == oldEvent.id &&
                    it.role in setOf(FinanceFlowRole.REFUND, FinanceFlowRole.REBATE, FinanceFlowRole.CASHBACK) } != false) {
                    "已有调整的旧事项需先核对成本归属，不能直接切换口径"
                }
            }
            val now = clock()
            val eventId = oldEvent?.id ?: id()
            val event = oldEvent?.copy(description = input.description.trim(), personalShareMinor = null,
                occurredAt = input.occurredAt, updatedAt = maxOf(now, oldEvent.updatedAt + 1))
                ?: FinanceEventEntity(eventId, input.description.trim(), FinanceNature.OTHER, input.currencyCode,
                    input.occurredAt, now, now)
            require(event.currencyCode == input.currencyCode) { "经济事项币种不能更换" }
            if (oldEvent == null) intake.insertEvent(event) else intake.updateEvent(event)
            if (v.partyRows.none { it.id == FinanceV16Integrity.SELF && it.voidedAt == null }) {
                val self = v.partyRows.singleOrNull { it.id == FinanceV16Integrity.SELF }
                dao.putParty(listOf(FinancePartyEntity(FinanceV16Integrity.SELF, "本人", true, self?.createdAt ?: now, now,
                    Math.addExact(self?.revision ?: 0L, 1L))))
            }
            val nextRevision = Math.addExact(old?.revision ?: 0L, 1L)
            dao.putEconomic(listOf(FinanceEconomicEntity(eventId, input.valueMinor, input.valueBasis.trim(),
                input.categoryId, input.allocationsComplete,statPolicy=input.statPolicy ?: old?.statPolicy ?: FinanceStatPolicy.INCLUDE,
                budgetPolicy=input.budgetPolicy ?: old?.budgetPolicy ?: FinanceBudgetPolicy.INCLUDE,createdAt = old?.createdAt ?: now,
                updatedAt = now, revision = nextRevision)))
            val priorFunding = v.fundingRows.filter { it.eventId == eventId }
            val priorShares = v.shareRows.filter { it.eventId == eventId }
            require(input.funding.all { new -> v.fundingRows.none { it.id == new.id && it.eventId != eventId } })
            require(input.shares.all { new -> v.shareRows.none { it.id == new.id && it.eventId != eventId } })
            dao.putFunding(priorFunding.filter { oldRow -> input.funding.none { it.id == oldRow.id } }
                .map { it.copy(voidedAt = now, revision = Math.addExact(it.revision, 1L), updatedAt = now) })
            dao.putShare(priorShares.filter { oldRow -> input.shares.none { it.id == oldRow.id } }
                .map { it.copy(voidedAt = now, revision = Math.addExact(it.revision, 1L), updatedAt = now) })
            dao.putFunding(input.funding.map { part ->
                val prior = priorFunding.singleOrNull { it.id == part.id }
                FinanceFundingEntity(part.id, eventId, part.partyId, part.amountMinor, part.entryLinkId,
                    part.basis, prior?.createdAt ?: now, now, Math.addExact(prior?.revision ?: 0L, 1L))
            })
            dao.putShare(input.shares.map { part ->
                val prior = priorShares.singleOrNull { it.id == part.id }
                FinanceShareEntity(part.id, eventId, part.amountMinor, part.beneficiaryId, part.bearerId,
                    part.fundingId, part.relation, prior?.createdAt ?: now, now, Math.addExact(prior?.revision ?: 0L, 1L))
            })
            val priorObligations = v.obligationRows.filter { it.eventId == eventId && it.shareId != null && it.voidedAt == null }
            val repayable = input.shares.filter { it.relation == FinanceShareRelation.REPAYABLE_CONFIRMED }
            priorObligations.filter { row -> repayable.none { it.id == row.shareId } }.forEach { row ->
                require(v.settlementRows.none { it.obligationId == row.id && it.voidedAt == null } &&
                    v.resolutionRows.none { it.obligationId == row.id && it.voidedAt == null }) { "已有结算，不能删除承担关系" }
                dao.putObligation(listOf(row.copy(voidedAt = now, updatedAt = now, revision = row.revision + 1)))
            }
            repayable.forEach { part ->
                val payer = requireNotNull(input.funding.singleOrNull { it.id == part.fundingId })
                val prior = priorObligations.singleOrNull { it.shareId == part.id }
                val debtor = requireNotNull(part.bearerId)
                if (prior != null && (prior.creditorId != payer.partyId || prior.debtorId != debtor)) {
                    require(v.settlementRows.none { it.obligationId == prior.id && it.voidedAt == null } &&
                        v.resolutionRows.none { it.obligationId == prior.id && it.voidedAt == null }) { "已有结算，不能更换债权债务方" }
                }
                dao.putObligation(listOf(FinanceObligationEntity(prior?.id ?: id(), eventId, part.id,
                    payer.partyId, debtor, requireNotNull(part.amountMinor), input.currencyCode,
                    prior?.createdAt ?: now, now, Math.addExact(prior?.revision ?: 0L, 1L))))
            }
            eventId
        }

    suspend fun recordSettlement(token: FinanceCommandToken, obligationId: String, expectedRevision: Long,
        cash: FinanceCashInput): String = command(token, "OBLIGATION_SETTLE", listOf(obligationId, expectedRevision, cash)) {
        val obligation = requireNotNull(dao.obligationRows().singleOrNull { it.id == obligationId && it.voidedAt == null })
        require(obligation.revision == expectedRevision) { "往来已改变，请重新查看" }
        val event = requireNotNull(intake.event(obligation.eventId))
        val flow = finance.saveEntryInTransaction(null, cash.accountId,
            if (obligation.creditorId == FinanceV16Integrity.SELF) FinanceDirection.INFLOW else FinanceDirection.OUTFLOW,
            cash.amountMinor, cash.description, cash.occurredAt, category = "", tags = emptyList(),
            event = FinanceEventInput(existingEventId = event.id, role = FinanceFlowRole.SETTLEMENT, expectedEventUpdatedAt = event.updatedAt))
        val now = clock()
        dao.putSettlement(listOf(FinanceSettlementEntity(id(), obligationId, flow.id, cash.amountMinor, now, now)))
        dao.putObligation(listOf(obligation.copy(updatedAt = now, revision = Math.addExact(obligation.revision, 1L))))
        touchEconomic(event.id)
        flow.id
    }

    /** Explicit legacy receivable/payable. A payment or a guessed expected amount never creates it. */
    suspend fun createObligation(token:FinanceCommandToken,eventId:String,expectedEventUpdatedAt:Long,
        counterpartyId:String,receivable:Boolean,amountMinor:Long):String =
        command(token,"OBLIGATION_CREATE",listOf(eventId,expectedEventUpdatedAt,counterpartyId,receivable,amountMinor)) {
            val event=requireNotNull(intake.event(eventId))
            require(event.voidedAt==null && event.updatedAt==expectedEventUpdatedAt)
            require(dao.economicRows().none {it.eventId==eventId && it.voidedAt==null}) {"新经济事项请通过承担分配建立义务"}
            require(counterpartyId!=FinanceV16Integrity.SELF && amountMinor>0)
            val now=clock()
            if(dao.partyRows().none {it.id==FinanceV16Integrity.SELF && it.voidedAt==null}) {
                val old=dao.partyRows().singleOrNull {it.id==FinanceV16Integrity.SELF}
                dao.putParty(listOf(FinancePartyEntity(FinanceV16Integrity.SELF,"本人",true,old?.createdAt ?: now,now,
                    Math.addExact(old?.revision ?: 0L,1L))))
            }
            val result=id()
            dao.putObligation(listOf(FinanceObligationEntity(result,eventId,null,
                if(receivable) FinanceV16Integrity.SELF else counterpartyId,
                if(receivable) counterpartyId else FinanceV16Integrity.SELF,amountMinor,event.currencyCode,now,now)))
            intake.updateEvent(event.copy(updatedAt=maxOf(now,event.updatedAt+1)))
            result
        }

    suspend fun linkExistingSettlement(token:FinanceCommandToken,obligationId:String,expectedRevision:Long,
        entryId:String,amountMinor:Long,expectedEntryRevision:Long?=null):String = command(token,"SETTLEMENT_LINK",listOf(obligationId,expectedRevision,entryId,amountMinor,expectedEntryRevision)) {
        val debt=requireNotNull(dao.obligationRows().singleOrNull {it.id==obligationId && it.voidedAt==null})
        require(debt.revision==expectedRevision)
        if(intake.activeLinksForEvent(debt.eventId).none {it.entryId==entryId && it.role in setOf(FinanceFlowRole.SETTLEMENT,FinanceFlowRole.REIMBURSEMENT)}) {
            finance.reassignExternalFlowInTransaction(entryId,requireNotNull(expectedEntryRevision),debt.eventId,FinanceFlowRole.SETTLEMENT)
        } else if(expectedEntryRevision!=null) require(db.financeDao().entry(entryId)?.revision==expectedEntryRevision)
        val now=clock();val result=id()
        // Integrity requires a confirmed same-event settlement allocation and available capacity.
        dao.putSettlement(listOf(FinanceSettlementEntity(result,obligationId,entryId,amountMinor,now,now)))
        dao.putObligation(listOf(debt.copy(updatedAt=now,revision=Math.addExact(debt.revision,1L))))
        touchEconomic(debt.eventId)
        result
    }

    suspend fun adjustExistingCash(token:FinanceCommandToken,eventId:String,expectedEventRevision:Long,entryId:String,expectedEntryRevision:Long,
        role:FinanceFlowRole,reduction:Long,lineId:String?=null):String=
        command(token,"COST_ADJUST_EXISTING",listOf(eventId,expectedEventRevision,entryId,expectedEntryRevision,role,reduction,lineId)) {
            require(role in setOf(FinanceFlowRole.REFUND,FinanceFlowRole.REBATE,FinanceFlowRole.CASHBACK))
            val detail=requireNotNull(dao.economicRows().singleOrNull {it.eventId==eventId && it.voidedAt==null})
            require(detail.revision==expectedEventRevision)
            val entry=requireNotNull(db.financeDao().entry(entryId));require(entry.direction==FinanceDirection.INFLOW)
            finance.reassignExternalFlowInTransaction(entryId,expectedEntryRevision,eventId,role)
            val link=intake.activeLinksForEvent(eventId).single {it.entryId==entryId}
            val now=clock()
            dao.putCostAdjustment(listOf(FinanceCostAdjustmentEntity(id(),eventId,link.id,reduction,lineId,now,now)))
            touchEconomic(eventId);entryId
        }

    suspend fun allocatePurchase(token:FinanceCommandToken,lineId:String,eventId:String,valueMinor:Long,
        personalMinor:Long?,expectedEventRevision:Long):String =
        command(token,"PURCHASE_ALLOCATE",listOf(lineId,eventId,valueMinor,personalMinor,expectedEventRevision)) {
            val detail=requireNotNull(dao.economicRows().singleOrNull {it.eventId==eventId && it.voidedAt==null})
            require(detail.revision==expectedEventRevision)
            val result=id();val now=clock()
            dao.putPurchaseAllocation(listOf(FinancePurchaseAllocationEntity(result,lineId,eventId,valueMinor,personalMinor,now,now)))
            touchEconomic(eventId);result
        }

    private suspend fun releasePurchaseAdjustments(eventId:String,lineId:String,now:Long) {
        dao.putCostAdjustment(dao.costAdjustmentRows().filter {it.eventId==eventId && it.lineId==lineId && it.voidedAt==null}
            .map {it.copy(lineId=null,updatedAt=now,revision=Math.addExact(it.revision,1L))})
    }
    suspend fun editPurchaseAllocation(token:FinanceCommandToken,allocationId:String,expectedRevision:Long,eventRevision:Long,
        valueMinor:Long,personalMinor:Long?,releaseAdjustments:Boolean=false):String =
        command(token,"PURCHASE_ALLOCATION_EDIT",listOf(allocationId,expectedRevision,eventRevision,valueMinor,personalMinor,releaseAdjustments)) {
            val row=requireNotNull(dao.purchaseAllocationRows().singleOrNull {it.id==allocationId && it.voidedAt==null})
            require(row.revision==expectedRevision && dao.economicRows().single {it.eventId==row.eventId && it.voidedAt==null}.revision==eventRevision)
            val now=clock()
            if(releaseAdjustments) releasePurchaseAdjustments(row.eventId,row.lineId,now)
            dao.putPurchaseAllocation(listOf(row.copy(valueMinor=valueMinor,personalMinor=personalMinor,updatedAt=now,revision=Math.addExact(row.revision,1L))))
            touchEconomic(row.eventId);allocationId
        }
    suspend fun removePurchaseAllocation(token:FinanceCommandToken,allocationId:String,expectedRevision:Long,eventRevision:Long,
        releaseAdjustments:Boolean=false):String =
        command(token,"PURCHASE_ALLOCATION_REMOVE",listOf(allocationId,expectedRevision,eventRevision,releaseAdjustments)) {
            val row=requireNotNull(dao.purchaseAllocationRows().singleOrNull {it.id==allocationId && it.voidedAt==null})
            require(row.revision==expectedRevision && dao.economicRows().single {it.eventId==row.eventId && it.voidedAt==null}.revision==eventRevision)
            val now=clock()
            if(releaseAdjustments) releasePurchaseAdjustments(row.eventId,row.lineId,now)
            dao.putPurchaseAllocation(listOf(row.copy(voidedAt=now,updatedAt=now,revision=Math.addExact(row.revision,1L))))
            touchEconomic(row.eventId);allocationId
        }

    suspend fun resolveWithoutCash(token: FinanceCommandToken, obligationId: String, expectedRevision: Long,
        kind: FinanceResolutionKind, amountMinor: Long, note: String): String =
        command(token, "OBLIGATION_RESOLVE", listOf(obligationId, expectedRevision, kind, amountMinor, note)) {
            val row = requireNotNull(dao.obligationRows().singleOrNull { it.id == obligationId && it.voidedAt == null })
            require(row.revision == expectedRevision)
            require(note.isNotBlank() && amountMinor > 0)
            val now = clock()
            val result = id()
            dao.putResolution(listOf(FinanceResolutionEntity(result, row.id, kind, amountMinor, note.trim(), now, now)))
            dao.putObligation(listOf(row.copy(updatedAt = now, revision = Math.addExact(row.revision, 1L))))
            touchEconomic(row.eventId)
            result
        }

    suspend fun recordAdjustment(token: FinanceCommandToken, eventId: String, expectedRevision: Long,
        role: FinanceFlowRole, cash: FinanceCashInput, personalReductionMinor: Long, lineId: String? = null): String =
        command(token, "COST_ADJUST", listOf(eventId, expectedRevision, role, cash, personalReductionMinor, lineId)) {
            require(role in setOf(FinanceFlowRole.REFUND, FinanceFlowRole.REBATE, FinanceFlowRole.CASHBACK))
            val detail = requireNotNull(dao.economicRows().singleOrNull { it.eventId == eventId && it.voidedAt == null })
            require(detail.revision == expectedRevision)
            val event = requireNotNull(intake.event(eventId))
            val row = finance.saveEntryInTransaction(null, cash.accountId, FinanceDirection.INFLOW, cash.amountMinor,
                cash.description, cash.occurredAt, category = "", tags = emptyList(),
                event = FinanceEventInput(existingEventId = eventId, role = role, expectedEventUpdatedAt = event.updatedAt),authority="V16_COST_ADJUST")
            val link = intake.activeLinksForEvent(eventId).single { it.entryId == row.id }
            val now = clock()
            dao.putCostAdjustment(listOf(FinanceCostAdjustmentEntity(id(), eventId, link.id, personalReductionMinor, lineId, now, now)))
            touchEconomic(eventId)
            row.id
        }

    private suspend fun touchEconomic(eventId: String) {
        dao.economicRows().singleOrNull { it.eventId == eventId }?.let {
            dao.putEconomic(listOf(it.copy(updatedAt = clock(), revision = Math.addExact(it.revision, 1L))))
        }
    }
}
