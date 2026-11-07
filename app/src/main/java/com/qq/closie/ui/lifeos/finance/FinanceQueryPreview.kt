package com.qq.closie.ui.lifeos.finance

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.qq.closie.life.finance.*
import com.qq.closie.life.ui.theme.BundledText as Text
import com.qq.closie.ui.lifeos.theme.LocalLifeOsColors

internal data class FinanceQueryPreview(val spec:FinanceQuerySpec,val count:Int,
    val totals:Map<String,PersonalCostTotal>,val undatedCount:Int,
    val directions:Map<String,Set<FinanceDirection>> = emptyMap(),
    val confirmedCostCurrencies:Set<String> = emptySet(),val unknownDateCount:Int = undatedCount,
    val dateBasis:FinanceLedgerDateBasis=FinanceLedgerDateBasis.CASH_MOVEMENT) {
    companion object {
        fun fromLedger(spec:FinanceQuerySpec,result:FinanceLedgerQueryResult)=FinanceQueryPreview(
            spec,result.rows.size,result.totals,result.undatedRows.size,result.directions,
            unknownDateCount=result.unknownDateCount,dateBasis=result.dateBasis)
    }
    fun totalLabel(code:String,total:PersonalCostTotal):String {
        if(spec.mode==FinanceQueryMode.PERSONAL_COST) {
            if(!total.complete && code !in confirmedCostCurrencies)
                return "个人成本金额未知 · $code · ${total.unknownCount}项待确认"
            return (if(total.complete) "个人成本 " else "已确认个人成本 ")+FinanceMoney.display(total.confirmedMinor,code)+
                (if(total.complete) "" else " · ${total.unknownCount}项金额未知")
        }
        val cashDirections=directions[code].orEmpty()
        val net=total.confirmedMinor
        val label=when {
            cashDirections==setOf(FinanceDirection.INFLOW)->"资金流入 "
            cashDirections==setOf(FinanceDirection.OUTFLOW)->"资金流出 "
            net>0->"净资金流出 "
            net<0->"净资金流入 "
            else->"净资金变化 "
        }
        // Format the magnitude exactly, including Long.MIN_VALUE, without an overflowing abs().
        val magnitude=java.math.BigDecimal.valueOf(net,FinanceMoney.fractionDigits(code)).abs().toPlainString()
        return label+(if(code=="CNY") "¥" else "$code ")+magnitude
    }
}

internal fun financeQueryPreview(snapshot:FinanceSnapshot,spec:FinanceQuerySpec,
    dateBasis:FinanceLedgerDateBasis=FinanceLedgerDateBasis.ECONOMIC_LEDGER):FinanceQueryPreview =
    if(spec.mode==FinanceQueryMode.CASH_MOVEMENT) FinanceLedgerQuery.execute(snapshot,spec,dateBasis=dateBasis).let {
        FinanceQueryPreview.fromLedger(spec,it)
    } else FinanceQueryEngine.execute(snapshot,spec).let {
        FinanceQueryPreview(spec,it.costs.size,it.totals,it.undatedCosts.size,
            confirmedCostCurrencies=it.costs.filter {row->row.amountMinor!=null}.map {row->row.currencyCode}.toSet())
    }

@Composable
internal fun FinanceQueryPreviewSummary(result:FinanceQueryPreview,label:String="符合当前条件：",tag:String="finance-query-preview") {
    val colors=LocalLifeOsColors.current
    val single=result.totals.entries.singleOrNull()
    Column(Modifier.testTag(tag)) {
        Row(Modifier.fillMaxWidth().padding(top=12.dp),verticalAlignment=Alignment.CenterVertically) {
            Text(label,style=LedgerText.caption,color=colors.inkSecondary)
            LedgerOdometer(result.count.toString(),LedgerText.caption,colors.inkSecondary,
                modifier=Modifier.testTag("$tag-count"))
            Text(if(result.spec.mode==FinanceQueryMode.CASH_MOVEMENT) " 笔" else " 项",style=LedgerText.caption,color=colors.inkSecondary)
            single?.let {(code,total)->Text(" · "+result.totalLabel(code,total),Modifier.weight(1f),
                style=LedgerText.caption,color=colors.inkSecondary)}
        }
        if(single==null) result.totals.forEach {(code,total)->Text(result.totalLabel(code,total),style=LedgerText.caption,color=colors.inkSecondary)}
        if(result.undatedCount>0) Text("另有 ${result.undatedCount} "+
            (if(result.spec.mode==FinanceQueryMode.CASH_MOVEMENT) "笔资金记录" else "项成本")+
            "日期待确认，未计入期间合计",style=LedgerText.caption,color=colors.inkSecondary)
        else if(result.unknownDateCount>0) Text("其中 ${result.unknownDateCount} 笔日期待确认，按全部时间汇总",
            style=LedgerText.caption,color=colors.inkSecondary)
    }
}
