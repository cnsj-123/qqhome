package com.qq.closie.ui.lifeos.finance

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.qq.closie.life.finance.*
import com.qq.closie.life.ui.theme.BundledText as Text
import com.qq.closie.life.ui.theme.LifeType
import com.qq.closie.ui.lifeos.theme.*

/** Read-only canonical projection. This composable has no repository or command reference. */
@Composable
internal fun FinanceRecordDetails(row: FinanceLedgerRow?, snapshot: FinanceSnapshot,
    onEdit: (FinanceLedgerRow) -> Unit, onClose: () -> Unit) {
    val colors = LocalLifeOsColors.current
    FinanceReceipt("账单详情", onClose = onClose, actions = {
        if (row != null) Button({ onEdit(row) }, Modifier.fillMaxWidth()) { Text("编辑账单") }
    }) {
        if (row == null) {
            Text("这条记录已失效或不可用", style = LifeText.title, color = colors.ink)
            Text("请关闭后刷新账簿。", style = LifeText.body, color = colors.inkSecondary)
        } else {
            val direction = if (row.transfer != null) "转账" else if (row.entry.direction == FinanceDirection.INFLOW) "收入" else "支出"
            Text(direction, style = LifeText.caption, color = colors.inkSecondary)
            Text(FinanceMoney.display(row.entry.amountMinor, row.currencyCode), style = LifeType.MoneyCompact, color = colors.ink)
            Text(row.account.name + (row.targetAccount?.let { " → " + it.name } ?: ""), style = LifeText.body, color = colors.ink)
            Text(FinanceTime.input(row.entry.occurredAt), style = LifeText.body, color = colors.inkSecondary)
            HorizontalDivider(color = colors.line)
            Text("分类 · " + (row.category ?: "未分类"), style = LifeText.body)
            if (row.tags.isNotEmpty()) Text("标签 · " + row.tags.joinToString("、"), style = LifeText.body)
            if (row.entry.description.isNotBlank()) Text(row.entry.description, style = LifeText.body)
            val source = snapshot.v4?.proposals.orEmpty().firstOrNull { it.canonicalId == row.id || it.canonicalId == row.entry.id }
            Text("来源 · " + (source?.discoverySourceLabel() ?: if (row.entry.importBatchId != null) "文件导入" else "未记录"), style = LifeText.caption)
            val links = snapshot.v4?.activeLinks.orEmpty().filter { it.entryId == row.entry.id }
            if (links.isNotEmpty()) {
                HorizontalDivider(color = colors.line)
                Text("关联 · " + links.map { it.role.label }.distinct().joinToString("、"), style = LifeText.body)
            }
            if (row.entry.voidedAt != null) Text("已作废", style = LifeText.body, color = colors.inkSecondary)
            Spacer(Modifier.height(8.dp))
        }
    }
}
