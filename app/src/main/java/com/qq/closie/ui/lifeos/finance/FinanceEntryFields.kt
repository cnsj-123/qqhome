package com.qq.closie.ui.lifeos.finance

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qq.closie.life.ui.theme.BundledText as Text
import com.qq.closie.ui.lifeos.theme.LocalLifeOsColors

@Composable
internal fun FinanceEntryTabs(kind: FinanceEditorKind, enabled: (FinanceEditorKind) -> Boolean, select: (FinanceEditorKind) -> Unit) {
    val colors=LocalLifeOsColors.current
    Row(Modifier.fillMaxWidth().height(32.dp).background(colors.paperTertiary,RoundedCornerShape(12.dp)).padding(3.dp)) {
        FinanceEditorKind.entries.forEach { tab ->
            Box(Modifier.weight(1f).fillMaxHeight().background(if(kind==tab) colors.card else androidx.compose.ui.graphics.Color.Transparent,RoundedCornerShape(9.dp))
                .semantics {selected=kind==tab}.clickable(enabled=enabled(tab)){select(tab)},contentAlignment=Alignment.Center) {
                Text(tab.label,style=LedgerText.body,color=if(kind==tab) colors.ink else colors.inkSecondary)
            }
        }
    }
}

@Composable
internal fun FinanceCompactEntryField(value:String,onChange:(String)->Unit,label:String,enabled:Boolean,
    modifier:Modifier=Modifier,amount:Boolean=false) {
    val colors=LocalLifeOsColors.current
    BasicTextField(value,onChange,modifier.heightIn(min=36.dp).semantics {contentDescription=label},
        enabled=enabled,singleLine=true,cursorBrush=SolidColor(colors.accentDeep),
        textStyle=(if(amount) LedgerText.amount.copy(fontSize=20.sp,lineHeight=28.sp) else LedgerText.caption).copy(color=colors.ink,
            textAlign=if(amount) androidx.compose.ui.text.style.TextAlign.End else androidx.compose.ui.text.style.TextAlign.Start),
        keyboardOptions=KeyboardOptions(keyboardType=if(amount) KeyboardType.Decimal else KeyboardType.Text),
        decorationBox={field->Box(contentAlignment=if(amount) Alignment.CenterEnd else Alignment.CenterStart) {
            if(value.isEmpty()) Text(if(amount) "0.00" else "备注（选填）",style=if(amount) LedgerText.amount.copy(fontSize=20.sp) else LedgerText.caption,color=colors.inkSecondary)
            field()
        }})
}
