package com.qq.closie.ui.lifeos.finance

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import com.qq.closie.life.ui.theme.BundledText as Text
import com.qq.closie.ui.lifeos.theme.LifeText

/** Bounds composed history rows only. Callers calculate financial totals from the complete result. */
@Composable
internal fun <T> FinancePagedItems(rows:List<T>,scope:String,itemKey:(T)->String,pageSize:Int=30,
    content:@Composable (T)->Unit) {
    require(pageSize in 1..100)
    var requestedPage by rememberSaveable(scope) {mutableIntStateOf(0)}
    val rowStates=rememberSaveableStateHolder()
    val lastPage=if(rows.isEmpty()) 0 else (rows.size-1)/pageSize
    val page=requestedPage.coerceIn(0,lastPage)
    val start=page*pageSize
    val end=minOf(rows.size,start+pageSize)
    if(rows.size>pageSize) Row {
        TextButton({requestedPage=page-1},enabled=page>0) {Text("上一页")}
        Text("${start+1}–$end / ${rows.size}",style=LifeText.caption)
        TextButton({requestedPage=page+1},enabled=page<lastPage) {Text("下一页")}
    }
    rows.subList(start,end).forEach {row->key(scope,itemKey(row)) {rowStates.SaveableStateProvider(itemKey(row)) {content(row)}}}
}
