package com.qq.closie.ui.lifeos.finance

import com.qq.closie.life.ui.theme.BundledText as Text
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.qq.closie.life.finance.*
import com.qq.closie.ui.lifeos.theme.LifeText
import java.time.Instant
import java.time.ZoneId
import java.time.YearMonth

@Composable
internal fun FinanceCostLine(row:PersonalCostRow,snapshot:FinanceSnapshot,editCash:(String)->Unit) {
    var details by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(vertical=4.dp)) {
        Text("个人成本 · "+row.description.ifBlank { snapshot.categories.firstOrNull { it.id==row.categoryId }?.name ?: "经济事项" },style=LifeText.body)
        Text(row.amountMinor?.let { FinanceMoney.display(it,row.currencyCode) } ?: "承担待确认",style=LifeText.number)
        Text(FinanceTime.input(row.occurredAt)+" · "+if(row.adjustment) "实际发生期成本调整" else "经济发生期个人承担",style=LifeText.caption)
        TextButton({ details=true }) { Text("查看构成") }
    }
    if(details) AlertDialog(onDismissRequest={details=false},title={Text("个人成本构成")},text={
        Column {
            Text(if(row.entryId==null) "此成本行没有本人资金流水引用。" else "关联真实资金流水："+row.entryId)
            row.eventId?.let { eventId ->
                val detail=snapshot.v16?.economicRows.orEmpty().firstOrNull { it.eventId==eventId && it.voidedAt==null }
                Text(detail?.valueMinor?.let { "事项价值 "+FinanceMoney.display(it,row.currencyCode) } ?: "事项价值未知")
                val total=FinanceCostProjection.eventTotal(snapshot,eventId)
                Text("累计已确认个人成本 "+FinanceMoney.display(total.confirmedMinor,row.currencyCode)+if(total.complete) "" else "，另有待确认")
                FinanceObligationProjection.rows(snapshot).filter { it.obligation.eventId==eventId }.forEach {
                    Text((if(it.obligation.debtorId=="self") "待付 " else "待收 ")+FinanceMoney.display(it.openMinor,row.currencyCode))
                }
            }
        }
    },confirmButton={TextButton({details=false}){Text("关闭")}},dismissButton={
        if(row.entryId!=null) TextButton({ details=false; editCash(row.entryId) }) { Text("查看真实流水") }
    })
}

@Composable
internal fun FinanceCostTrend(rows:List<PersonalCostRow>,select:(String,String)->Unit) {
    Column {
        Text("按经济与实际调整月份",style=LifeText.title)
        rows.groupBy { YearMonth.from(Instant.ofEpochMilli(it.occurredAt).atZone(ZoneId.systemDefault())) }
            .toSortedMap().forEach { (month,monthRows) ->
                monthRows.groupBy { it.currencyCode }.forEach { (currency,values) ->
                    val total=FinanceCostProjection.total(values)
                    TextButton({select(month.atDay(1).toString(),month.atEndOfMonth().toString())}) {
                        Text("$month · "+FinanceMoney.display(total.confirmedMinor,currency)+if(total.complete) "" else " + 待确认")
                    }
                }
            }
    }
}
