package com.qq.closie.ui.lifeos.finance

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qq.closie.R
import com.qq.closie.life.finance.FinanceAccountKind
import com.qq.closie.life.ui.theme.BundledText as Text
import com.qq.closie.ui.lifeos.theme.*

/** The account editor is a plain bottom sheet, not a sheet torn from the ledger. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FinanceAccountPaperForm(title: String, busy: Boolean, error: String?, onClose: () -> Unit,
    actions: @Composable ColumnScope.() -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val colors = LocalLifeOsColors.current
    com.qq.closie.ui.lifeos.components.SuspendFunctionPageSwipe()
    ModalBottomSheet(onDismissRequest = { if (!busy) onClose() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true,
            confirmValueChange = { !busy || it != SheetValue.Hidden }),
        containerColor = colors.paper, shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp), dragHandle = null) {
        Column(Modifier.fillMaxWidth().height((LocalConfiguration.current.screenHeightDp * .8f).dp)
            .imePadding().padding(horizontal = 31.dp).padding(bottom = 24.dp)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 64.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClose, enabled = !busy, contentPadding = PaddingValues(0.dp),
                    modifier = Modifier.offset(x = (-12).dp).size(40.dp).semantics { contentDescription = "返回账户列表" }) {
                    Icon(painterResource(R.drawable.lifeos_finance_account_back), null, Modifier.size(24.dp), tint = colors.inkSecondary)
                }
                Text(title, style = LedgerText.title.copy(fontSize = 20.sp, lineHeight = 25.sp), color = colors.ink)
            }
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), content = content)
            error?.let { Text(it, Modifier.padding(top = 8.dp), style = LifeText.caption, color = MaterialTheme.colorScheme.error) }
            Spacer(Modifier.height(20.dp))
            actions()
        }
    }
}

@Composable
internal fun FinanceAccountInputRow(label: String, semanticLabel: String = label, value: String,
    onValue: (String) -> Unit, enabled: Boolean = true, placeholder: String = "",
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default, divider: Boolean = false) {
    val colors = LocalLifeOsColors.current
    Column {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(label, Modifier.widthIn(min = 58.dp), style = LedgerText.body.copy(fontSize = 13.sp, lineHeight = 16.sp), color = colors.inkSecondary)
            BasicTextField(value, onValue, enabled = enabled, singleLine = true, keyboardOptions = keyboardOptions,
                textStyle = com.qq.closie.life.ui.theme.LifeType.inputFor(value).copy(fontSize = 13.sp, lineHeight = 16.sp, textAlign = TextAlign.End),
                cursorBrush = SolidColor(colors.accentDeep),
                modifier = Modifier.weight(1f).heightIn(min = 48.dp).semantics { contentDescription = semanticLabel },
                decorationBox = { field -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                    if (value.isEmpty()) Text(placeholder, style = LedgerText.body.copy(fontSize = 13.sp, lineHeight = 16.sp), color = colors.inkSecondary)
                    field()
                } })
        }
        if (divider) HorizontalDivider(color = colors.line)
    }
}

/** A template writes the real canonical account name/kind; there is no detached institution state. */
private data class AccountNameTemplate(val name: String, val kind: FinanceAccountKind)

@Composable
internal fun FinanceAccountTypes(kind: FinanceAccountKind, name: String, enabled: Boolean,
    change: (FinanceAccountKind, String?) -> Unit) {
    val colors = LocalLifeOsColors.current
    val network = kind in setOf(FinanceAccountKind.WECHAT, FinanceAccountKind.ALIPAY)
    val types = listOf(FinanceAccountKind.CASH to "现金", FinanceAccountKind.BANK_CARD to "银行卡",
        FinanceAccountKind.WECHAT to "网络支付", FinanceAccountKind.CREDIT to "信用账户",
        FinanceAccountKind.OTHER to "其他", FinanceAccountKind.INVESTMENT to "投资")
    Text("类型 · 先选大类", Modifier.padding(top = 20.dp, bottom = 10.dp),
        style = LedgerText.caption.copy(fontSize = 12.sp), color = colors.inkSecondary)
    types.chunked(4).forEach { row ->
        Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            row.forEach { (value, label) ->
                val selected = if (value == FinanceAccountKind.WECHAT) network else kind == value
                Surface(Modifier.clickable(enabled = enabled) { change(value, null) },
                    shape = RoundedCornerShape(9.dp), color = if (selected) colors.soft.copy(alpha = .28f) else colors.paper,
                    border = BorderStroke(1.dp, if (selected) colors.accent.copy(alpha = .55f) else colors.line)) {
                    Box(Modifier.padding(horizontal = 12.dp).heightIn(min = 30.dp), contentAlignment = Alignment.Center) {
                        Text(label, style = LedgerText.body.copy(fontSize = 12.sp, lineHeight = 15.sp),
                            color = if (selected) colors.accentDeep else colors.inkSecondary)
                    }
                }
            }
        }
    }
    val templates = when {
        kind == FinanceAccountKind.BANK_CARD -> listOf(AccountNameTemplate("建设银行", kind), AccountNameTemplate("招商银行", kind))
        network -> listOf(AccountNameTemplate("微信零钱", FinanceAccountKind.WECHAT), AccountNameTemplate("支付宝", FinanceAccountKind.ALIPAY))
        kind == FinanceAccountKind.CREDIT -> listOf(AccountNameTemplate("花呗", kind), AccountNameTemplate("京东白条", kind))
        else -> emptyList()
    }
    if (templates.isNotEmpty()) {
        Text("再选具体机构", Modifier.padding(top = 14.dp, bottom = 10.dp),
            style = LedgerText.caption.copy(fontSize = 12.sp), color = colors.inkSecondary)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            templates.forEach { template ->
                val selected = name == template.name && kind == template.kind
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Surface(Modifier.size(48.dp).clickable(enabled = enabled) { change(template.kind, template.name) }
                        .semantics { contentDescription = template.name },
                        shape = RoundedCornerShape(10.dp),
                        color = if (selected) colors.soft.copy(alpha = .28f) else colors.paper,
                        border = if (selected) BorderStroke(1.2.dp, colors.accentDeep) else null) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(painterResource(R.drawable.lifeos_finance_account_template), null, Modifier.size(22.dp), tint = colors.accentDeep)
                        }
                    }
                    Text(template.name, style = LedgerText.body.copy(fontSize = 10.sp, lineHeight = 15.sp), color = colors.inkSecondary)
                }
            }
        }
        templates.firstOrNull { it.name == name && it.kind == kind }?.let {
            Text("已选：${it.name} · ${it.kind.label}", Modifier.fillMaxWidth().padding(top = 6.dp),
                textAlign = TextAlign.End, style = LedgerText.caption.copy(fontSize = 12.sp), color = colors.inkSecondary)
        }
    }
    Spacer(Modifier.height(14.dp))
}
