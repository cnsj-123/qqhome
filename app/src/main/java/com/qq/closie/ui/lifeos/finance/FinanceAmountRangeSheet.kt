package com.qq.closie.ui.lifeos.finance

import com.qq.closie.life.ui.theme.BundledText as Text
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import com.qq.closie.life.finance.*

@Composable
internal fun FinanceAmountRangeSheet(filter:FinanceFilter,currencies:List<String>,apply:(Long?,Long?,String?)->Unit,close:()->Unit) {
    var currency by rememberSaveable {mutableStateOf(filter.currencyCode ?: "CNY")}
    var minimum by rememberSaveable {mutableStateOf(filter.minimumMinor?.let {FinanceMoney.input(it,currency)}.orEmpty())}
    var maximum by rememberSaveable {mutableStateOf(filter.maximumMinor?.let {FinanceMoney.input(it,currency)}.orEmpty())}
    var error by remember {mutableStateOf<String?>(null)}
    FinanceReceipt("金额与币种筛选",error=error,onClose=close,actions={Row {
        TextButton({apply(null,null,null);close()}) {Text("清除此筛选")}
        Button({
            runCatching {
                val low=minimum.takeIf(String::isNotBlank)?.let {FinanceMoney.parse(it,currency,true)}
                val high=maximum.takeIf(String::isNotBlank)?.let {FinanceMoney.parse(it,currency,true)}
                require(low==null || high==null || low<=high) {"最低金额不能高于最高金额"}
                apply(low,high,currency);close()
            }.onFailure {error=it.message ?: "金额格式不正确"}
        }) {Text("应用条件")}
    }}) {
        FinanceChoice("币种",currency,(currencies+"CNY").distinct().map {it to it}) {currency=it;minimum="";maximum=""}
        OutlinedTextField(minimum,{minimum=it},label={Text("最低金额（含，留空不限）")})
        OutlinedTextField(maximum,{maximum=it},label={Text("最高金额（含，留空不限）")})
        Text("金额按所选币种的精度比较。成本调整允许负数；存在金额条件时，未知承担不冒充 0 或确定金额。")
    }
}
