package com.qq.closie.life.finance

import com.qq.closie.life.data.database.LifeDatabase
import java.util.UUID

/** Explicit source identities and dated instrument bindings are hints, never a second account ledger. */
class FinanceProvenanceRepository(private val db:LifeDatabase,finance:FinanceRepository,private val clock:()->Long=System::currentTimeMillis) {
    private val commands=FinanceCommandRunner(db,finance,clock)
    private val dao get()=db.financeV16Dao()
    private fun id()=UUID.randomUUID().toString()
    suspend fun createIdentity(token:FinanceCommandToken,family:String,label:String):String=commands.run(token,"IDENTITY_CREATE",listOf(family,label)) {
        require(family.matches(Regex("[a-z][a-z0-9-]{0,31}")) && label.isNotBlank() && label.length<=100)
        val result=id();val now=clock()
        dao.putExternalIdentity(listOf(FinanceExternalIdentityEntity(id=result,sourceFamily=family,label=label.trim(),
            digestNamespace="$family:$result",createdAt=now,updatedAt=now)));result
    }
    suspend fun approveRestoredIdentity(token:FinanceCommandToken,identityId:String,expectedRevision:Long):String=
        commands.run(token,"IDENTITY_APPROVE",listOf(identityId,expectedRevision)) {
            val row=requireNotNull(dao.externalIdentityRows().singleOrNull {it.id==identityId && it.voidedAt==null})
            require(row.revision==expectedRevision)
            dao.putExternalIdentity(listOf(row.copy(matchUsable=true,updatedAt=clock(),revision=Math.addExact(row.revision,1L))));identityId
        }
    suspend fun createInstrument(token:FinanceCommandToken,identityId:String,label:String,kind:String):String=
        commands.run(token,"INSTRUMENT_CREATE",listOf(identityId,label,kind)) {
            require(dao.externalIdentityRows().any {it.id==identityId && it.voidedAt==null && it.matchUsable})
            require(label.isNotBlank() && label.length<=100 && kind in PaymentChannelHint.entries.map {it.name})
            val result=id();val now=clock()
            dao.putInstrument(listOf(FinanceInstrumentEntity(result,identityId,label.trim(),kind,now,now)));result
        }
    suspend fun bindInstrument(token:FinanceCommandToken,instrumentId:String,accountId:String,from:Long,expectedOpenBindingId:String?):String=
        commands.run(token,"INSTRUMENT_BIND",listOf(instrumentId,accountId,from,expectedOpenBindingId)) {
            val instrument=requireNotNull(dao.instrumentRows().singleOrNull {it.id==instrumentId && it.voidedAt==null})
            require(dao.externalIdentityRows().any {it.id==instrument.identityId && it.voidedAt==null && it.matchUsable})
            require(db.financeDao().account(accountId)?.archivedAt==null && db.financeDao().account(accountId)!=null)
            val open=dao.accountBindingRows().singleOrNull {it.instrumentId==instrumentId && it.voidedAt==null && it.validUntil==null}
            require(open?.id==expectedOpenBindingId) {"付款工具绑定已改变，请重新查看"}
            val now=clock()
            if(open!=null) {
                require(from>open.validFrom)
                dao.putAccountBinding(listOf(open.copy(validUntil=from,updatedAt=now,revision=Math.addExact(open.revision,1L))))
            }
            val result=id();dao.putAccountBinding(listOf(FinanceAccountBindingEntity(result,instrumentId,accountId,from,createdAt=now,updatedAt=now)))
            result
        }
    suspend fun unbind(token:FinanceCommandToken,bindingId:String,expectedRevision:Long,until:Long):String=
        commands.run(token,"INSTRUMENT_UNBIND",listOf(bindingId,expectedRevision,until)) {
            val row=requireNotNull(dao.accountBindingRows().singleOrNull {it.id==bindingId && it.voidedAt==null && it.validUntil==null})
            require(row.revision==expectedRevision && until>row.validFrom)
            dao.putAccountBinding(listOf(row.copy(validUntil=until,updatedAt=clock(),revision=Math.addExact(row.revision,1L))));bindingId
        }
    /** Caller holds acquisition's Room transaction. Raw page/notification text is never accepted. */
    internal suspend fun recordVisible(proposal:FinanceProposalEntity,e:NormalizedTransactionEvidence) {
        val now=clock();val evidence=id()
        dao.putEvidence(listOf(FinanceEvidenceEntity(evidence,"visible-detail",e.providerKey,referenceKind=e.referenceKind,
            referenceDigest=e.referenceDigest,digestNamespace=e.providerKey+":unscoped:"+e.referenceKind.name,capturedAt=e.observedAt,
            summary="已提取金额、时间、方向；来源身份尚未确认",relatedOrderDigest=e.relatedOrderDigest,createdAt=now,updatedAt=now)))
        dao.putExtraction(listOf(FinanceExtractionEntity(id(),evidence,1,e.amountMinor,e.currencyCode,e.occurredAt,
            if(e.kind==FinanceProposalKind.INCOME) FinanceDirection.INFLOW else if(e.kind==FinanceProposalKind.EXPENSE) FinanceDirection.OUTFLOW else null,
            e.extractionVersion+" · 未确认正式账务",now,now)))
        dao.putEvidenceProposal(listOf(FinanceEvidenceProposalEntity(evidence,proposal.id)))
    }
    internal suspend fun recordNotification(proposal:FinanceProposalEntity) {
        val family=when(proposal.source){FinanceSource.WECHAT->"wechat";FinanceSource.ALIPAY->"alipay";else->return}
        val now=clock();val evidence=id()
        dao.putEvidence(listOf(FinanceEvidenceEntity(evidence,"notification",family,referenceKind=FinanceReferenceKind.NONE,
            digestNamespace="$family:unscoped:NONE",capturedAt=proposal.createdAt,summary="通知本机识别，仅保留金额、方向与时间；来源身份未确认",createdAt=now,updatedAt=now)))
        dao.putExtraction(listOf(FinanceExtractionEntity(id(),evidence,1,proposal.amountMinor,"CNY",proposal.occurredAt,
            if(proposal.kind==FinanceProposalKind.INCOME) FinanceDirection.INFLOW else if(proposal.kind==FinanceProposalKind.EXPENSE) FinanceDirection.OUTFLOW else null,
            if(proposal.amountMinor==null) "通知金额有歧义，需复核" else "通知建议，未入账",now,now)))
        dao.putEvidenceProposal(listOf(FinanceEvidenceProposalEntity(evidence,proposal.id)))
    }
    suspend fun classify(token:FinanceCommandToken,proposalId:String,proposalRevision:Long,evidenceId:String,evidenceRevision:Long,
        identityId:String?,instrumentId:String?,adoptBindingSuggestion:Boolean=false):String=commands.run(token,"EVIDENCE_CLASSIFY",listOf(proposalId,proposalRevision,evidenceId,evidenceRevision,identityId,instrumentId,adoptBindingSuggestion)) {
        val p=requireNotNull(db.financeIntakeDao().proposal(proposalId));require(p.revision==proposalRevision && p.status in setOf(FinanceProposalStatus.ACTIVE,FinanceProposalStatus.SNOOZED))
        require(dao.evidenceProposalRows().any {it.proposalId==proposalId && it.evidenceId==evidenceId})
        val e=requireNotNull(dao.evidenceRows().singleOrNull {it.id==evidenceId && it.voidedAt==null});require(e.revision==evidenceRevision)
        val identity=identityId?.let {id->requireNotNull(dao.externalIdentityRows().singleOrNull {it.id==id && it.voidedAt==null && it.matchUsable})}
        require(identity==null || identity.sourceFamily==e.sourceFamily)
        val instrument=instrumentId?.let {id->requireNotNull(dao.instrumentRows().singleOrNull {it.id==id && it.voidedAt==null && it.identityId==identityId})}
        val session=dao.reviewSessionRows().singleOrNull {it.proposalId==proposalId && it.voidedAt==null && it.state!=FinanceReviewState.COMPLETED}
        val occurredAt=session?.let {FinanceTime.resolve(it.occurredText,it.occurredAt)} ?: p.occurredAt
        val binding=instrument?.let {i->dao.accountBindingRows().singleOrNull {it.instrumentId==i.id && it.voidedAt==null &&
            it.validFrom<=occurredAt && (it.validUntil==null || occurredAt<it.validUntil)}}
        require(!adoptBindingSuggestion || binding!=null) {"此发生时间没有可用绑定，请先设置有效区间或选择手工核对账户"}
        val now=clock()
        dao.putEvidence(listOf(e.copy(identityId=identityId,identityRevision=identity?.revision,instrumentId=instrumentId,
            bindingId=binding?.id,bindingRevision=binding?.revision,digestNamespace=e.sourceFamily+":"+(identityId ?: "unscoped")+":"+e.referenceKind.name,
            updatedAt=now,revision=Math.addExact(e.revision,1L))))
        val next=Math.addExact(p.revision,1L)
        db.financeIntakeDao().updateProposal(p.copy(accountId=binding?.accountId,revision=next))
        session?.let {
            dao.putReviewSession(listOf(it.copy(accountId=if(adoptBindingSuggestion) binding!!.accountId else it.accountId,
                baseRevision=next,updatedAt=now,revision=Math.addExact(it.revision,1L))))
        }
        evidenceId
    }
    /** Revalidate a captured suggestion against the final cash time, also for existing-entry links. */
    internal suspend fun validateAccountSuggestion(proposalId:String,accountId:String,occurredAt:Long) {
        val ids=dao.evidenceProposalRows().filter {it.proposalId==proposalId}.map {it.evidenceId}.toSet()
        dao.evidenceRows().filter {it.id in ids && it.voidedAt==null && it.bindingId!=null}.forEach {e->
            val binding=requireNotNull(dao.accountBindingRows().singleOrNull {it.id==e.bindingId})
            if(accountId==binding.accountId) require(binding.voidedAt==null && binding.revision==e.bindingRevision &&
                binding.validFrom<=occurredAt && (binding.validUntil==null || occurredAt<binding.validUntil) &&
                dao.externalIdentityRows().any {it.id==e.identityId && it.voidedAt==null && it.matchUsable && it.revision==e.identityRevision}) {
                "来源身份、发生日期或工具绑定已改变，请重新应用来源分类；也可清除工具建议后手工核对账户"
            }
        }
    }
}

/** Order references are correlation hints. Only a scoped payment/refund reference may block duplicates. */
object FinanceEvidenceCorrelation {
    private data class Key(val family:String,val digest:String,val kind:FinanceReferenceKind,val identity:String?)
    private fun usableIdentity(e:FinanceEvidenceEntity,v:FinanceV16Snapshot?):String? = e.identityId?.takeIf {id->
        v?.externalIdentityRows.orEmpty().any {it.id==id && it.voidedAt==null && it.matchUsable && it.sourceFamily==e.sourceFamily}
    }
    private fun keys(p:FinanceProposalEntity,v:FinanceV16Snapshot?):List<Key> {
        if(!p.sourceKey.startsWith("visible:v3:")) {
            val parts=FinanceEvidenceOrigin.strongReference(p)?.split(':') ?: return emptyList()
            return listOf(Key(parts[2],parts[3],if(p.role==FinanceFlowRole.REFUND) FinanceReferenceKind.REFUND else FinanceReferenceKind.PAYMENT,null))
        }
        val ids=v?.evidenceProposalRows.orEmpty().filter {it.proposalId==p.id}.map {it.evidenceId}.toSet()
        return v?.evidenceRows.orEmpty().filter {it.id in ids && it.voidedAt==null && it.referenceKind in setOf(FinanceReferenceKind.PAYMENT,FinanceReferenceKind.REFUND) && it.referenceDigest!=null}
            .map {Key(it.sourceFamily,requireNotNull(it.referenceDigest),it.referenceKind,usableIdentity(it,v))}
    }
    fun reference(p:FinanceProposalEntity,v:FinanceV16Snapshot?):String? {
        if(!p.sourceKey.startsWith("visible:v3:")) return FinanceEvidenceOrigin.strongReference(p)
        val ids=v?.evidenceProposalRows.orEmpty().filter {it.proposalId==p.id}.map {it.evidenceId}.toSet()
        val keys=v?.evidenceRows.orEmpty().filter {it.id in ids && it.voidedAt==null && usableIdentity(it,v)!=null &&
            it.referenceKind in setOf(FinanceReferenceKind.PAYMENT,FinanceReferenceKind.REFUND) && it.referenceDigest!=null}
            .map {it.digestNamespace+":"+it.referenceDigest}.distinct()
        return keys.singleOrNull()
    }
    fun blocking(p:FinanceProposalEntity,all:List<FinanceProposalEntity>,v:FinanceV16Snapshot?):List<FinanceProposalEntity> {
        val own=keys(p,v)
        return all.filter {other->other.id!=p.id && other.status in setOf(FinanceProposalStatus.ACTIVE,FinanceProposalStatus.SNOOZED,FinanceProposalStatus.CONFIRMED) &&
            keys(other,v).any {b->own.any {a->a.family==b.family && a.digest==b.digest && a.kind==b.kind &&
                (a.identity==null || b.identity==null || a.identity==b.identity)}}}
    }
}
