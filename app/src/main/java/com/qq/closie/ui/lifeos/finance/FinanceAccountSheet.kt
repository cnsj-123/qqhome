package com.qq.closie.ui.lifeos.finance

import com.qq.closie.life.ui.theme.BundledText as Text

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.saveable.rememberSaveable
import com.qq.closie.life.finance.*
import com.qq.closie.ui.lifeos.theme.*

@Composable
internal fun FinanceAccountSheet(state: FinanceUiState, account: FinanceAccountEntity?, vm: FinanceViewModel, onClose: () -> Unit) {
    if(!state.loaded) {FinanceReceipt("正在打开账户",error=state.error,onClose=onClose){Text("读取账本快照后继续。")} ;return}
    val colors = LocalLifeOsColors.current
    val epoch=rememberSaveable(account?.id) {state.snapshot.v16?.controlRows?.singleOrNull()?.restoreEpoch ?: 0L}
    val expectedVersion by rememberSaveable(account?.id) {mutableStateOf<Long?>(account?.updatedAt)}
    var openingChoice by rememberSaveable(account?.id) {mutableStateOf(FinanceOpeningChoice.SAVE_TIME.name)}
    var anchorText by rememberSaveable(account?.id) {mutableStateOf(FinanceTime.input(System.currentTimeMillis()))}
    var name by rememberSaveable(account?.id) { mutableStateOf(account?.name.orEmpty()) }
    var kindName by rememberSaveable(account?.id) { mutableStateOf(account?.kind?.name ?: FinanceAccountKind.BANK_CARD.name) }
    var balance by rememberSaveable(account?.id) { mutableStateOf(account?.let { FinanceMoney.input(it.openingBalanceMinor, it.currencyCode) } ?: "0.00") }
    var currenciesOpen by remember { mutableStateOf(false) }
    var currency by rememberSaveable(account?.id) { mutableStateOf(account?.currencyCode ?: "CNY") }
    var includedInNet by rememberSaveable(account?.id) { mutableStateOf(true) }
    var anchorOptions by rememberSaveable(account?.id) { mutableStateOf(false) }
    // Includes voided entries, matching the repository's accountEntryCount guard.
    val currencyLocked = account != null && state.snapshot.entries.any { it.accountId == account.id }
    var confirmArchive by remember { mutableStateOf(false) }
    val kind = FinanceAccountKind.valueOf(kindName)
    FinanceAccountPaperForm(if (account == null) "新建账户" else "编辑账户", state.busy, state.error, onClose, actions = {
        Button(onClick = { vm.saveAccount(account?.id, FinanceAccountDraft(name, kind, balance, currency,FinanceOpeningChoice.valueOf(openingChoice),anchorText,expectedVersion,epoch,includedInNet), onClose) }, enabled = !state.busy,
            shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().heightIn(min = 42.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)) {
            Text(if (state.busy) "正在保存……" else "保存账户", style = LedgerText.body.copy(fontSize = 14.sp, lineHeight = 17.sp))
        }
    }) {
        FinanceAccountInputRow("名称", "账户名称", name, { name = it }, enabled = !state.busy,
            placeholder = "例如：建设银行储蓄卡", divider = true)
        FinanceAccountTypes(kind, name, !state.busy) { value, template ->
            kindName = value.name
            if (template != null) name = template
        }
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("币种", Modifier.weight(1f), style = LedgerText.body.copy(fontSize = 13.sp), color = colors.inkSecondary)
            TextButton({ currenciesOpen = true }, enabled = !state.busy && !currencyLocked, contentPadding = PaddingValues(0.dp)) {
                Text((if (currency == "CNY") "人民币 " else "") + currency + if (currencyLocked) " · 已锁定" else "⌄",
                    style = LedgerText.body.copy(fontSize = 13.sp), color = colors.ink)
            }
        }
        FinanceAccountInputRow("期初余额", value = balance, onValue = { balance = it },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
            enabled = !state.busy && account == null && openingChoice != FinanceOpeningChoice.FROM_ZERO.name)
        if(account==null) {
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("起算日期", Modifier.weight(1f), style = LedgerText.body.copy(fontSize = 13.sp), color = colors.inkSecondary)
                TextButton({ anchorOptions = !anchorOptions }, enabled = !state.busy, contentPadding = PaddingValues(0.dp)) {
                    Text(when (FinanceOpeningChoice.valueOf(openingChoice)) {
                        FinanceOpeningChoice.SAVE_TIME -> "保存时作为起点"
                        FinanceOpeningChoice.CUSTOM -> anchorText
                        FinanceOpeningChoice.FROM_ZERO -> "从零完整记录"
                    } + "⌄", style = LedgerText.body.copy(fontSize = 13.sp), color = colors.ink)
                }
            }
            if (anchorOptions) {
                FinanceChoice("起点方式",openingChoice,listOf(FinanceOpeningChoice.SAVE_TIME.name to "保存时作为起点",FinanceOpeningChoice.CUSTOM.name to "明确选择历史起点",FinanceOpeningChoice.FROM_ZERO.name to "从零完整记录")) {
                    openingChoice=it;if(it==FinanceOpeningChoice.FROM_ZERO.name) balance=FinanceMoney.input(0,currency)
                }
                if(openingChoice==FinanceOpeningChoice.CUSTOM.name) FinanceAccountInputRow("起点时间", "已确认起点时间 · 年-月-日 时:分", anchorText, {anchorText=it}, enabled=!state.busy)
                Text("期初余额以所选起点为准；更早补记仍保留在历史和统计中，不改变起点后的余额。", style = LifeText.caption, color = colors.inkSecondary)
                if(openingChoice==FinanceOpeningChoice.FROM_ZERO.name) Text("从零不带期初金额，之后需补齐全部历史收付；不会推断缺失流水。",style=LifeText.caption)
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("计入账簿净额", Modifier.weight(1f), style = LedgerText.body.copy(fontSize = 13.sp), color = colors.inkSecondary)
                Switch(includedInNet, { includedInNet = it }, enabled = !state.busy)
            }
        }
        if (currencyLocked) Text("此账户已有流水，币种不可更改。", style = LifeText.caption, color = colors.inkSecondary)
        if(account!=null) Text("修改期初金额和起点请从账户核对页进入影响预览。",style=LifeText.caption)
        account?.let { Text("余额起点 · ${FinanceTime.anchorLabel(it.balanceAnchorAt)}", style = LifeText.caption, color = colors.muted) }
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
