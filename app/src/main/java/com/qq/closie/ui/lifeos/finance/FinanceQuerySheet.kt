package com.qq.closie.ui.lifeos.finance

import android.os.Bundle
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.qq.closie.life.finance.*
import com.qq.closie.life.ui.theme.BundledText as Text
import com.qq.closie.ui.lifeos.theme.LifeText
import com.qq.closie.ui.lifeos.theme.LocalLifeOsColors
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

private val queryFilterSaver = Saver<FinanceFilter, Bundle>(
    save = { FinanceFilterState.encode(it) },
    restore = { FinanceFilterState.decode(it) }
)

private data class QueryPreviewLoad(val snapshot:FinanceSnapshot,val spec:FinanceQuerySpec,val dateBasis:FinanceLedgerDateBasis,
    val result:Result<FinanceQueryPreview>)

/** A disposable filter draft over the module's existing query state. Apply publishes it once. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FinanceQuerySheet(
    state: FinanceUiState,
    initialFilter: FinanceFilter,
    calendarMode: Boolean,
    calendarMonth: YearMonth,
    showCalendarControls: Boolean,
    onApply: (FinanceFilter, Boolean) -> Unit,
    onSaveView: (String, FinanceQuerySpec, () -> Unit) -> Unit,
    onClose: () -> Unit
) {
    val colors = LocalLifeOsColors.current
    var draft by rememberSaveable(stateSaver = queryFilterSaver) { mutableStateOf(initialFilter) }
    var useCalendar by rememberSaveable { mutableStateOf(calendarMode) }
    var chooseMonth by rememberSaveable { mutableStateOf(false) }
    var chooseDates by rememberSaveable { mutableStateOf(false) }
    var dimension by rememberSaveable { mutableStateOf<FinanceFilterDimension?>(null) }
    var amountOpen by rememberSaveable { mutableStateOf(false) }
    var extraOptions by rememberSaveable {mutableStateOf(false)}
    var saveOpen by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    var savedMode by rememberSaveable { mutableStateOf(FinanceQueryMode.CASH_MOVEMENT) }
    var currency by rememberSaveable {
        mutableStateOf(initialFilter.currencyCode ?: state.snapshot.accounts.firstOrNull()?.currencyCode ?: "CNY")
    }
    var minimum by rememberSaveable {
        mutableStateOf(initialFilter.minimumMinor?.let { FinanceMoney.input(it, currency) }.orEmpty())
    }
    var maximum by rememberSaveable {
        mutableStateOf(initialFilter.maximumMinor?.let { FinanceMoney.input(it, currency) }.orEmpty())
    }
    var error by remember { mutableStateOf<String?>(null) }
    val epoch = rememberSaveable { state.snapshot.v16?.controlRows?.singleOrNull()?.restoreEpoch ?: 0L }
    val currentEpoch = state.snapshot.v16?.controlRows?.singleOrNull()?.restoreEpoch ?: 0L
    val validSession = epoch == currentEpoch
    val snapshot = state.snapshot

    fun prepared(): FinanceFilter {
        require(validSession) { "备份恢复后筛选会话已失效，请重新打开" }
        val selected = if (amountOpen) {
            val code = currency.trim().uppercase(java.util.Locale.ROOT)
            draft.copy(
                currencyCode = code,
                minimumMinor = minimum.takeIf(String::isNotBlank)?.let { FinanceMoney.parse(it, code, true) },
                maximumMinor = maximum.takeIf(String::isNotBlank)?.let { FinanceMoney.parse(it, code, true) }
            )
        } else draft
        require(selected.accountId == null || snapshot.accounts.any { it.id == selected.accountId }) { "所选账户已不可用，请重新选择" }
        require(selected.categoryId == null || snapshot.categories.any { it.id == selected.categoryId }) { "所选分类已不可用，请重新选择" }
        require(selected.tagId == null || snapshot.tags.any { it.id == selected.tagId }) { "所选标签已不可用，请重新选择" }
        require(selected.minimumMinor == null || selected.maximumMinor == null ||
            selected.minimumMinor <= selected.maximumMinor) { "最低金额不能高于最高金额" }
        return FinanceFilterState.validated(selected)
    }
    fun setPeriod(period: FinancePeriod) {
        error = null
        draft = draft.copy(period = period, customLabel = "", range = when (period) {
            FinancePeriod.MONTH -> FinanceDateRange.month()
            FinancePeriod.YEAR -> FinanceDateRange.year()
            else -> FinanceDateRange()
        })
        if (showCalendarControls) useCalendar = period == FinancePeriod.MONTH
    }
    val rangeLabel = remember(draft.range, draft.period) {
        val zone = ZoneId.systemDefault()
        val first = draft.range.startInclusive?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
        val last = draft.range.endExclusive?.let { Instant.ofEpochMilli(it - 1).atZone(zone).toLocalDate() }
        when {
            first == null && last == null -> "全部日期"
            first != null && last != null && draft.range == FinanceDateRange.month(first) ->
                "${first.year}.${first.monthValue.toString().padStart(2, '0')}"
            draft.period == FinancePeriod.YEAR && first != null -> "${first.year} 年"
            else -> "${first ?: "不限"} — ${last ?: "不限"}"
        }
    }
    val previewMode=if(saveOpen) savedMode else FinanceQueryMode.CASH_MOVEMENT
    val previewBasis=if(saveOpen) FinanceLedgerDateBasis.CASH_MOVEMENT else FinanceLedgerDateBasis.ECONOMIC_LEDGER
    val previewSpec=remember(snapshot,draft,amountOpen,currency,minimum,maximum,validSession,previewMode) {
        runCatching {prepared().querySpec(previewMode)}.getOrNull()
    }
    val previewData by produceState<QueryPreviewLoad?>(null,snapshot,previewSpec,previewBasis) {
        value=null
        if(previewSpec!=null) value=kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            QueryPreviewLoad(snapshot,previewSpec,previewBasis,runCatching {financeQueryPreview(snapshot,previewSpec,previewBasis)})
        }
    }
    val currentPreview=previewData?.takeIf {it.snapshot===snapshot && it.spec==previewSpec && it.dateBasis==previewBasis}?.result
    val preview=currentPreview?.getOrNull()
    val previewError=currentPreview?.exceptionOrNull()?.message
    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = colors.paper,
        // Figma 1929:20344 uses the same violet overlay sample as the formal receipt.
        scrimColor = if(colors.isDark) BottomSheetDefaults.ScrimColor else Color(0xFF84758F).copy(alpha=.16f),
        shape = ManualReceiptShape,
        dragHandle = null
    ) {
        Column(Modifier.fillMaxWidth().testTag("finance-query-sheet").heightIn(max = (LocalConfiguration.current.screenHeightDp * .73f).dp)
            .padding(horizontal = 32.dp).imePadding()) {
            Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClose, Modifier.width(40.dp).offset(x = (-8).dp)) {
                    Icon(androidx.compose.ui.res.painterResource(com.qq.closie.R.drawable.lifeos_finance_entry_back),
                        "取消查账条件", Modifier.size(24.dp))
                }
                Text("查账", Modifier.weight(1f), style = LedgerText.title, color = colors.ink)
                TextButton({
                    runCatching { prepared() }.onSuccess { saveOpen = true }.onFailure { error = it.message }
                }, enabled = state.loaded && validSession) { Text("存为视图", style = LedgerText.caption) }
            }
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth().height(40.dp),verticalAlignment=Alignment.CenterVertically) {
                    Text("关键词",style=LedgerText.caption,color=colors.inkSecondary)
                    androidx.compose.foundation.text.BasicTextField(draft.query,{draft=draft.copy(query=it.take(FinanceFilterState.MAX_QUERY));error=null},
                        Modifier.weight(1f).padding(start=16.dp).testTag("finance-query-keyword"),singleLine=true,textStyle=LedgerText.input.copy(color=colors.ink),
                        decorationBox={field->Box(contentAlignment=Alignment.CenterEnd) {if(draft.query.isEmpty()) Text("商品 / 商户 / 备注",style=LedgerText.caption,color=colors.inkSecondary);field()}})
                }
                HorizontalDivider(color=colors.line)
                QueryLabel("发生日期范围")
                LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    item { LedgerFilterChip(true, { chooseMonth = true }, label = { Text(rangeLabel, style = LifeText.caption) }) }
                    item { LedgerFilterChip(draft.period == FinancePeriod.MONTH, { setPeriod(FinancePeriod.MONTH) }, modifier = Modifier.testTag("query-period-MONTH"), label = { Text("本月", style = LifeText.caption) }) }
                    item { LedgerFilterChip(draft.period == FinancePeriod.YEAR, { setPeriod(FinancePeriod.YEAR) }, modifier = Modifier.testTag("query-period-YEAR"), label = { Text("今年", style = LifeText.caption) }) }
                    item { LedgerFilterChip(draft.period == FinancePeriod.ALL, { setPeriod(FinancePeriod.ALL) }, modifier = Modifier.testTag("query-period-ALL"), label = { Text("全部", style = LifeText.caption) }) }
                    item { LedgerFilterChip(false, { chooseDates = true }, label = { Text("自定义范围", style = LifeText.caption) }) }
                }
                if (showCalendarControls && extraOptions) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        LedgerFilterChip(useCalendar, {
                            useCalendar = true
                            val month = draft.range.startInclusive?.let {
                                YearMonth.from(Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()))
                            } ?: calendarMonth
                            draft = draft.copy(period = FinancePeriod.CUSTOM, range = FinanceDateRange.month(month.atDay(1)), customLabel = "")
                        }, label = { Text("月历与日期", style = LifeText.caption) })
                        LedgerFilterChip(!useCalendar, { useCalendar = false }, label = { Text("列表与范围", style = LifeText.caption) })
                    }
                }

                QueryLabel("金额区间")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    LedgerFilterChip(
                        !amountOpen && draft.minimumMinor == null && draft.maximumMinor == null && draft.currencyCode == null,
                        {
                            amountOpen = false
                            minimum = ""
                            maximum = ""
                            draft = draft.copy(minimumMinor = null, maximumMinor = null, currencyCode = null)
                            error = null
                        }, label = { Text("不限", style = LifeText.caption) }
                    )
                    LedgerFilterChip(amountOpen || draft.currencyCode != null, {
                        amountOpen = true
                    }, label = { Text("自定义金额与币种", style = LifeText.caption) })
                }
                if (amountOpen) {
                    FinanceChoice("币种", currency,
                        (snapshot.accounts.map { it.currencyCode } + currency).distinct().map { it to it }) {
                        currency = it
                        minimum = ""
                        maximum = ""
                        error = null
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(minimum, { minimum = it; error = null }, Modifier.weight(1f),
                            label = { Text("最低金额") }, singleLine = true)
                        OutlinedTextField(maximum, { maximum = it; error = null }, Modifier.weight(1f),
                            label = { Text("最高金额") }, singleLine = true)
                    }
                    Text("留空不限金额。负调整按所选币种的精度比较，未知金额不会当作零。",
                        style = LifeText.caption, color = colors.muted)
                } else if (draft.currencyCode != null) {
                    Text("${draft.currencyCode} · ${draft.minimumMinor?.let { FinanceMoney.input(it, draft.currencyCode!!) } ?: "不限"} — ${draft.maximumMinor?.let { FinanceMoney.input(it, draft.currencyCode!!) } ?: "不限"}",
                        style = LifeText.caption, color = colors.inkSecondary)
                }

                QueryChoiceRow("账户", draft.accountId, snapshot.accounts.map { it.id to it.name },
                    onSelect = { draft = draft.copy(accountId = it); error = null },
                    onMore = { dimension = FinanceFilterDimension.ACCOUNT })
                QueryChoiceRow("分类", draft.categoryId, snapshot.categories.map {
                    it.id to (FinanceProjection.categoryPath(snapshot.categories, it.id) ?: it.name)
                }, onSelect = { draft = draft.copy(categoryId = it); error = null },
                    onMore = { dimension = FinanceFilterDimension.CATEGORY })
                if(snapshot.tags.isNotEmpty() || draft.tagId!=null) QueryChoiceRow("标签", draft.tagId, snapshot.tags.map { it.id to it.name },
                    onSelect = { draft = draft.copy(tagId = it); error = null },
                    onMore = { dimension = FinanceFilterDimension.TAG })
                QueryChoiceRow("业务性质", draft.nature?.name, FinanceNature.entries.map { it.name to it.label },
                    onSelect = { draft = draft.copy(nature = it?.let(FinanceNature::valueOf)); error = null },
                    onMore = { dimension = FinanceFilterDimension.NATURE })
                TextButton({extraOptions=!extraOptions},contentPadding=PaddingValues(0.dp)) {Text(if(extraOptions) "收起更多条件" else "更多条件",style=LedgerText.caption)}
                if(extraOptions) Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                    TextButton({draft=draft.copy(query="");error=null}) {Text("清除搜索",style=LedgerText.caption)}
                    TextButton({draft=FinanceFilter(period=FinancePeriod.ALL,range=FinanceDateRange());useCalendar=false;amountOpen=false;minimum="";maximum="";error=null}) {Text("清除全部条件",style=LedgerText.caption)}
                }
                if (!validSession) Text("数据已恢复，请关闭后重新打开查账。", style = LifeText.caption, color = MaterialTheme.colorScheme.error)
                error?.let { Text(it, style = LifeText.caption, color = MaterialTheme.colorScheme.error) }
                state.error?.let { Text(it, style = LifeText.caption, color = MaterialTheme.colorScheme.error) }
            }
            if(!saveOpen) {
                preview?.let {FinanceQueryPreviewSummary(it)}
                previewError?.let {Text(it,style=LifeText.caption,color=MaterialTheme.colorScheme.error)}
            }
            Button({
                runCatching { prepared() }.onSuccess { onApply(it, useCalendar) }.onFailure { error = it.message ?: "请检查查询条件" }
            }, Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 20.dp).height(42.dp), enabled = state.loaded && validSession,shape=androidx.compose.foundation.shape.RoundedCornerShape(12.dp)) {
                Text("查看结果",style=LedgerText.body)
            }
        }
    }
    if (chooseMonth) {
        val selected = draft.range.startInclusive?.let {
            YearMonth.from(Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()))
        } ?: calendarMonth
        FinanceMonthPicker(selected, onSelect = {
            draft = draft.copy(period = FinancePeriod.CUSTOM, range = FinanceDateRange.month(it.atDay(1)), customLabel = "")
            chooseMonth = false
            if (showCalendarControls) useCalendar = true
        }, onClose = { chooseMonth = false }, onRange = { chooseMonth = false; chooseDates = true })
    }
    if (chooseDates) FinanceDateSheet(
        error = error,
        onApply = { first, last ->
            runCatching {
                draft = draft.copy(period = FinancePeriod.CUSTOM,
                    range = FinanceDateRange.days(LocalDate.parse(first), LocalDate.parse(last)),
                    customLabel = "$first — $last")
                if (showCalendarControls) useCalendar = false
                error = null
            }.onFailure { error = it.message }.isSuccess
        },
        onClose = { chooseDates = false },
        initial = draft.range.startInclusive?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate() } ?: LocalDate.now(),
        initialEnd = draft.range.endExclusive?.let { Instant.ofEpochMilli(it - 1).atZone(ZoneId.systemDefault()).toLocalDate() } ?: LocalDate.now(),
        transactionDays = state.allRows.filter { it.entry.id !in state.unknownCashDateIds || it.entry.id in state.economicDates }
            .map { it.localDay(state.economicDates) }.toSet()
    )
    dimension?.let { kind ->
        val options = when (kind) {
            FinanceFilterDimension.ACCOUNT -> snapshot.accounts.map { it.id to it.name }
            FinanceFilterDimension.CATEGORY -> snapshot.categories.map { it.id to (FinanceProjection.categoryPath(snapshot.categories, it.id) ?: it.name) }
            FinanceFilterDimension.TAG -> snapshot.tags.map { it.id to it.name }
            FinanceFilterDimension.NATURE -> FinanceNature.entries.map { it.name to it.label }
        }
        val selected = when (kind) {
            FinanceFilterDimension.ACCOUNT -> draft.accountId
            FinanceFilterDimension.CATEGORY -> draft.categoryId
            FinanceFilterDimension.TAG -> draft.tagId
            FinanceFilterDimension.NATURE -> draft.nature?.name
        }
        fun select(id: String?) {
            draft = when (kind) {
                FinanceFilterDimension.ACCOUNT -> draft.copy(accountId = id)
                FinanceFilterDimension.CATEGORY -> draft.copy(categoryId = id)
                FinanceFilterDimension.TAG -> draft.copy(tagId = id)
                FinanceFilterDimension.NATURE -> draft.copy(nature = id?.let(FinanceNature::valueOf))
            }
            error = null
        }
        val common = if (kind == FinanceFilterDimension.CATEGORY) snapshot.entries
            .filter { it.voidedAt == null }.mapNotNull { it.categoryId }.groupingBy { it }.eachCount()
            .entries.sortedByDescending { it.value }.take(6).map { it.key } else emptyList()
        FinanceChoiceSheet(kind.label, selected, options, { select(it) }, { dimension = null }, { select(null) }, common)
    }
    if (saveOpen) AlertDialog(
        onDismissRequest = { if (!state.busy) saveOpen = false },
        modifier = Modifier.testTag("finance-query-save-dialog"),
        title = { Text("存为查账视图") },
        text = {
            Column {
                OutlinedTextField(name, { name = it.take(80) }, Modifier.testTag("finance-query-view-name"), label = { Text("视图名称") }, singleLine = true, enabled = !state.busy)
                FinanceQueryMode.entries.forEach { mode ->
                    LedgerFilterChip(savedMode == mode, { savedMode = mode }, modifier=Modifier.testTag("finance-query-save-mode-"+mode.name), enabled = !state.busy,
                        label = { Text(if (mode == FinanceQueryMode.CASH_MOVEMENT) "真实资金流水" else "个人成本") })
                }
                Text(if(savedMode==FinanceQueryMode.CASH_MOVEMENT) "按真实收付日期查询" else "按经济日期查询本人承担",style=LifeText.caption)
                preview?.let {FinanceQueryPreviewSummary(it)}
                previewError?.let {Text(it,style=LifeText.caption,color=MaterialTheme.colorScheme.error)}
                Text("只保存查询条件。新记录会按条件进入结果。", style = LifeText.caption)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = LifeText.caption) }
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = LifeText.caption) }
            }
        },
        confirmButton = { TextButton({
            runCatching {
                val filter = prepared()
                FinanceQuerySpec(
                    version = if (filter.nature != null) 2 else 1,
                    mode = savedMode,
                    startInclusive = filter.range.startInclusive, endExclusive = filter.range.endExclusive,
                    accountIds = setOfNotNull(filter.accountId), categoryIds = setOfNotNull(filter.categoryId),
                    tagIds = setOfNotNull(filter.tagId), currencyCode = filter.currencyCode,
                    minimumMinor = filter.minimumMinor, maximumMinor = filter.maximumMinor, text = filter.query,
                    nature = filter.nature
                ).also(FinanceQuerySpec::validate)
            }.onSuccess { onSaveView(name.trim(), it) { saveOpen = false } }.onFailure { error = it.message }
        }, enabled = name.isNotBlank() && !state.busy && validSession) { Text("保存视图") } },
        dismissButton = { TextButton({ saveOpen = false }, enabled = !state.busy) { Text("取消") } }
    )
}

@Composable
private fun QueryLabel(text: String) {
    Text(text, style = LifeText.caption, color = LocalLifeOsColors.current.muted, modifier = Modifier.padding(top = 6.dp))
}

@Composable
private fun QueryChoiceRow(
    title: String,
    selected: String?,
    options: List<Pair<String, String>>,
    onSelect: (String?) -> Unit,
    onMore: () -> Unit
) {
    QueryLabel(title)
    val visible = (options.filter { it.first == selected } + options.take(3)).distinctBy { it.first }
    LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        item { LedgerFilterChip(selected == null, { onSelect(null) }, label = { Text("全部", style = LifeText.caption) }) }
        items(visible, key = { it.first }) { (id, label) ->
            LedgerFilterChip(selected == id, { onSelect(id) }, label = { Text(label, maxLines = 2, style = LifeText.caption) })
        }
        if (options.size > visible.size) item { LedgerFilterChip(false, onMore, label = { Text("更多", style = LifeText.caption) }) }
    }
}
