package com.qq.closie.life.shopping

import com.qq.closie.life.data.database.LifeDatabase
import com.qq.closie.life.finance.*
import java.util.UUID

data class ShoppingLineInput(val id:String=UUID.randomUUID().toString(),val title:String,val quantity:Int,val valueMinor:Long?)
data class ShoppingPurchaseInput(val id:String?=null,val expectedRevision:Long?=null,val title:String,
    val currencyCode:String,val occurredAt:Long,val lines:List<ShoppingLineInput>)

/** Owns Purchase/Line facts; Finance owns allocations and all money movements. */
class ShoppingRepository(private val db:LifeDatabase,finance:FinanceRepository,private val clock:()->Long=System::currentTimeMillis) {
    private val commands=FinanceCommandRunner(db,finance,clock)
    suspend fun save(token:FinanceCommandToken,input:ShoppingPurchaseInput):String =commands.run(token,"PURCHASE_SAVE",input) {
        require(input.title.isNotBlank() && input.lines.isNotEmpty() && input.lines.size<=200)
        require(input.lines.map {it.id}.distinct().size==input.lines.size)
        FinanceMoney.fractionDigits(input.currencyCode)
        val dao=db.financeV16Dao();val now=clock()
        val old=input.id?.let {id->requireNotNull(dao.purchaseRows().singleOrNull {it.id==id && it.voidedAt==null})}
        require(old?.revision==input.expectedRevision) {"购买记录已改变"}
        require(old==null || old.currencyCode==input.currencyCode)
        val id=old?.id ?: UUID.randomUUID().toString()
        val prior=dao.purchaseLineRows().filter {it.purchaseId==id}
        require(input.lines.all {line->dao.purchaseLineRows().none {it.id==line.id && it.purchaseId!=id}})
        val removed=prior.filter {row->input.lines.none {it.id==row.id}}
        require(removed.none {line->dao.purchaseAllocationRows().any {it.lineId==line.id && it.voidedAt==null}}) {"请先解除账务分配再移除商品明细"}
        dao.putPurchase(listOf(ShoppingPurchaseEntity(id,input.title.trim(),input.currencyCode,input.occurredAt,
            old?.createdAt ?: now,now,Math.addExact(old?.revision ?: 0L,1L))))
        dao.putPurchaseLine(removed.map {it.copy(voidedAt=now,updatedAt=now,revision=Math.addExact(it.revision,1L))})
        dao.putPurchaseLine(input.lines.map {line->
            val before=prior.singleOrNull {it.id==line.id}
            ShoppingPurchaseLineEntity(line.id,id,line.title.trim(),line.quantity,line.valueMinor,before?.createdAt ?: now,now,
                Math.addExact(before?.revision ?: 0L,1L))
        })
        id
    }
}

data class ShoppingCostPoint(val eventId:String,val at:Long,val amountMinor:Long?,val adjustment:Boolean,
    val sourceId:String="",val entryIds:Set<String> = emptySet())
data class ShoppingLineCost(val line:ShoppingPurchaseLineEntity,val points:List<ShoppingCostPoint>,
    val allocatedValueMinor:Long,val confirmedCostMinor:Long,val incomplete:Boolean,val unassignedAdjustmentCount:Int)
object ShoppingCostProjection {
    fun lines(s:FinanceSnapshot,purchaseId:String):List<ShoppingLineCost> {
        val v=requireNotNull(s.v16);val events=s.v4?.events.orEmpty().associateBy {it.id}
        val links=s.v4?.activeLinks.orEmpty().associateBy {it.id};val cash=s.entries.associateBy {it.id}
        return v.purchaseLineRows.filter {it.purchaseId==purchaseId && it.voidedAt==null}.map {line->
            val allocations=v.purchaseAllocationRows.filter {it.lineId==line.id && it.voidedAt==null}
            val eventIds=allocations.map {it.eventId}.toSet()
            val refunds=v.costAdjustmentRows.filter {it.lineId==line.id && it.voidedAt==null}
            val points=allocations.map {allocation->ShoppingCostPoint(allocation.eventId,events.getValue(allocation.eventId).occurredAt,allocation.personalMinor,false,
                allocation.id,v.fundingRows.filter {it.eventId==allocation.eventId && it.voidedAt==null}.mapNotNull {it.entryLinkId?.let {id->links[id]?.entryId}}.toSet())}+
                refunds.map {ShoppingCostPoint(it.eventId,cash.getValue(links.getValue(it.entryLinkId).entryId).occurredAt,-it.personalReductionMinor,true,
                    it.id,setOf(links.getValue(it.entryLinkId).entryId))}
            val allocated=exactSum(allocations.map {it.valueMinor})
            ShoppingLineCost(line,points.sortedBy {it.at},allocated,exactSum(points.mapNotNull {it.amountMinor}),
                allocations.isEmpty() || allocations.any {it.personalMinor==null} || line.valueMinor==null || allocated!=line.valueMinor,
                v.costAdjustmentRows.count {it.eventId in eventIds && it.lineId==null && it.voidedAt==null})
        }
    }
}
