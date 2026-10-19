package com.qq.closie.ui.lifeos.finance

import com.qq.closie.life.ui.theme.BundledText as Text
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import com.qq.closie.life.finance.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@Composable
internal fun FinanceBudgetEditor(state:FinanceUiState,vm:FinanceViewModel,budget:FinanceBudgetEntity,close:()->Unit) {
    val expected=rememberSaveable {budget.revision}
    val epoch=rememberSaveable {state.snapshot.v16?.controlRows?.singleOrNull()?.restoreEpoch ?: 0L}
    var name by rememberSaveable {mutableStateOf(budget.name)}
    var amount by rememberSaveable {mutableStateOf(FinanceMoney.input(budget.limitMinor,budget.currencyCode))}
    var category by rememberSaveable {mutableStateOf(budget.categoryId.orEmpty())}
    fun day(at:Long)=Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).toLocalDate().toString()
    var start by rememberSaveable {mutableStateOf(day(budget.startAt))}
    var end by rememberSaveable {mutableStateOf(day(budget.endExclusive-1))}
    FinanceReceipt("编辑预算",state.busy,state.error,close,actions={Button({vm.workspaceAction(epoch,close) {repo,token->
        val range=FinanceDateRange.days(LocalDate.parse(start),LocalDate.parse(end))
        repo.editBudget(token,budget.id,expected,name,FinanceMoney.parse(amount,budget.currencyCode,true),category.ifBlank {null},range.startInclusive!!,range.endExclusive!!)
    }},enabled=!state.busy) {Text("保存预算")}}) {
        Text("币种 ${budget.currencyCode}；修改规则后按原始事实重算，不改写历史流水。")
        OutlinedTextField(name,{name=it},label={Text("预算名称")})
        OutlinedTextField(amount,{amount=it},label={Text("预算上限")})
        FinanceChoice("分类",category,listOf("" to "全部分类")+state.snapshot.categories.map {it.id to (FinanceProjection.categoryPath(state.snapshot.categories,it.id) ?: it.name)}) {category=it}
        OutlinedTextField(start,{start=it},label={Text("开始日期 · 年-月-日")})
        OutlinedTextField(end,{end=it},label={Text("结束日期 · 含当天")})
    }
}
