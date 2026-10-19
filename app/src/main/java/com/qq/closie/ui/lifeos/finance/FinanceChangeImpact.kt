package com.qq.closie.ui.lifeos.finance

import com.qq.closie.life.ui.theme.BundledText as Text
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import com.qq.closie.life.finance.*

@Composable
internal fun FinanceChangeImpact(preview:FinanceChangePreview) {
    Column {
        Text(preview.explanation)
        Text("影响 ${preview.entryIds.size} 笔真实资金流水、${preview.eventIds.size} 个关联事项。")
        preview.changesByCurrency.forEach { (currency,delta) ->Text("净余额变化 "+FinanceMoney.display(delta,currency)) }
        Text("确认后可在工作页查看有限恢复；后续相关修改会阻止覆盖恢复。")
    }
}
