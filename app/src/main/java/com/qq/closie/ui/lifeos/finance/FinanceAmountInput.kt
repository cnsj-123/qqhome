package com.qq.closie.ui.lifeos.finance

import com.qq.closie.life.ui.theme.BundledText as Text
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.qq.closie.life.finance.*
import com.qq.closie.ui.lifeos.theme.LifeText

@Composable
internal fun FinanceAmountInput(value:String,currency:String,enabled:Boolean,onChange:(String)->Unit) {
    var keypad by remember { mutableStateOf(true) }
    var calculationError by remember { mutableStateOf<String?>(null) }
    OutlinedTextField(value,{onChange(it);calculationError=null},Modifier.fillMaxWidth(),label={Text("金额 · $currency")},
        textStyle=LifeText.number,singleLine=true,enabled=enabled,
        keyboardOptions=androidx.compose.foundation.text.KeyboardOptions(keyboardType=androidx.compose.ui.text.input.KeyboardType.Decimal))
    TextButton({keypad=!keypad}) { Text(if(keypad) "收起金额键盘" else "展开金额键盘与计算器") }
    if(keypad) Column(verticalArrangement=Arrangement.spacedBy(2.dp)) {
        listOf(listOf("7","8","9","÷"),listOf("4","5","6","×"),listOf("1","2","3","−"),listOf(".","0","⌫","+")).forEach { keys ->
            Row(horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                keys.forEach { key -> OutlinedButton(enabled=enabled,modifier=Modifier.weight(1f),onClick={
                    onChange(if(key=="⌫") value.dropLast(1) else value+if(key=="−") "-" else key)
                    calculationError=null
                }) { Text(key) } }
            }
        }
        TextButton(enabled=enabled,onClick={
            runCatching { FinanceCalculator.evaluate(value,currency) }.onSuccess { onChange(FinanceMoney.input(it,currency)) }
                .onFailure { calculationError=it.message }
        }) { Text("＝ 计算金额") }
    }
    calculationError?.let { Text(it,color=MaterialTheme.colorScheme.error,style=LifeText.caption) }
}
