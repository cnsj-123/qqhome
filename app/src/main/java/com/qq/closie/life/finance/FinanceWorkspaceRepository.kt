package com.qq.closie.life.finance

import com.qq.closie.life.data.database.LifeDatabase
import java.security.MessageDigest
import java.util.UUID

internal class FinanceDraftGenerationChanged:IllegalStateException("上一笔连续记录已提交，请使用新一笔草稿")

data class FinanceAnchorPreview(val accountId: String, val beforeFingerprint: String,
    val openingMinor: Long, val anchorAt: Long, val fromZeroHistory: Boolean,
    val oldBalanceMinor: Long, val proposedBalanceMinor: Long, val affectedReconciliations: Int,
    val proposalDigest:String)

/** Owns durable user configuration, drafts, account observations and finite query scopes. */
class FinanceWorkspaceRepository(private val db: LifeDatabase, private val finance: FinanceRepository,
    private val clock: () -> Long = System::currentTimeMillis) {
    private val dao get()=db.financeV16Dao()
    private val commands=FinanceCommandRunner(db,finance,clock)
    private fun id()=UUID.randomUUID().toString()
    private fun hash(value:Any?)=MessageDigest.getInstance("SHA-256").digest(com.google.gson.Gson().toJson(value).toByteArray())
        .joinToString("") { "%02x".format(it) }

    suspend fun saveQuery(token:FinanceCommandToken, name:String, spec:FinanceQuerySpec):String =
        commands.run(token,"QUERY_SAVE",listOf(name,spec)) {
            spec.validate(); require(name.isNotBlank())
            val now=clock(); val result=id()
            dao.putSavedQuery(listOf(FinanceSavedQueryEntity(result,name.trim(),1,com.google.gson.Gson().toJson(spec),now,now)))
            result
        }
    fun queryFrom(row:FinanceSavedQueryEntity):FinanceQuerySpec {
        require(row.specVersion==1 && row.voidedAt==null)
        return com.google.gson.Gson().fromJson(row.specJson,FinanceQuerySpec::class.java).also { it.validate() }
    }

    suspend fun saveDraft(token:FinanceCommandToken, draft:FinanceDraftEntity, tags:Set<String>,
        expected:List<FinanceDraftExpectedEntity>, expectedRevision:Long?):String =
        commands.run(token,"DRAFT_SAVE",listOf(draft,tags,expected,expectedRevision)) {
            val old=dao.draftRows().singleOrNull { it.id==draft.id }
            require(if(old==null) expectedRevision==null else old.revision==expectedRevision && old.voidedAt==null) { "草稿已改变，请重新打开" }
            if(old!=null && draft.editGeneration!=old.editGeneration) throw FinanceDraftGenerationChanged()
            require(draft.epoch==token.restoreEpoch)
            require(tags.all { it.isNotBlank() } && expected.map { it.position }.distinct().size==expected.size)
            val now=clock()
            dao.putDraft(listOf(draft.copy(createdAt=old?.createdAt ?: now,updatedAt=now,
                revision=Math.addExact(old?.revision ?: 0L,1L),voidedAt=null)))
            dao.clearDraftTags(draft.id)
            dao.putDraftTag(tags.map { FinanceDraftTagEntity(draft.id,it) })
            dao.clearDraftExpected(draft.id)
            dao.putDraftExpected(expected.map { it.copy(draftId=draft.id) })
            draft.id
        }
    suspend fun resumeDraft(token:FinanceCommandToken,draftId:String):String =
        commands.run(token,"DRAFT_RESUME",draftId) {
            val old=requireNotNull(dao.draftRows().singleOrNull { it.id==draftId && it.voidedAt==null })
            dao.putDraft(listOf(old.copy(epoch=token.restoreEpoch,revision=Math.addExact(old.revision,1L),updatedAt=clock())))
            draftId
        }
    suspend fun discardDraft(token:FinanceCommandToken,draftId:String,expectedRevision:Long):String =
        commands.run(token,"DRAFT_DISCARD",listOf(draftId,expectedRevision)) {
            val row=requireNotNull(dao.draftRows().singleOrNull { it.id==draftId && it.voidedAt==null })
            require(row.revision==expectedRevision)
            dao.putDraft(listOf(row.copy(voidedAt=clock(),updatedAt=clock(),revision=Math.addExact(row.revision,1L))))
            draftId
        }
    suspend fun commitDraft(token:FinanceCommandToken,draftId:String,expectedRevision:Long,resolvedOccurredAt:Long):String =
        commands.run(token,"DRAFT_CONFIRM",listOf(draftId,expectedRevision,resolvedOccurredAt)) {
            val row=requireNotNull(dao.draftRows().singleOrNull { it.id==draftId && it.voidedAt==null })
            require(row.epoch==token.restoreEpoch && row.revision==expectedRevision) { "草稿已改变，请重新核对" }
            val account=requireNotNull(db.financeDao().account(requireNotNull(row.accountId)))
            require(account.currencyCode==row.currencyCode)
            val amount=FinanceCalculator.evaluate(row.amountExpression,row.currencyCode)
            val tags=dao.draftTagRows().filter { it.draftId==draftId }.map { it.name }
            val expected=dao.draftExpectedRows().filter { it.draftId==draftId }.sortedBy { it.position }.map {
                FinanceExpectedFlowInput(it.expectedId,it.role,it.amountText.takeIf { value -> !it.cancelled && value.isNotBlank() }?.let { text ->
                    FinanceMoney.parse(text,row.currencyCode) },it.note,it.cancelled)
            }
            val effectiveRole=if(row.kind==FinanceDraftKind.INCOME && row.role==FinanceFlowRole.PAYMENT && row.eventId==null)
                FinanceFlowRole.OTHER else row.role
            val result=if(row.kind==FinanceDraftKind.TRANSFER) finance.saveTransferInTransaction(null,account.id,
                requireNotNull(row.targetAccountId),amount,row.description,resolvedOccurredAt).id
            else finance.saveEntryInTransaction(null,account.id,
                if(row.kind==FinanceDraftKind.INCOME) FinanceDirection.INFLOW else FinanceDirection.OUTFLOW,
                amount,row.description,resolvedOccurredAt,row.categoryName,tags,row.subcategoryName,event=FinanceEventInput(row.nature,
                    row.shareText.takeIf { it.isNotBlank() }?.let { FinanceMoney.parse(it,row.currencyCode,allowNegative=true) },
                    effectiveRole,row.eventId,expected.takeIf { row.expectedEdited },
                    if(row.excluded) FinanceStatPolicy.EXCLUDE else FinanceStatPolicy.INCLUDE,
                    if(row.excludedBudget) FinanceBudgetPolicy.EXCLUDE else FinanceBudgetPolicy.INCLUDE,
                    expectedEventUpdatedAt=row.eventUpdatedAt),categoryId=row.categoryId).id
            val now=clock()
            dao.putDraft(listOf(if(row.continuous) row.copy(amountExpression="",description="",categoryId=null,categoryName="",subcategoryName="",
                editGeneration=Math.addExact(row.editGeneration,1L),
                eventId=null,shareText="",nature=FinanceNature.PERSONAL,expectedEdited=false,
                role=if(row.kind==FinanceDraftKind.INCOME) FinanceFlowRole.OTHER else FinanceFlowRole.PAYMENT,eventUpdatedAt=null,
                updatedAt=now,revision=Math.addExact(row.revision,1L))
                else row.copy(voidedAt=now,updatedAt=now,revision=Math.addExact(row.revision,1L))))
            if(row.continuous) { dao.clearDraftTags(draftId); dao.clearDraftExpected(draftId) }
            result
        }

    suspend fun saveObservation(token:FinanceCommandToken,accountId:String,amount:Long,at:Long,
        basis:FinanceBalanceBasis,precision:FinanceTimePrecision,note:String,civilDate:FinanceCivilDate?=null):String =
        commands.run(token,"OBSERVATION_SAVE",listOf(accountId,amount,at,basis,precision,note)+listOfNotNull(civilDate)) {
            require(db.financeDao().account(accountId)!=null)
            require(precision in setOf(FinanceTimePrecision.DATE,FinanceTimePrecision.INSTANT))
            FinanceCivilDate.validateNew(precision,at,civilDate)
            val result=id(); val now=clock()
            dao.putObservation(listOf(FinanceObservationEntity(result,accountId,amount,at,basis,precision,note,now,now,
                observedLocalDate=civilDate?.localDate,observedZoneId=civilDate?.zoneId,
                observedDayStartAt=civilDate?.startInclusive,observedDayEndExclusive=civilDate?.endExclusive,
                observedDateBasisVersion=civilDate?.basisVersion)))
            result
        }
    suspend fun confirmReconciliation(token:FinanceCommandToken,observationId:String,expectedFingerprint:String,note:String):String =
        commands.run(token,"RECONCILE_CONFIRM",listOf(observationId,expectedFingerprint,note)) {
            val s=readFinanceSnapshot(db,true)
            val observation=requireNotNull(s.v16!!.observationRows.singleOrNull { it.id==observationId && it.voidedAt==null })
            val projection=FinanceAccountProjection.reconcile(s,observation)
            require(projection.fingerprint==expectedFingerprint) { "核对范围发生变化，请重新计算" }
            val result=id(); val now=clock()
            val order=Math.addExact(dao.reconciliationRows().maxOfOrNull {it.confirmationOrder} ?: 0L,1L)
            dao.putReconciliation(listOf(FinanceReconciliationEntity(result,observationId,projection.bookMinor,projection.fingerprint,
                FinanceAccountProjection.RECONCILIATION_ALGORITHM,note,order,now,now)))
            result
        }
    suspend fun setPostingDate(token:FinanceCommandToken,entryId:String,expectedRevision:Long,postedAt:Long?,
        precision:FinanceTimePrecision,source:String,civilDate:FinanceCivilDate?=null):String =
        commands.run(token,"POSTING_CONFIRM",listOf(entryId,expectedRevision,postedAt,precision,source)+listOfNotNull(civilDate)) {
            val entry=requireNotNull(db.financeDao().entry(entryId))
            require(entry.voidedAt==null && entry.revision==expectedRevision)
            FinanceCivilDate.validateNew(precision,postedAt,civilDate)
            val old=dao.entryTimingRows().singleOrNull { it.entryId==entryId }
            val now=clock()
            dao.putEntryTiming(listOf(FinanceEntryTimingEntity(entryId,postedAt,
                old?.cashPrecision ?: FinanceTimePrecision.LEGACY,precision,source,old?.createdAt ?: now,now,
                Math.addExact(old?.revision ?: 0L,1L),postedLocalDate=civilDate?.localDate,postedZoneId=civilDate?.zoneId,
                postedDayStartAt=civilDate?.startInclusive,postedDayEndExclusive=civilDate?.endExclusive,
                postedDateBasisVersion=civilDate?.basisVersion)))
            db.financeDao().updateEntry(entry.copy(revision=Math.addExact(entry.revision,1L),updatedAt=now))
            entryId
        }

    private fun anchorHash(s:FinanceSnapshot,id:String)=hash(listOf(s.accounts.single { it.id==id },
        s.entries.filter { it.accountId==id }.sortedBy { it.id }))
    suspend fun previewAnchor(accountId:String,opening:Long,anchor:Long,fromZero:Boolean):FinanceAnchorPreview {
        val s=finance.snapshot(); val old=s.accounts.single { it.id==accountId }
        require(!fromZero || opening==0L)
        if(fromZero) require(s.entries.filter { it.accountId==accountId }.all { it.occurredAt>=anchor }) { "从零完整历史的起点须不晚于首笔流水" }
        val observations=s.v16?.observationRows.orEmpty().filter { it.accountId==accountId }.map { it.id }.toSet()
        return FinanceAnchorPreview(accountId,anchorHash(s,accountId),opening,anchor,fromZero,
            FinanceProjection.balance(old,s.entries),FinanceProjection.balance(old.copy(openingBalanceMinor=opening,balanceAnchorAt=anchor),s.entries),
            s.v16?.reconciliationRows.orEmpty().count { it.observationId in observations && it.voidedAt==null },
            hash(listOf(accountId,anchorHash(s,accountId),opening,anchor,fromZero)))
    }
    suspend fun confirmAnchor(token:FinanceCommandToken,p:FinanceAnchorPreview):String =
        commands.run(token,"ANCHOR_CONFIRM",p) {
            val s=readFinanceSnapshot(db,true)
            require(anchorHash(s,p.accountId)==p.beforeFingerprint) { "账户已改变，请重新预览" }
            require(p.proposalDigest==hash(listOf(p.accountId,p.beforeFingerprint,p.openingMinor,p.anchorAt,p.fromZeroHistory))) { "起点方案已改变，请重新预览" }
            require(!p.fromZeroHistory || (p.openingMinor==0L &&
                s.entries.filter { it.accountId==p.accountId }.all { it.occurredAt>=p.anchorAt }))
            val row=s.accounts.single { it.id==p.accountId }
            db.financeDao().updateAccount(row.copy(openingBalanceMinor=p.openingMinor,balanceAnchorAt=p.anchorAt,
                updatedAt=maxOf(clock(),row.updatedAt+1)))
            p.accountId
        }

    suspend fun setAccountPolicy(token:FinanceCommandToken,accountId:String,included:Boolean,
        liability:FinanceLiabilityConvention,anchorBasis:FinanceBalanceBasis,expectedRevision:Long?,expectedAccountUpdatedAt:Long?=null):String =
        commands.run(token,"ACCOUNT_POLICY",listOf(accountId,included,liability,anchorBasis,expectedRevision,expectedAccountUpdatedAt)) {
            val account=requireNotNull(db.financeDao().account(accountId)); require(account.archivedAt==null)
            require(expectedAccountUpdatedAt==null || account.updatedAt==expectedAccountUpdatedAt) {"账户起点或资料已改变，请重新核对净额口径"}
            require(liability!=FinanceLiabilityConvention.POSITIVE_DEBT || account.openingBalanceMinor>=0) {
                "期初已经是负数，请保留按账面符号计入"
            }
            val old=dao.accountPolicyRows().singleOrNull { it.accountId==accountId && it.voidedAt==null }
            require(old?.revision==expectedRevision)
            val now=clock()
            dao.putAccountPolicy(listOf(FinanceAccountPolicyEntity(accountId,included,liability,anchorBasis,
                old?.createdAt ?: now,now,Math.addExact(old?.revision ?: 0L,1L))))
            accountId
        }
    suspend fun saveCreditCycle(token:FinanceCommandToken,accountId:String,closingDay:Int,dueDay:Int,zone:String,expectedRevision:Long?=null):String =
        commands.run(token,"CREDIT_CYCLE",listOf(accountId,closingDay,dueDay,zone,expectedRevision)) {
            require(db.financeDao().account(accountId)?.kind==FinanceAccountKind.CREDIT)
            require(closingDay in 1..31 && dueDay in 1..31); java.time.ZoneId.of(zone)
            val old=dao.creditCycleRows().singleOrNull { it.accountId==accountId }; val now=clock()
            require(old?.revision==expectedRevision) { "账期设置已改变" }
            dao.putCreditCycle(listOf(FinanceCreditCycleEntity(accountId,closingDay,dueDay,zone,old?.createdAt ?: now,now,
                Math.addExact(old?.revision ?: 0L,1L))))
            accountId
        }
    suspend fun saveBudget(token:FinanceCommandToken,name:String,currency:String,limit:Long,categoryId:String?,
        start:Long,endExclusive:Long):String = commands.run(token,"BUDGET_CREATE",listOf(name,currency,limit,categoryId,start,endExclusive)) {
        require(name.isNotBlank() && limit>=0 && start<endExclusive); FinanceMoney.fractionDigits(currency)
        val result=id(); val now=clock()
        dao.putBudget(listOf(FinanceBudgetEntity(result,name.trim(),currency,limit,categoryId,start,endExclusive,now,now)))
        result
    }
    suspend fun setCategoryPolicy(token:FinanceCommandToken,categoryId:String,burden:FinanceBurden,iconKey:String,expectedRevision:Long?=null):String =
        commands.run(token,"CATEGORY_POLICY",listOf(categoryId,burden,iconKey,expectedRevision)) {
            require(db.financeDao().categories().any { it.id==categoryId })
            require(iconKey in setOf("food","transport","home","health","shopping","gift","other"))
            val now=clock(); val old=dao.categoryPolicyRows().singleOrNull { it.categoryId==categoryId }
            require(old?.revision==expectedRevision) {"分类政策已改变，请重新打开"}
            dao.putCategoryPolicy(listOf(FinanceCategoryPolicyEntity(categoryId,burden,old?.createdAt ?: now,now,
                Math.addExact(old?.revision ?: 0L,1L))))
            val icon=dao.categoryIconRows().singleOrNull { it.categoryId==categoryId }
            dao.putCategoryIcon(listOf(FinanceCategoryIconEntity(categoryId,iconKey,icon?.createdAt ?: now,now,
                Math.addExact(icon?.revision ?: 0L,1L))))
            categoryId
        }
    suspend fun editBudget(token:FinanceCommandToken,budgetId:String,expectedRevision:Long,name:String,limit:Long,
        categoryId:String?,start:Long,endExclusive:Long,disable:Boolean=false):String =
        commands.run(token,"BUDGET_EDIT",listOf(budgetId,expectedRevision,name,limit,categoryId,start,endExclusive,disable)) {
            val old=requireNotNull(dao.budgetRows().singleOrNull {it.id==budgetId && it.voidedAt==null})
            require(old.revision==expectedRevision && name.isNotBlank() && limit>=0 && start<endExclusive)
            dao.putBudget(listOf(old.copy(name=name.trim(),limitMinor=limit,categoryId=categoryId,startAt=start,endExclusive=endExclusive,
                voidedAt=if(disable) clock() else null,updatedAt=clock(),revision=Math.addExact(old.revision,1L))))
            budgetId
        }
    suspend fun removeQuery(token:FinanceCommandToken,queryId:String,expectedRevision:Long):String =
        commands.run(token,"QUERY_REMOVE",listOf(queryId,expectedRevision)) {
            val row=requireNotNull(dao.savedQueryRows().singleOrNull {it.id==queryId && it.voidedAt==null})
            require(row.revision==expectedRevision)
            dao.putSavedQuery(listOf(row.copy(voidedAt=clock(),updatedAt=clock(),revision=Math.addExact(row.revision,1L))))
            queryId
        }
    suspend fun grant(token:FinanceCommandToken,purpose:FinanceReadPurpose,providerId:String,
        eventIds:Set<String>,entryIds:Set<String>,amount:Boolean,date:Boolean,description:Boolean,expiresAt:Long):String =
        commands.run(token,"PURPOSE_GRANT",listOf(purpose,providerId,eventIds,entryIds,amount,date,description,expiresAt)) {
            require(providerId.isNotBlank() && (eventIds.isNotEmpty() || entryIds.isNotEmpty()) && expiresAt>clock())
            require(eventIds.size<=500 && entryIds.size<=500) {"请将授权范围缩小至500个事项或流水以内"}
            val result=id(); val now=clock()
            dao.putPurposeGrant(listOf(FinancePurposeGrantEntity(result,purpose,providerId,amount,date,description,expiresAt,now,now)))
            dao.putGrantEvent(eventIds.map { FinanceGrantEventEntity(result,it) })
            dao.putGrantEntry(entryIds.map { FinanceGrantEntryEntity(result,it) })
            result
        }
    suspend fun revoke(token:FinanceCommandToken,grantId:String):String = commands.run(token,"PURPOSE_REVOKE",grantId) {
        val row=requireNotNull(dao.purposeGrantRows().singleOrNull { it.id==grantId })
        dao.putPurposeGrant(listOf(row.copy(voidedAt=clock(),updatedAt=clock(),revision=Math.addExact(row.revision,1L))))
        grantId
    }
}
