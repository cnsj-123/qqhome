package com.qq.closie.ui.lifeos.finance

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import com.qq.closie.life.finance.*
import com.qq.closie.ui.lifeos.theme.*

@Composable
internal fun FinanceAccountSheet(state: FinanceUiState, account: FinanceAccountEntity?, vm: FinanceViewModel, onClose: () -> Unit) {
    val colors = LocalLifeOsColors.current
    var name by rememberSaveable(account?.id) { mutableStateOf(account?.name.orEmpty()) }
    var kindName by rememberSaveable(account?.id) { mutableStateOf(account?.kind?.name ?: FinanceAccountKind.BANK_CARD.name) }
    var balance by rememberSaveable(account?.id) { mutableStateOf(account?.let { FinanceMoney.input(it.openingBalanceMinor, it.currencyCode) } ?: "0.00") }
    var kindsOpen by remember { mutableStateOf(false) }
    var currenciesOpen by remember { mutableStateOf(false) }
    var currency by rememberSaveable(account?.id) { mutableStateOf(account?.currencyCode ?: "CNY") }
    // Includes voided entries, matching the repository's accountEntryCount guard.
    val currencyLocked = account != null && state.snapshot.entries.any { it.accountId == account.id }
    var confirmArchive by remember { mutableStateOf(false) }
    val kind = FinanceAccountKind.valueOf(kindName)
    FinanceReceipt(if (account == null) "建立真实账户" else "编辑账户", state.busy, state.error, onClose, actions = {
        Button(onClick = { vm.saveAccount(account?.id, FinanceAccountDraft(name, kind, balance, currency), onClose) }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
            Text(if (state.busy) "正在保存……" else "保存账户")
        }
    }) {
        OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("账户名称") }, singleLine = true, enabled = !state.busy)
        Box {
            TextButton(onClick = { kindsOpen = true }, enabled = !state.busy) { Text("账户类型 · ${kind.label}") }
            DropdownMenu(kindsOpen, onDismissRequest = { kindsOpen = false }) {
                FinanceAccountKind.entries.forEach { choice -> DropdownMenuItem(text = { Text(choice.label) },
                    onClick = { kindName = choice.name; kindsOpen = false }) }
            }
        }
        TextButton({ currenciesOpen = true }, enabled = !state.busy && !currencyLocked) { Text("币种 · $currency${if (currencyLocked) " · 已锁定" else " · 选择"}") }
        Text(if (currencyLocked) "此账户已有流水，币种不可更改。" else "不同币种分别统计；转账仅支持同币种账户。", style = LifeText.caption, color = colors.muted)
        Text("$currency 金额最多 ${FinanceMoney.fractionDigits(currency)} 位小数", style = LifeText.caption)
        OutlinedTextField(balance, { balance = it }, Modifier.fillMaxWidth(), label = { Text("期初余额") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text), singleLine = true, enabled = !state.busy)
        account?.let { Text("余额起点 · ${FinanceTime.anchorLabel(it.balanceAnchorAt)}", style = LifeText.caption, color = colors.muted) }
        Text("期初余额以显示的余额起点为准。当前余额仅计入该时刻及之后发生的有效流水；更早的补记仍保留在历史与统计中。", style = LifeText.caption, color = colors.muted)
        if (account?.archivedAt != null) TextButton({ vm.unarchiveAccount(account.id, onClose) }, enabled = !state.busy) { Text("恢复使用此账户") }
        else if (account != null) TextButton(onClick = { confirmArchive = true }, enabled = !state.busy) { Text("归档账户") }
    }
    if (currenciesOpen) FinanceChoiceSheet("币种", currency, FinanceCurrencies.choices, {
        currency = it
        if (balance.toBigDecimalOrNull()?.signum() == 0) balance = FinanceMoney.input(0, it)
    }, { currenciesOpen = false }, common = FinanceCurrencies.common)
    if (confirmArchive && account != null) AlertDialog(onDismissRequest = { if (!state.busy) confirmArchive = false },
        title = { Text("归档 ${account.name}？") }, text = { Text("不再用于新增流水，历史记录和余额仍然保留。") },
        confirmButton = { TextButton(onClick = { vm.archiveAccount(account.id) { confirmArchive = false; onClose() } }, enabled = !state.busy) { Text("确认归档") } },
        dismissButton = { TextButton(onClick = { confirmArchive = false }, enabled = !state.busy) { Text("取消") } })
}

@Composable
internal fun FinanceAccountsSheet(state: FinanceUiState, onAdd: () -> Unit, onEdit: (String) -> Unit, onClose: () -> Unit) {
    val colors = LocalLifeOsColors.current
    FinanceReceipt("账户管理", onClose = onClose) {
        TextButton(onClick = onAdd) { Text("建立账户") }
        state.snapshot.accounts.forEach { account ->
            TextButton(onClick = { onEdit(account.id) }, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth()) {
                    Text("${account.name} · ${account.kind.label}${if (account.archivedAt != null) " · 已归档" else ""}", style = LifeText.body)
                    Text(FinanceMoney.display(state.balances[account.id] ?: 0, account.currencyCode), style = LifeText.caption, color = colors.muted)
                }
            }
        }
        if (state.snapshot.accounts.isEmpty()) Text("还没有账户。", style = LifeText.body, color = colors.muted)
    }
}
