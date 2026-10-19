package com.qq.closie.ui.lifeos.finance

import com.qq.closie.life.ui.theme.BundledText as Text

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
    if(!state.loaded) {FinanceReceipt("正在打开账户",error=state.error,onClose=onClose){Text("读取账本快照后继续。")} ;return}
    val colors = LocalLifeOsColors.current
    val epoch=rememberSaveable(account?.id) {state.snapshot.v16?.controlRows?.singleOrNull()?.restoreEpoch ?: 0L}
    val expectedVersion=rememberSaveable(account?.id) {account?.updatedAt}
    var openingChoice by rememberSaveable(account?.id) {mutableStateOf(FinanceOpeningChoice.SAVE_TIME.name)}
    var anchorText by rememberSaveable(account?.id) {mutableStateOf(FinanceTime.input(System.currentTimeMillis()))}
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
        Button(onClick = { vm.saveAccount(account?.id, FinanceAccountDraft(name, kind, balance, currency,FinanceOpeningChoice.valueOf(openingChoice),anchorText,expectedVersion,epoch), onClose) }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
            Text(if (state.busy) "正在保存……" else "保存账户")
        }
    }) {
        OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("账户名称") }, singleLine = true, enabled = !state.busy, textStyle = com.qq.closie.life.ui.theme.LifeType.inputFor(name))
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
        if(account==null) {
            FinanceChoice("起点方式",openingChoice,listOf(FinanceOpeningChoice.SAVE_TIME.name to "保存时作为起点",FinanceOpeningChoice.CUSTOM.name to "明确选择历史起点",FinanceOpeningChoice.FROM_ZERO.name to "从零完整记录")) {
                openingChoice=it;if(it==FinanceOpeningChoice.FROM_ZERO.name) balance=FinanceMoney.input(0,currency)
            }
            if(openingChoice==FinanceOpeningChoice.CUSTOM.name) OutlinedTextField(anchorText,{anchorText=it},label={Text("已确认起点时间 · 年-月-日 时:分")},enabled=!state.busy)
            if(openingChoice==FinanceOpeningChoice.FROM_ZERO.name) Text("从零不带期初金额，之后需补齐全部历史收付；不会推断缺失流水。",style=LifeText.caption)
        }
        OutlinedTextField(balance, { balance = it }, Modifier.fillMaxWidth(), label = { Text("期初余额") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text), singleLine = true, enabled = !state.busy && account==null && openingChoice!=FinanceOpeningChoice.FROM_ZERO.name, textStyle = com.qq.closie.life.ui.theme.LifeType.inputFor(balance))
        if(account!=null) Text("修改期初金额和起点请从账户核对页进入影响预览。",style=LifeText.caption)
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
