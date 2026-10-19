package com.qq.closie.ui.lifeos.finance

import com.qq.closie.life.ui.theme.BundledText as Text
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.listSaver
import com.qq.closie.life.finance.*
import java.time.LocalDate
import java.time.ZoneId

internal data class FinanceRegisterFilter(val query:String="",val direction:String="",val start:String="",val end:String="",
    val after:Long?=null,val through:Long?=null,val posted:Boolean=false) {
    fun range()=runCatching {
        val first=start.takeIf(String::isNotBlank)?.let {LocalDate.parse(it.trim()).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()}
        val last=end.takeIf(String::isNotBlank)?.let {LocalDate.parse(it.trim()).plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()}
        require(first==null || last==null || first<last)
        FinanceDateRange(first,last)
    }
    fun apply(all:List<FinanceRegisterRow>,snapshot:FinanceSnapshot?=null):List<FinanceRegisterRow> {
        val range=range()
        val timings=snapshot?.v16?.entryTimingRows.orEmpty().filter {it.voidedAt==null}.associateBy {it.entryId}
        return if(range.isFailure) emptyList() else all.filter {row->
        val comparison=if(posted) timings[row.entry.id]?.postedTimeBounds() else FinanceTimeBounds(row.entry.occurredAt,row.entry.occurredAt)
        range.getOrThrow().contains(row.entry.occurredAt) &&
        ((after==null && through==null) || (comparison!=null && (after==null || comparison.earliest>after) && (through==null || comparison.latest<=through))) &&
        (direction.isBlank() || row.entry.direction.name==direction) && (query.isBlank() || row.entry.description.contains(query,true))}
    }
    companion object {
        val Saver=listSaver<FinanceRegisterFilter,String>(save={listOf(it.query,it.direction,it.start,it.end,it.after?.toString().orEmpty(),it.through?.toString().orEmpty(),it.posted.toString())},
            restore={FinanceRegisterFilter(it[0],it[1],it[2],it[3],it.getOrNull(4)?.toLongOrNull(),it.getOrNull(5)?.toLongOrNull(),it.getOrNull(6)=="true")})
    }
}

@Composable
internal fun FinanceRegisterFilters(value:FinanceRegisterFilter,total:Int,shown:Int,change:(FinanceRegisterFilter)->Unit) {
    var expanded by rememberSaveable {mutableStateOf(false)}
    TextButton({expanded=!expanded}) {Text("流水筛选 · 显示 $shown / $total 笔")}
    if(value.after!=null || value.through!=null) Text("核对区间 · "+(if(value.posted) "确知入账日（未知不列入）" else "实际收付日")+" · 晚于 "+
        (value.after?.let(FinanceTime::input) ?: "不限")+" 至 "+(value.through?.let(FinanceTime::input) ?: "不限")+"（含）")
    if(expanded) Column {
        OutlinedTextField(value.query,{change(value.copy(query=it.take(200)))},label={Text("搜索流水备注")})
        FinanceChoice("方向",value.direction,listOf("" to "全部方向","OUTFLOW" to "流出","INFLOW" to "流入")){change(value.copy(direction=it))}
        OutlinedTextField(value.start,{change(value.copy(start=it,after=null,through=null,posted=false))},label={Text("从日期 · 年-月-日，可留空")})
        OutlinedTextField(value.end,{change(value.copy(end=it,after=null,through=null,posted=false))},label={Text("到日期 · 年-月-日，含当天，可留空")})
        if(value.range().isFailure) Text("请填写有效日期且结束不早于开始。",color=MaterialTheme.colorScheme.error)
        Text("逐笔余额按完整账户流水计算，筛选只隐藏记录，不改变每笔余额。")
        TextButton({change(FinanceRegisterFilter())}) {Text("清除账户流水筛选")}
    }
}
