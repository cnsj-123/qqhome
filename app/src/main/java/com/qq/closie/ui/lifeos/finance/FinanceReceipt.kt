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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.paneTitle
import com.qq.closie.life.finance.FinanceAccountEntity
import com.qq.closie.ui.lifeos.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FinanceReceipt(title: String, busy: Boolean = false, error: String? = null,
    onClose: () -> Unit, actions: (@Composable ColumnScope.() -> Unit)? = null,
    origin:com.qq.closie.ui.lifeos.components.LifeSurfaceOrigin?=null,
    formal: Boolean = false,
    formalStatus: String? = null,
    manualPaper: Boolean = false,
    content: @Composable ColumnScope.() -> Unit) {
    com.qq.closie.ui.lifeos.components.SuspendFunctionPageSwipe()
    val colors = LocalLifeOsColors.current
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true,
        confirmValueChange = { !busy || it != SheetValue.Hidden })
    val dragHandle: (@Composable () -> Unit)? = if (manualPaper) null else { { BottomSheetDefaults.DragHandle() } }
    val contents:@Composable (()->Unit)->Unit = { close ->
        Column(Modifier.testTag("finance-receipt").semantics { paneTitle = title }.fillMaxWidth()
            .heightIn(min = if (formal) minOf(566.dp, (LocalConfiguration.current.screenHeightDp * .8f).dp) else 0.dp,
                max = (LocalConfiguration.current.screenHeightDp * .9f).dp)
            .imePadding().padding(horizontal = if (formal) 22.dp else 20.dp)
            .padding(top = if (formal) 32.dp else 0.dp, bottom = if (formal) 56.dp else 12.dp),
            verticalArrangement = Arrangement.spacedBy(if (formal) 0.dp else 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (formal) {
                    Surface(shape = RoundedCornerShape(5.dp), color = androidx.compose.ui.graphics.Color.Transparent,
                        border = BorderStroke(1.dp, colors.secondary.copy(alpha = .45f))) {
                        Text(formalStatus ?: title, Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                            style = LedgerText.body.copy(fontSize = 10.sp, lineHeight = 12.sp, letterSpacing = 0.sp), color = colors.accentDeep)
                    }
                    Spacer(Modifier.weight(1f))
                } else Text(title, style = LifeText.title, color = colors.ink, modifier = Modifier.weight(1f))
                TextButton(onClick = close, enabled = !busy, contentPadding = PaddingValues(0.dp)) {
                    Text(if (formal) "关闭 ✕" else "收起", style = if (formal) LedgerText.body.copy(fontSize = 12.sp, lineHeight = 20.sp, letterSpacing = 0.sp) else LifeText.body,
                        color = colors.inkSecondary)
                }
            }
            if (!formal) HorizontalDivider(color = colors.line)
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(if (formal) 0.dp else 12.dp)) { content() }
            error?.let { Text(it, style = LifeText.caption, color = MaterialTheme.colorScheme.error) }
            if (actions != null) {
                if (formal) Spacer(Modifier.height(34.dp)) else HorizontalDivider(color = colors.line)
                actions()
            }
        }
    }
    if (origin != null || formal) com.qq.closie.ui.lifeos.components.LifeOriginDialog(
        origin, { if (!busy) onClose() }, widthFraction = if (formal) .87f else .92f, compactHeight = true,
        paperShape = if (formal) FormalReceiptShape else null,
        // The torn receipt is warm stock in source node 1931:23203; dark palettes keep their readable surface.
        paperColor = if (formal && !colors.isDark) androidx.compose.ui.graphics.Color(0xFFFFFCF8) else null,
        // 1931:23201 combines 0.2 node opacity with a 0.8 fill: 16% violet, without platform dim.
        scrimColor = if (formal && !colors.isDark) androidx.compose.ui.graphics.Color(0xFF84758F) else null,
        dismissEnabled = !busy
    ) { close -> contents(close) }
    else ModalBottomSheet(onDismissRequest = { if (!busy) onClose() }, sheetState = sheet,
        containerColor = colors.card,
        shape = if (manualPaper) ManualReceiptShape else RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp),
        dragHandle = dragHandle) {contents(onClose)}
}

internal object ManualReceiptShape : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val depth = with(density) { 8.dp.toPx() }.coerceAtMost(size.height / 4)
        return Outline.Generic(Path().apply {
            moveTo(0f, depth * .7f)
            for (tooth in 1..22) {
                val edge = when (tooth % 5) { 0 -> .3f; 1 -> .9f; 2 -> .1f; 3 -> .6f; else -> .4f }
                lineTo(size.width * tooth / 22, depth * edge)
            }
            lineTo(size.width, size.height)
            lineTo(0f, size.height)
            close()
        })
    }
}

internal object FormalReceiptShape : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val corner = with(density) { 12.dp.toPx() }.coerceAtMost(minOf(size.width / 2, size.height / 2))
        val edge = with(density) { 4.dp.toPx() }.coerceAtMost(size.height / 4)
        return Outline.Generic(Path().apply {
            moveTo(0f, corner)
            quadraticBezierTo(0f, 0f, corner, 0f)
            lineTo(size.width - corner, 0f)
            quadraticBezierTo(size.width, 0f, size.width, corner)
            lineTo(size.width, size.height - edge / 2)
            for (tooth in 23 downTo 0) {
                val offset = when (tooth % 4) {
                    0 -> .55f
                    1 -> .1f
                    2 -> .75f
                    else -> .3f
                }
                lineTo(size.width * tooth / 24, size.height - edge * offset)
            }
            close()
        })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AccountChoice(label: String, accounts: List<FinanceAccountEntity>, selected: String,
    enabled: Boolean, recentIds: List<String> = emptyList(), compact:Boolean=false, onSelect: (String) -> Unit) {
    var expanded by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    var query by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    Column {
        TextButton({ expanded = !expanded }, enabled = enabled,modifier=if(compact) Modifier.height(32.dp) else Modifier,
            contentPadding=if(compact) PaddingValues(horizontal=9.dp,vertical=3.dp) else ButtonDefaults.TextButtonContentPadding,
            border=if(compact) androidx.compose.foundation.BorderStroke(1.dp,colorsForAccountChoice()) else null) {
            Text((if(compact && label=="账户") "" else label+" · ")+(accounts.find {it.id==selected}?.let {if(compact) it.name else it.name+" · "+it.currencyCode} ?: "选择账户"),style=if(compact) LedgerText.caption else LifeText.body)
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

@Composable
private fun colorsForAccountChoice() = LocalLifeOsColors.current.line
