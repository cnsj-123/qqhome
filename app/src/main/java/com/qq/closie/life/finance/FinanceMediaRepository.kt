package com.qq.closie.life.finance

import android.content.Context
import android.net.Uri
import com.qq.closie.data.backup.RestoreStartupGate
import com.qq.closie.life.data.database.LifeDatabase
import com.qq.closie.life.media.MediaResourceRole
import com.qq.closie.life.media.MediaType
import com.qq.closie.life.repository.MediaRepository
import java.util.UUID

data class FinanceMediaTarget(val eventId:String?=null,val purchaseId:String?=null,val proposalId:String?=null) {
    init {require(listOfNotNull(eventId,purchaseId,proposalId).size==1)}
    fun matches(row:FinanceMediaLinkEntity)=row.eventId==eventId && row.purchaseId==purchaseId && row.proposalId==proposalId
}
data class FinanceSelectedDocument(val uri:String,val mimeType:String,val sha256:String,val sizeBytes:Long)
enum class FinanceMediaAvailability { AVAILABLE, MISSING_ASSET, SOURCE_MISSING, SOURCE_CHANGED }
data class FinanceMediaSource(val availability:FinanceMediaAvailability,val uri:String?=null,val mimeType:String?=null)

/** Typed financial ownership; media bytes and identity stay in the shared media domain. */
class FinanceMediaRepository(private val db:LifeDatabase,private val finance:FinanceRepository,private val clock:()->Long=System::currentTimeMillis) {
    private val commands=FinanceCommandRunner(db,finance,clock)
    private val media=MediaRepository(db)
    private val dao get()=db.financeV16Dao()
    private fun id()=UUID.randomUUID().toString()
    private suspend fun validateTarget(target:FinanceMediaTarget) {
        require(target.eventId==null || db.financeIntakeDao().events().any {it.id==target.eventId && it.voidedAt==null})
        require(target.purchaseId==null || dao.purchaseRows().any {it.id==target.purchaseId && it.voidedAt==null})
        require(target.proposalId==null || db.financeIntakeDao().proposal(target.proposalId)!=null)
    }
    suspend fun createOcrCandidate(token:FinanceCommandToken,fields:FinanceOcrFields,chosenKind:FinanceProposalKind,
        document:FinanceSelectedDocument,retainReceipt:Boolean):String=
        commands.run(token,"OCR_CANDIDATE",listOf(fields,chosenKind,document,retainReceipt)) {
            validateDocument(document)
            require(document.mimeType.startsWith("image/") && fields.issue.length<=240 && (fields.amountMinor==null || fields.amountMinor>0))
            require(fields.role!=FinanceFlowRole.REFUND || chosenKind==FinanceProposalKind.INCOME) {"退款需按收入方向复核，并关联原事项"}
            val now=clock();val proposalId=id();val evidenceId=id()
            val proposal=FinanceProposalPrivacy.sanitize(FinanceProposalEntity(proposalId,FinanceSource.SELECTED_IMAGE,"ocr:v1:${id()}","",now,
                fields.occurredAt ?: now,chosenKind,fields.amountMinor,"",role=fields.role ?: if(chosenKind==FinanceProposalKind.EXPENSE) FinanceFlowRole.PAYMENT else FinanceFlowRole.OTHER))
            db.financeIntakeDao().insertProposal(proposal)
            dao.putEvidence(listOf(FinanceEvidenceEntity(evidenceId,"local-ocr","selected-image",referenceKind=FinanceReferenceKind.NONE,
                digestNamespace="selected-image:unscoped:NONE",capturedAt=now,summary=fields.issue,createdAt=now,updatedAt=now)))
            dao.putExtraction(listOf(FinanceExtractionEntity(id(),evidenceId,1,fields.amountMinor,"CNY",fields.occurredAt,
                when(fields.suggestedKind){FinanceProposalKind.EXPENSE->FinanceDirection.OUTFLOW;FinanceProposalKind.INCOME->FinanceDirection.INFLOW;else->null},
                fields.issue,now,now)))
            dao.putEvidenceProposal(listOf(FinanceEvidenceProposalEntity(evidenceId,proposalId)))
            dao.putReviewSession(listOf(FinanceReviewSessionEntity(proposalId,0,token.restoreEpoch,
                fields.amountMinor?.let {FinanceMoney.input(it)}.orEmpty(),null,"","",proposal.description,FinanceReviewState.EDITING,
                kind=FinanceDraftKind.valueOf(chosenKind.name),occurredAt=proposal.occurredAt,
                occurredText=fields.occurredAt?.let(FinanceTime::input).orEmpty(),role=proposal.role,createdAt=now,updatedAt=now)))
            if(retainReceipt) attachInTransaction(FinanceMediaTarget(proposalId=proposalId),document,"所选识别凭据")
            proposalId
        }
    private fun validateDocument(document:FinanceSelectedDocument) {
        require(Uri.parse(document.uri).scheme=="content" && document.sha256.matches(Regex("[a-f0-9]{64}")))
        require(document.mimeType.startsWith("image/") || document.mimeType=="application/pdf")
        require(document.sizeBytes in 1..FinanceSelectedMediaIO.MAX_BYTES.toLong())
    }
    /** A selected existing original is referenced, never copied into a Finance directory. */
    suspend fun attach(token:FinanceCommandToken,target:FinanceMediaTarget,document:FinanceSelectedDocument,label:String):String=
        commands.run(token,"MEDIA_ATTACH",listOf(target,document,label)) {attachInTransaction(target,document,label)}

    internal suspend fun attachInTransaction(target:FinanceMediaTarget,document:FinanceSelectedDocument,label:String):String {
        validateTarget(target);validateDocument(document);require(label.length<=100)
        val matched=media.findResourceBySha256(document.sha256)
        val asset=matched?.let {media.getMediaAsset(it.mediaAssetId)} ?: media.createMediaAsset(
            if(document.mimeType=="application/pdf") MediaType.DOCUMENT else MediaType.IMAGE,timestamp=clock())
        val resources=media.getResourcesForAsset(asset.id)
        if(resources.none {it.contentUri==document.uri && it.sha256==document.sha256}) media.addMediaResource(asset.id,
            MediaResourceRole.ORIGINAL,document.mimeType,contentUri=document.uri,sha256=document.sha256,sizeBytes=document.sizeBytes,timestamp=clock())
        val existing=dao.mediaLinkRows().singleOrNull {it.voidedAt==null && it.mediaAssetId==asset.id && it.sourceHash==document.sha256 && target.matches(it)}
        if(existing!=null) return existing.id
        val now=clock();val result=id()
        dao.putMediaLink(listOf(FinanceMediaLinkEntity(result,asset.id,target.eventId,target.purchaseId,target.proposalId,
            document.sha256,label.trim().ifBlank {"凭据"},now,now)))
        return result
    }
    suspend fun linkSharedAsset(token:FinanceCommandToken,target:FinanceMediaTarget,assetId:String,sourceHash:String,label:String):String=
        commands.run(token,"MEDIA_REUSE",listOf(target,assetId,sourceHash,label)) {
            validateTarget(target);require(label.length<=100)
            require(media.getMediaAsset(assetId)!=null && media.getResourcesForAsset(assetId).any {it.sha256==sourceHash})
            val now=clock();val result=id()
            dao.putMediaLink(listOf(FinanceMediaLinkEntity(result,assetId,target.eventId,target.purchaseId,target.proposalId,
                sourceHash,label.trim().ifBlank {"凭据"},now,now)));result
        }
    suspend fun unlink(token:FinanceCommandToken,linkId:String,expectedRevision:Long):String=
        commands.run(token,"MEDIA_UNLINK",listOf(linkId,expectedRevision)) {
            val row=requireNotNull(dao.mediaLinkRows().singleOrNull {it.id==linkId && it.voidedAt==null})
            require(row.revision==expectedRevision)
            dao.putMediaLink(listOf(row.copy(voidedAt=clock(),updatedAt=clock(),revision=Math.addExact(row.revision,1L))));linkId
        }
    /** Re-selecting the exact original can recover access without changing the stable media ID. */
    suspend fun reconnect(token:FinanceCommandToken,linkId:String,expectedRevision:Long,document:FinanceSelectedDocument):String=
        commands.run(token,"MEDIA_RECONNECT",listOf(linkId,expectedRevision,document)) {
            validateDocument(document)
            val row=requireNotNull(dao.mediaLinkRows().singleOrNull {it.id==linkId && it.voidedAt==null})
            require(row.revision==expectedRevision && document.sha256==row.sourceHash) {"所选文件与原凭据摘要不同，不能替换原件"}
            media.ensureReferencedAsset(row.mediaAssetId,if(document.mimeType=="application/pdf") MediaType.DOCUMENT else MediaType.IMAGE,clock())
            if(media.getResourcesForAsset(row.mediaAssetId).none {it.contentUri==document.uri && it.sha256==document.sha256}) {
                media.addMediaResource(row.mediaAssetId,MediaResourceRole.ORIGINAL,document.mimeType,contentUri=document.uri,
                    sha256=document.sha256,sizeBytes=document.sizeBytes,timestamp=clock())
            }
            dao.putMediaLink(listOf(row.copy(updatedAt=clock(),revision=Math.addExact(row.revision,1L))));linkId
        }
    /** URI grants are device state. Check current bytes on explicit open; a restored URI is no proof. */
    suspend fun source(context:Context,link:FinanceMediaLinkEntity):FinanceMediaSource=RestoreStartupGate.withBusinessAccessSuspending {
        if(media.getMediaAsset(link.mediaAssetId)==null) return@withBusinessAccessSuspending FinanceMediaSource(FinanceMediaAvailability.MISSING_ASSET)
        var changed=false
        for(resource in media.getResourcesForAsset(link.mediaAssetId).filter {it.sha256==link.sourceHash && it.contentUri!=null}) {
            val document=FinanceSelectedMediaIO.inspectOrNull(context,Uri.parse(resource.contentUri)) ?: continue
            if(document.sha256==link.sourceHash) return@withBusinessAccessSuspending FinanceMediaSource(FinanceMediaAvailability.AVAILABLE,document.uri,document.mimeType)
            changed=true
        }
        FinanceMediaSource(if(changed) FinanceMediaAvailability.SOURCE_CHANGED else FinanceMediaAvailability.SOURCE_MISSING)
    }
}
