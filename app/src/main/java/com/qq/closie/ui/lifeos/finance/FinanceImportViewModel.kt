package com.qq.closie.ui.lifeos.finance

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.qq.closie.life.finance.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File

internal data class ImportUiState(
    val busy: Boolean = false, val error: String? = null, val message: String? = null,
    val history: List<FinanceImportBatchEntity> = emptyList(),
    val preview: FinanceImportPreview? = null,
    val mappings: List<FinanceAccountMapping> = emptyList(),
    val reviewed: Boolean = false, val selected: Set<String> = emptySet(),
    val splitTags: Boolean = false, val fees: Boolean = false
)

internal class FinanceImportViewModel(private val repository: FinanceImportRepository) : ViewModel() {
    private val mutable = MutableStateFlow(ImportUiState())
    val state = mutable.asStateFlow()
    init { run { mutable.update { it.copy(history = repository.history()) } } }
    private fun run(block: suspend () -> Unit) {
        if (mutable.value.busy) return
        mutable.update { it.copy(busy = true, error = null, message = null) }
        viewModelScope.launch {
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { mutable.update { it.copy(error = e.message ?: "操作失败，请重试") } }
            finally { mutable.update { it.copy(busy = false) } }
        }
    }
    fun select(context: Context, uri: Uri) = run {
        val preview = withContext(Dispatchers.IO) {
            val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            } ?: "账单.xlsx"
            val file = File.createTempFile("finance-import-", ".tmp", context.cacheDir)
            try {
                requireNotNull(context.contentResolver.openInputStream(uri)).use { input ->
                    file.outputStream().use { output ->
                        val buffer = ByteArray(8192)
                        var size = 0L
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            size += count
                            require(size <= 16L * 1024 * 1024) { "账单文件不能超过 16 MB" }
                            output.write(buffer, 0, count)
                        }
                    }
                }
                repository.stage(file, name)
            } finally { file.delete() }
        }
        show(preview)
    }
    fun open(id: String) = run { show(repository.open(id)) }
    private suspend fun show(preview: FinanceImportPreview) {
        val mappings = preview.rows.flatMap { listOf(it.sourceAccount, it.targetAccount) }
            .filter(String::isNotBlank).distinct().map { FinanceAccountMapping(it, kind = FinanceImportRepository.suggestKind(it)) }
        mutable.update { it.copy(preview = preview, mappings = mappings, reviewed = false, selected = emptySet(),
            fees = false, splitTags = false, history = repository.history()) }
    }
    fun map(mapping: FinanceAccountMapping) {
        if (!mutable.value.busy) mutable.update { it.copy(mappings = it.mappings.map { old ->
            if (old.source == mapping.source) mapping else old }, reviewed = false, selected = emptySet()) }
    }
    fun prepare() = run {
        val current = mutable.value
        val preview = repository.preview(requireNotNull(current.preview).batch.id, current.mappings)
        mutable.update { it.copy(preview = preview, reviewed = true,
            selected = preview.rows.filter { row -> row.status == FinanceRowStatus.READY && row.canonicalId == null }.map { row -> row.id }.toSet()) }
    }
    fun choose(id: String, selected: Boolean) {
        if (!mutable.value.busy) mutable.update { it.copy(selected = if (selected) it.selected + id else it.selected - id) }
    }
    fun tags(value: Boolean) { mutable.update { it.copy(splitTags = value) } }
    fun fees(value: Boolean) { mutable.update { it.copy(fees = value) } }
    fun commit() = run {
        val current = mutable.value
        require(current.reviewed)
        val id = requireNotNull(current.preview).batch.id
        val count = repository.commit(id, current.mappings, current.selected,
            current.preview.rows.filter { it.status == FinanceRowStatus.DUPLICATE && it.id in current.selected }.map { it.id }.toSet(),
            current.splitTags, current.fees)
        mutable.update { it.copy(preview = repository.open(id), selected = emptySet(),
            history = repository.history(), message = "已导入 $count 条记录") }
    }
    fun undo(id: String) = run {
        val result = repository.undo(id)
        mutable.update { it.copy(preview = repository.open(id), reviewed = false, selected = emptySet(),
            history = repository.history(), message = "已作废 ${result.voided} 条流水；保留 ${result.retained} 条后来修改或关联的流水") }
    }
}

