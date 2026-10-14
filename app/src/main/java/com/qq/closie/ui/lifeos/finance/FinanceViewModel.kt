package com.qq.closie.ui.lifeos.finance

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.qq.closie.life.finance.*
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

enum class FinancePeriod(val label: String) { ALL("全部"), MONTH("本月"), YEAR("今年"), CUSTOM("自定义") }
enum class FinanceEditorKind(val label: String) { EXPENSE("支出"), INCOME("收入"), TRANSFER("转账") }
data class FinanceFilter(val query: String = "", val period: FinancePeriod = FinancePeriod.ALL,
    val range: FinanceDateRange = FinanceDateRange(), val customLabel: String = "")
data class FinanceUiState(
    val snapshot: FinanceSnapshot = FinanceSnapshot(), val filter: FinanceFilter = FinanceFilter(),
    val ledger: List<FinanceLedgerRow> = emptyList(), val balances: Map<String, Long> = emptyMap(),
    val month: Map<String, FinanceTotals> = emptyMap(), val year: Map<String, FinanceTotals> = emptyMap(),
    val selected: Map<String, FinanceTotals> = emptyMap(),
    val categories: List<FinanceDimensionTotal> = emptyList(), val tags: List<FinanceDimensionTotal> = emptyList(),
    val loading: Boolean = true, val busy: Boolean = false, val error: String? = null
)
data class FinanceEntryDraft(val kind: FinanceEditorKind, val amount: String, val accountId: String,
    val targetAccountId: String, val occurredAt: String, val description: String,
    val category: String = "", val tags: String = "", val preservedOccurredAt: Long? = null)
data class FinanceAccountDraft(val name: String, val kind: FinanceAccountKind, val openingBalance: String)

/** Wall-clock input is explicit and strict; recordedAt remains owned by FinanceRepository. */
object FinanceTime {
    private val formatter = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm").withResolverStyle(ResolverStyle.STRICT)
    private val anchorFormatter = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss.SSS")
    fun input(time: Long): String = formatter.format(java.time.Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()))
    fun anchorLabel(time: Long): String = anchorFormatter.format(java.time.Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()))
    // A minute-only display must not truncate an untouched default or an existing canonical time.
    fun resolve(text: String, preservedTime: Long?): Long =
        preservedTime?.takeIf { input(it) == text.trim() } ?: parse(text)
    fun parse(text: String): Long = try {
        LocalDateTime.parse(text.trim(), formatter).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    } catch (_: java.time.DateTimeException) { throw IllegalArgumentException("发生时间请填写为 年-月-日 时:分，例如 2026-10-09 17:30") }
}

/** Presentation and manual commands only; screens never obtain a DAO or persist projections. */
class FinanceViewModel(private val repository: FinanceRepository) : ViewModel() {
    private val snapshot = MutableStateFlow<FinanceSnapshot?>(null)
    private val filter = MutableStateFlow(FinanceFilter())
    private val error = MutableStateFlow<String?>(null)
    private val busy = MutableStateFlow(false)

    val state: StateFlow<FinanceUiState> = combine(snapshot, filter, error, busy) { data, selection, message, saving ->
        if (data == null) FinanceUiState(filter = selection, loading = message == null, busy = saving, error = message)
        else {
            val rows = FinanceProjection.ledger(data, selection.range, selection.query)
            FinanceUiState(data, selection, rows,
                data.accounts.associate { it.id to FinanceProjection.balance(it, data.entries) },
                FinanceProjection.totals(FinanceProjection.ledger(data, FinanceDateRange.month())),
                FinanceProjection.totals(FinanceProjection.ledger(data, FinanceDateRange.year())),
                FinanceProjection.totals(rows), FinanceProjection.categoryTotals(rows), FinanceProjection.tagTotals(rows),
                loading = false, busy = saving, error = message)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FinanceUiState())

    private var reader: kotlinx.coroutines.Job? = null
    init { reload() }
    fun reload() {
        reader?.cancel()
        error.value = null
        reader = viewModelScope.launch {
            repository.observeSnapshot().catch { failure -> error.value = "账目读取未完成，原记录仍保留：${failure.message ?: "请重试"}" }
                .collect { snapshot.value = it }
        }
    }
    fun clearError() { error.value = null }
    fun search(query: String) { filter.value = filter.value.copy(query = query) }
    fun selectPeriod(period: FinancePeriod) {
        if (period == FinancePeriod.CUSTOM) return
        filter.value = filter.value.copy(period = period, customLabel = "", range = when (period) {
            FinancePeriod.MONTH -> FinanceDateRange.month()
            FinancePeriod.YEAR -> FinanceDateRange.year()
            else -> FinanceDateRange()
        })
    }
    fun customRange(start: String, end: String): Boolean = try {
        val first = LocalDate.parse(start.trim())
        val last = LocalDate.parse(end.trim())
        require(!last.isBefore(first)) { "结束日期不能早于开始日期" }
        filter.value = filter.value.copy(period = FinancePeriod.CUSTOM,
            range = FinanceDateRange.days(first, last), customLabel = "$first — $last")
        clearError()
        true
    } catch (failure: Exception) {
        error.value = if (failure is java.time.DateTimeException) "日期请填写为 年-月-日" else failure.message
        false
    }

    fun saveAccount(id: String?, draft: FinanceAccountDraft, onSaved: () -> Unit) = mutate(onSaved) {
        val currency = snapshot.value?.accounts?.find { it.id == id }?.currencyCode ?: "CNY"
        repository.saveAccount(id, draft.name, draft.kind, currency,
            FinanceMoney.parse(draft.openingBalance.ifBlank { "0" }, currency, allowNegative = true))
    }
    fun archiveAccount(id: String, onSaved: () -> Unit) = mutate(onSaved) { repository.archiveAccount(id) }
    fun saveRecord(row: FinanceLedgerRow?, draft: FinanceEntryDraft, onSaved: () -> Unit) = mutate(onSaved) {
        val account = requireNotNull(snapshot.value?.accounts?.find { it.id == draft.accountId }) { "请先建立并选择账户" }
        val amount = FinanceMoney.parse(draft.amount, account.currencyCode)
        val occurred = FinanceTime.resolve(draft.occurredAt, draft.preservedOccurredAt)
        if (draft.kind == FinanceEditorKind.TRANSFER) {
            require(row == null || row.transfer != null) { "普通流水不能变更为转账，请先作废后另记" }
            repository.saveTransfer(row?.transfer?.id, draft.accountId, draft.targetAccountId, amount, draft.description, occurred)
        } else {
            require(row?.transfer == null) { "转账需要整体编辑" }
            repository.saveEntry(row?.entry?.id, draft.accountId,
                if (draft.kind == FinanceEditorKind.INCOME) FinanceDirection.INFLOW else FinanceDirection.OUTFLOW,
                amount, draft.description, occurred, draft.category, draft.tags.split(Regex("[,，\\n]")))
        }
    }
    fun voidRecord(row: FinanceLedgerRow, onSaved: () -> Unit) = mutate(onSaved) {
        if (row.transfer != null) repository.voidTransfer(row.transfer.id) else repository.voidEntry(row.entry.id)
    }
    private fun mutate(onSaved: () -> Unit, operation: suspend () -> Unit) {
        if (busy.value || snapshot.value == null) return
        busy.value = true
        error.value = null
        viewModelScope.launch {
            try {
                operation()
                snapshot.value = repository.snapshot()
                onSaved()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error.value = failure.message ?: "保存未完成，请重试" }
            finally { busy.value = false }
        }
    }
}
