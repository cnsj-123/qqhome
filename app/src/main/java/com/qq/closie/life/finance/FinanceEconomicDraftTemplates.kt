package com.qq.closie.life.finance

object FinanceEconomicDraftTemplates {
    /** Explicit form replacement only; the resulting receivable is created on later confirmation. */
    fun reimbursement(draftId:String,funding:List<FinanceEconomicDraftFundingEntity>,personalMinor:Long,
        counterpartyId:String,currency:String):List<FinanceEconomicDraftShareEntity> {
        require(counterpartyId.isNotBlank() && counterpartyId!="self") {"请选择确认应承担的报销方"}
        require(funding.isNotEmpty() && funding.all {it.draftId==draftId && it.partySelection=="self" && it.entryLinkSelection!=null}) {
            "快捷填写仅适用于已有本人真实付款；外部或混合出资请逐项确认分配"
        }
        val amounts=funding.map {FinanceMoney.parse(it.amountText,currency)}
        val total=exactSum(amounts)
        require(personalMinor in 0..total) {"个人承担不能小于0或超过实际垫付"}
        var remaining=personalMinor
        val result=mutableListOf<FinanceEconomicDraftShareEntity>()
        funding.zip(amounts).forEach {(fund,amount)->
            val own=minOf(amount,remaining);remaining=Math.subtractExact(remaining,own)
            if(own>0) result.add(FinanceEconomicDraftShareEntity(draftId,java.util.UUID.randomUUID().toString(),result.size,
                FinanceMoney.input(own,currency),"self","self",fund.localId,FinanceShareRelation.PERSONAL_CONFIRMED))
            val other=Math.subtractExact(amount,own)
            if(other>0) result.add(FinanceEconomicDraftShareEntity(draftId,java.util.UUID.randomUUID().toString(),result.size,
                FinanceMoney.input(other,currency),counterpartyId,counterpartyId,fund.localId,FinanceShareRelation.REPAYABLE_CONFIRMED))
        }
        require(result.size<=100) {"快捷拆分后超过100个份额，请减少出资项或逐项分配；原草稿已保留"}
        return result
    }
}
