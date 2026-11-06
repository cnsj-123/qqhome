package com.qq.closie.ui.lifeos.finance

import com.qq.closie.life.ui.theme.BundledText as Text
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import com.qq.closie.life.finance.*
import com.qq.closie.ui.lifeos.theme.LifeText

@Composable
internal fun FinanceAmountInput(value:String,currency:String,enabled:Boolean,showKeypad:Boolean = true,onChange:(String)->Unit) {
    var keypad by remember { mutableStateOf(showKeypad) }
    var calculationError by remember { mutableStateOf<String?>(null) }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    OutlinedTextField(value,{onChange(it);calculationError=null},Modifier.fillMaxWidth()
        .onFocusChanged { if (it.isFocused) keypad = false }, label={Text("金额 · $currency")},
        textStyle=LifeText.number,singleLine=true,enabled=enabled,
        keyboardOptions=androidx.compose.foundation.text.KeyboardOptions(keyboardType=androidx.compose.ui.text.input.KeyboardType.Decimal))
    if (showKeypad) TextButton({
        if (!keypad) {
            focus.clearFocus()
            keyboard?.hide()
        }
        keypad = !keypad
    }, enabled = enabled) { Text(if(keypad) "收起金额键盘" else "展开金额键盘与计算器") }
    if(showKeypad && keypad) FinanceAmountKeypad(value, currency, enabled, onChange = onChange)
    calculationError?.let { Text(it,color=MaterialTheme.colorScheme.error,style=LifeText.caption) }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun FinanceAmountKeypad(value:String,currency:String,enabled:Boolean,compact:Boolean=false,onChange:(String)->Unit) {
    var calculationError by remember {mutableStateOf<String?>(null)}
    val focus=LocalFocusManager.current
    val keyboard=LocalSoftwareKeyboardController.current
    val haptic=androidx.compose.ui.platform.LocalHapticFeedback.current
    val colors=com.qq.closie.ui.lifeos.theme.LocalLifeOsColors.current
    val density=androidx.compose.ui.platform.LocalDensity.current
    if(WindowInsets.ime.getBottom(density)>0) {
        TextButton({focus.clearFocus();keyboard?.hide()},enabled=enabled){Text("返回数字键盘")}
        return
    }
    fun press(key:String) {
        focus.clearFocus();keyboard?.hide();calculationError=null
        haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
        when(key) {
            "＝"->runCatching {FinanceCalculator.evaluate(value,currency)}.onSuccess {onChange(FinanceMoney.input(it,currency))}.onFailure {calculationError=it.message}
            "清空"->onChange("")
            "⌫"->onChange(value.dropLast(1))
            else->onChange(value+if(key=="−") "-" else key)
        }
    }
    Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        listOf(listOf("1","2","3","⌫"),listOf("4","5","6","+"),listOf("7","8","9","−"),listOf(".","0","＝","清空")).forEach {keys->
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                keys.forEachIndexed {column,key->
                    val source=remember {androidx.compose.foundation.interaction.MutableInteractionSource()}
                    val pressed by source.collectIsPressedAsState()
                    val scale by androidx.compose.animation.core.animateFloatAsState(if(pressed) .95f else 1f,LedgerMotion.SpringSnappy,label="calculator-key")
                    Box(Modifier.weight(1f).height(48.dp).graphicsLayer {scaleX=scale;scaleY=scale}
                        .background(if(column==3 || key=="＝") colors.paperTertiary else colors.paperSecondary,androidx.compose.foundation.shape.RoundedCornerShape(14.dp))
                        .combinedClickable(enabled=enabled,interactionSource=source,indication=androidx.compose.foundation.LocalIndication.current,
                            onClick={press(key)},onLongClick=if(compact && key in listOf("+","−")) {{press(if(key=="+") "×" else "÷")}} else null),
                        contentAlignment=androidx.compose.ui.Alignment.Center) {
                        Text(key,style=LedgerText.body,color=if(column==3 || key=="＝") colors.accentDeep else colors.ink)
                    }
                }
            }
        }
        if(!compact) Row {TextButton({press("×")},enabled=enabled){Text("×")};TextButton({press("÷")},enabled=enabled){Text("÷")}}
    }
    calculationError?.let {Text(it,color=MaterialTheme.colorScheme.error,style=LedgerText.caption)}
}
