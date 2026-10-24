package com.qq.closie.ui.lifeos.finance

import com.qq.closie.life.ui.theme.BundledText as Text

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.qq.closie.life.finance.FinanceAccountEntity
import com.qq.closie.ui.lifeos.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FinanceReceipt(title: String, busy: Boolean = false, error: String? = null,
    onClose: () -> Unit, actions: (@Composable ColumnScope.() -> Unit)? = null,
    origin:com.qq.closie.ui.lifeos.components.LifeSurfaceOrigin?=null,
    content: @Composable ColumnScope.() -> Unit) {
    com.qq.closie.ui.lifeos.components.SuspendFunctionPageSwipe()
    val colors = LocalLifeOsColors.current
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true,
        confirmValueChange = { !busy || it != SheetValue.Hidden })
    val contents:@Composable (()->Unit)->Unit = { close ->
        Column(Modifier.testTag("finance-receipt").fillMaxWidth().heightIn(max = (LocalConfiguration.current.screenHeightDp * .9f).dp)
            .imePadding().padding(horizontal = 20.dp).padding(bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = LifeText.title, color = colors.ink, modifier = Modifier.weight(1f))
                TextButton(onClick = close, enabled = !busy) { Text("收起") }
            }
            HorizontalDivider(color = colors.line)
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) { content() }
            error?.let { Text(it, style = LifeText.caption, color = MaterialTheme.colorScheme.error) }
            if (actions != null) {
                HorizontalDivider(color = colors.line)
                actions()
            }
        }
    }
    if(origin!=null) com.qq.closie.ui.lifeos.components.LifeOriginDialog(origin,onClose,compactHeight=true) {close->contents(close)}
    else ModalBottomSheet(onDismissRequest = { if (!busy) onClose() }, sheetState = sheet,
        containerColor = colors.card, shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp)) {contents(onClose)}
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AccountChoice(label: String, accounts: List<FinanceAccountEntity>, selected: String,
    enabled: Boolean, recentIds: List<String> = emptyList(), onSelect: (String) -> Unit) {
    var expanded by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    var query by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    Column {
        TextButton({ expanded = !expanded }, enabled = enabled) {
            Text(label + " · " + (accounts.find { it.id == selected }?.let { "${it.name} · ${it.currencyCode}" } ?: "选择账户"))
        }
        if (expanded) ModalBottomSheet(onDismissRequest={expanded=false},sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)) {
            Column(Modifier.fillMaxWidth().heightIn(max=(LocalConfiguration.current.screenHeightDp*.72f).dp)
                .verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                Text("选择"+label,Modifier.weight(1f),style=LifeText.title)
                TextButton({expanded=false}) {Text("返回账单")}
            }
            val recent = recentIds.mapNotNull { id -> accounts.find { it.id == id } }
            if (recent.isNotEmpty() && query.isBlank()) {
                Text("最近使用", style = LifeText.caption)
                androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(recent.size) { index -> val account = recent[index]
                        InputChip(account.id == selected, { onSelect(account.id); expanded = false }, enabled = enabled,
                            label = { Text(account.name) })
                    }
                }
            }
            OutlinedTextField(query, { query = it }, label = { Text("搜索账户") }, singleLine = true, enabled = enabled, textStyle = com.qq.closie.life.ui.theme.LifeType.inputFor(query))
            accounts.filter { query.isBlank() || (it.name + it.kind.label).contains(query, true) }.groupBy { it.kind }.forEach { (kind, group) ->
                Text(kind.label, style = LifeText.caption)
                group.chunked(2).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEach { account ->
                            FilterChip(account.id == selected, { onSelect(account.id); expanded = false }, enabled = enabled,
                                modifier = Modifier.weight(1f), label = { Text(account.name + " · " + account.currencyCode, maxLines = 2) })
                        }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
            }
        }
    }
}
