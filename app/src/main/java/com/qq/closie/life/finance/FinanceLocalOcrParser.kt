package com.qq.closie.life.finance

data class FinanceOcrFields(val amountMinor:Long?,val occurredAt:Long?,val suggestedKind:FinanceProposalKind?,
    val role:FinanceFlowRole?,val issue:String)

/** Finite local grammar. Ambiguous fields stay null, and raw OCR prose is never stored. */
object FinanceLocalOcrParser {
    fun parse(raw:String):FinanceOcrFields {
        val text=raw.take(24_000)
        val amounts=mutableSetOf<Long>()
        text.lineSequence().take(200).filterNot {it.contains("余额")}.forEach {line->
            val pattern=Regex("(?:[¥￥]|(?:实付|付款金额|交易金额|退款金额|收款金额|支付金额)[:：\\s]*)([0-9]{1,12}(?:\\.[0-9]{1,2})?)(?![0-9.])")
            pattern.findAll(line).forEach {match->runCatching {FinanceMoney.parse(match.groupValues[1])}.getOrNull()?.takeIf {it>0}?.let(amounts::add)}
        }
        val dates=Regex("(?:20[0-9]{2})[-/年][0-9]{1,2}[-/月][0-9]{1,2}日?[ T\\s]+[0-9]{1,2}:[0-9]{2}").findAll(text).mapNotNull {match->
            val parts=Regex("[0-9]+").findAll(match.value).map {it.value.toInt()}.toList()
            runCatching {FinanceTime.parse("%04d-%02d-%02d %02d:%02d".format(parts[0],parts[1],parts[2],parts[3],parts[4]))}.getOrNull()
        }.toSet()
        val refund=text.contains("退款成功") || text.contains("退款金额")
        val outgoing=listOf("支付成功","付款成功","实付","支出").any(text::contains)
        val incoming=listOf("收款成功","收款金额","收入").any(text::contains)
        val transfer=text.contains("还款成功") || text.contains("信用卡还款")
        val kind=when {
            refund && !outgoing->FinanceProposalKind.INCOME
            transfer && !refund->FinanceProposalKind.TRANSFER
            outgoing && !incoming && !refund->FinanceProposalKind.EXPENSE
            incoming && !outgoing && !refund->FinanceProposalKind.INCOME
            else->null
        }
        val issue=listOfNotNull(if(amounts.size!=1) "金额缺失或有多值，需手填" else null,
            if(dates.size!=1) "完整发生时间缺失或有多值，需手填" else null,if(kind==null) "收支方向需选择" else null,
            "本地识别仅供复核，不是已入账事实").joinToString("；")
        return FinanceOcrFields(amounts.singleOrNull(),dates.singleOrNull(),kind,if(refund) FinanceFlowRole.REFUND else null,issue)
    }
}
