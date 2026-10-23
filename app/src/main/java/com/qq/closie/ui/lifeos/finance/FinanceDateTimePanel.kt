package com.qq.closie.ui.lifeos.finance

import android.widget.DatePicker
import android.widget.TimePicker
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.qq.closie.life.finance.FinanceTime
import com.qq.closie.life.ui.theme.BundledText as Text

/** Date/time controls edit a local selection; only Apply changes the receipt. */
@Composable
internal fun FinanceDateTimePanel(value:String,enabled:Boolean,onCancel:()->Unit,onApply:(String)->Unit) {
    val initial=remember(value) {java.util.Calendar.getInstance().apply {runCatching {timeInMillis=FinanceTime.parse(value)}}}
    var day by remember(value){mutableIntStateOf(initial.get(java.util.Calendar.DAY_OF_MONTH))}
    var month by remember(value){mutableIntStateOf(initial.get(java.util.Calendar.MONTH))}
    var year by remember(value){mutableIntStateOf(initial.get(java.util.Calendar.YEAR))}
    var hour by remember(value){mutableIntStateOf(initial.get(java.util.Calendar.HOUR_OF_DAY))}
    var minute by remember(value){mutableIntStateOf(initial.get(java.util.Calendar.MINUTE))}
    Column {
        AndroidView(factory={context->DatePicker(context).apply {
            init(year,month,day) {_,y,m,d->year=y;month=m;day=d}
        }},modifier=Modifier.fillMaxWidth(),update={it.isEnabled=enabled})
        AndroidView(factory={context->TimePicker(context).apply {
            setIs24HourView(true);this.hour=hour;this.minute=minute
            setOnTimeChangedListener {_,h,m->hour=h;minute=m}
        }},modifier=Modifier.fillMaxWidth(),update={it.isEnabled=enabled})
        Row {
            TextButton(onCancel,enabled=enabled){Text("取消选时")}
            TextButton({
                val selected=initial.clone() as java.util.Calendar
                selected.set(year,month,day,hour,minute,0);selected.set(java.util.Calendar.MILLISECOND,0)
                onApply(FinanceTime.input(selected.timeInMillis))
            },enabled=enabled){Text("使用此时间")}
        }
    }
}
