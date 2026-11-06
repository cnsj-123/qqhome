package com.qq.closie.ui.lifeos.finance

import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import com.qq.closie.R
import com.qq.closie.life.finance.FinanceSnapshot

/** Source icons from the record frame. Explicit user icon assignments remain authoritative. */
@Composable
internal fun FinanceEntryGlyph(snapshot:FinanceSnapshot,categoryId:String?,name:String,modifier:Modifier,tint:Color) {
    val custom=categoryId!=null && snapshot.v16?.categoryIconRows.orEmpty().any {it.categoryId==categoryId && it.voidedAt==null}
    val resource=if(custom) null else when(name) {
        "餐饮"->R.drawable.lifeos_finance_entry_food
        "交通"->R.drawable.lifeos_finance_entry_transport
        "购物"->R.drawable.lifeos_finance_entry_shopping
        "居家"->R.drawable.lifeos_finance_entry_home
        "咖啡"->R.drawable.lifeos_finance_entry_coffee
        "园艺"->R.drawable.lifeos_finance_entry_garden
        "人情"->R.drawable.lifeos_finance_entry_gift
        "医疗"->R.drawable.lifeos_finance_entry_medical
        "学习"->R.drawable.lifeos_finance_entry_study
        "早餐"->R.drawable.lifeos_finance_entry_breakfast
        "午餐"->R.drawable.lifeos_finance_entry_lunch
        "晚餐"->R.drawable.lifeos_finance_entry_dinner
        "下午茶"->R.drawable.lifeos_finance_entry_tea
        "宵夜","夜宵"->R.drawable.lifeos_finance_entry_supper
        else->null
    }
    if(resource!=null) Icon(painterResource(resource),null,modifier,tint=tint)
    else Icon(categoryId?.let {FinanceCategoryGlyph.forCategory(snapshot,it,name)} ?: FinanceCategoryGlyph.forName(name),null,modifier,tint=tint)
}
