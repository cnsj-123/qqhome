package com.qq.closie.life.finance

data class FinanceSharePreview(val grantId:String,val grantRevision:Long,val epoch:Long,val text:String)

/** Sharing reads a single explicit SHARE grant. Home/query grants cannot widen this payload. */
object FinanceShareProjection {
    fun preview(s:FinanceSnapshot,grantId:String,now:Long):FinanceSharePreview {
        val v=requireNotNull(s.v16)
        val grant=requireNotNull(v.purposeGrantRows.singleOrNull {it.id==grantId && it.purpose==FinanceReadPurpose.SHARE &&
            it.providerId=="manual-share" && it.voidedAt==null && it.expiresAt>now}) {"此分享授权已失效，请重新选择范围"}
        val scoped=s.copy(v16=v.copy(purposeGrantRows=listOf(grant)))
        val facts=FinancePurposeRead.facts(scoped,FinanceReadPurpose.SHARE,"manual-share",now)
        require(facts.isNotEmpty() && facts.size<=100) {"一次分享需包含1至100个有效事项，请缩小授权范围"}
        val text=buildString {
            append("财务片段 · 用户选定范围\n金额为事项累计确认个人成本，含已发生调整；日期为事项经济发生时间。\n")
            facts.forEachIndexed {index,fact->
                append("\n").append(index+1).append(". ")
                if(grant.allowDescription) append(fact.description?.ifBlank {"备注为空"} ?: "备注未提供").append('\n')
                if(grant.allowAmount) {
                    append("个人成本：").append(fact.amountMinor?.let {FinanceMoney.display(it,fact.currencyCode)} ?: "未知")
                    if(fact.incomplete) append("，另有承担待确认")
                    append('\n')
                }
                if(grant.allowDate) append("经济发生时间：").append(fact.occurredAt?.let(FinanceTime::input) ?: "未知").append('\n')
            }
        }
        require(text.length<=100_000) {"片段过长，请减少事项或隐藏备注后重新预览"}
        return FinanceSharePreview(grant.id,grant.revision,v.controlRows.singleOrNull()?.restoreEpoch ?: 0L,text)
    }
    fun validate(s:FinanceSnapshot,preview:FinanceSharePreview,now:Long):String {
        val current=FinanceShareProjection.preview(s,preview.grantId,now)
        require(current==preview) {"授权、恢复代际或范围内事实已改变，请重新预览分享内容"}
        return current.text
    }
}
