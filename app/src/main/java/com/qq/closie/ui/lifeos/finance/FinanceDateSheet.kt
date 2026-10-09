package com.qq.closie.ui.lifeos.finance

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import java.time.LocalDate

@Composable
internal fun FinanceDateSheet(error: String?, onApply: (String, String) -> Boolean, onClose: () -> Unit) {
    var start by rememberSaveable { mutableStateOf(LocalDate.now().withDayOfMonth(1).toString()) }
    var end by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
    FinanceReceipt("按日期查看", error = error, onClose = onClose) {
        OutlinedTextField(start, { start = it }, Modifier.fillMaxWidth(), label = { Text("开始日期 · 年-月-日") }, singleLine = true)
        OutlinedTextField(end, { end = it }, Modifier.fillMaxWidth(), label = { Text("结束日期 · 年-月-日（包含当天）") }, singleLine = true)
        Button(onClick = { if (onApply(start, end)) onClose() }, modifier = Modifier.fillMaxWidth()) { Text("应用日期范围") }
    }
}
