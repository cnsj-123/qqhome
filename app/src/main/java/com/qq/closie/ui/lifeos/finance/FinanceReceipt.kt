package com.qq.closie.ui.lifeos.finance

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import com.qq.closie.life.finance.FinanceAccountEntity
import com.qq.closie.ui.lifeos.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FinanceReceipt(title: String, busy: Boolean = false, error: String? = null,
    onClose: () -> Unit, actions: (@Composable ColumnScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit) {
    com.qq.closie.ui.lifeos.components.SuspendFunctionPageSwipe()
    val colors = LocalLifeOsColors.current
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true,
        confirmValueChange = { !busy || it != SheetValue.Hidden })
    ModalBottomSheet(onDismissRequest = { if (!busy) onClose() }, sheetState = sheet,
        containerColor = colors.card, shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp)) {
        Column(Modifier.fillMaxWidth().heightIn(max = (LocalConfiguration.current.screenHeightDp * .9f).dp)
            .imePadding().padding(horizontal = 20.dp).padding(bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = LifeText.title, color = colors.ink, modifier = Modifier.weight(1f))
                TextButton(onClick = onClose, enabled = !busy) { Text("收起") }
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
}

@Composable
internal fun AccountChoice(label: String, accounts: List<FinanceAccountEntity>, selected: String,
    enabled: Boolean, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val colors = LocalLifeOsColors.current
    Column {
        Text(label, style = LifeText.caption, color = colors.muted)
        Box {
            TextButton(onClick = { expanded = true }, enabled = enabled) {
                Text(accounts.find { it.id == selected }?.let { "${it.name}${if (it.archivedAt != null) " · 已归档" else ""} · ${it.currencyCode}" } ?: "选择账户")
            }
            if (expanded) FinanceChoiceSheet(label, selected,
                accounts.map { it.id to (it.name + if (it.archivedAt != null) " · 已归档" else "") },
                onSelect = onSelect, onClose = { expanded = false })
        }
    }
}
