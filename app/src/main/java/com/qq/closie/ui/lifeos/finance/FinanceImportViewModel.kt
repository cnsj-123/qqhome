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

internal enum class ImportPhase(val label: String) {
    FILE_ACCESS("文件访问"), PARSE("格式与解析"), MAPPING("账户映射"), REVIEW("记录检查"), COMMIT("确认写入")
}
internal data class ImportDiagnostic(val phase: ImportPhase = ImportPhase.FILE_ACCESS,
    val filename: String? = null, val format: String = "尚未检测", val rowCount: Int? = null,
    val technical: String? = null)

// Only fixed, actionable reader messages are safe to display: exception payloads may contain cells.
internal fun importFailure(phase: ImportPhase, error: Exception): String {
    val safe = setOf("文件为空或超过 16 MB", "账单文件不能超过 16 MB", "请选择 XLSX 或 CSV 账单",
        "账单表头与支持的 22 列格式不一致，原文件未修改", "工作簿缺少必需部分", "工作簿展开后过大",
        "请选择包含“账单列表”的工作簿", "不支持含外部定义的工作簿", "工作簿路径无效")
    return error.message?.takeIf { it in safe } ?: when (phase) {
        ImportPhase.FILE_ACCESS -> "无法读取所选文件。请确认文件已下载到本机，重新选择以授予访问权限。"
        ImportPhase.PARSE -> "文件未能解析或暂存。请检查 XLSX / CSV 格式及 22 列表头，或重新导出文件副本。"
        ImportPhase.MAPPING -> "账户映射未通过检查。请检查账户是否可用、币种及余额填写是否正确。"
        ImportPhase.REVIEW -> "检查未完成。请重新检查本批记录及重复候选。"
        ImportPhase.COMMIT -> "写入或结果读取未完成。请重新打开本批次检查状态后再确认；已写入的记录不会重复导入。"
    }
}

internal enum class ImportStage { ACCOUNTS, REVIEW, COMPLETED }

internal data class ImportUiState(
    val busy: Boolean = false, val error: String? = null, val message: String? = null,
    val history: List<FinanceImportBatchEntity> = emptyList(),
    val preview: FinanceImportPreview? = null,
    val mappings: List<FinanceAccountMapping> = emptyList(),
    val stage: ImportStage = ImportStage.ACCOUNTS, val selected: Set<String> = emptySet(),
    val splitTags: Boolean = false, val fees: Boolean = false,
    val diagnostic: ImportDiagnostic = ImportDiagnostic(),
    val policy: FinanceImportReviewPolicy = FinanceImportReviewPolicy(), val overrides: Map<String, Boolean> = emptyMap()
)

internal class FinanceImportViewModel(private val repository: FinanceImportRepository) : ViewModel() {
    private val mutable = MutableStateFlow(ImportUiState())
    val state = mutable.asStateFlow()
    private var lastUri: Uri? = null
    init { run { mutable.update { it.copy(history = repository.history()) } } }
    private fun run(block: suspend () -> Unit) {
        if (mutable.value.busy) return
        mutable.update { it.copy(busy = true, error = null, message = null, diagnostic = it.diagnostic.copy(technical = null)) }
        viewModelScope.launch {
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { mutable.update { it.copy(error = importFailure(it.diagnostic.phase, e), diagnostic = it.diagnostic.copy(technical = e.javaClass.simpleName)) } }
            finally { mutable.update { it.copy(busy = false) } }
        }
    }
    fun retryFile(context: Context) { lastUri?.let { select(context, it) } }
    fun select(context: Context, uri: Uri) = run {
        lastUri = uri
        mutable.update { it.copy(preview = null, selected = emptySet(), mappings = emptyList(), stage = ImportStage.ACCOUNTS,
            diagnostic = ImportDiagnostic()) }
        val preview = withContext(Dispatchers.IO) {
            val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            } ?: "账单.xlsx"
            mutable.update { it.copy(diagnostic = it.diagnostic.copy(filename = name)) }
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
                val signature = file.inputStream().use { input -> ByteArray(4).also { input.read(it) } }
                val format = if (signature[0] == 0x50.toByte() && signature[1] == 0x4B.toByte()) "XLSX / ZIP"
                    else if (name.endsWith(".csv", ignoreCase = true)) "CSV" else "未知格式"
                mutable.update { it.copy(diagnostic = it.diagnostic.copy(phase = ImportPhase.PARSE, format = format)) }
                repository.stage(file, name)
            } finally { file.delete() }
        }
        show(preview)
    }
    fun open(id: String) = run {
        mutable.update { it.copy(diagnostic = it.diagnostic.copy(phase = ImportPhase.REVIEW)) }
        show(repository.open(id))
    }
    private suspend fun show(preview: FinanceImportPreview) {
        val mappings = preview.rows.flatMap { listOf(it.sourceAccount, it.targetAccount) }
            .filter(String::isNotBlank).distinct().map { source -> preview.createdMappings.find { it.source == source }
                ?: FinanceAccountMapping(source, kind = FinanceImportRepository.suggestKind(source)) }
        mutable.update { it.copy(preview = preview, mappings = mappings,
            diagnostic = ImportDiagnostic(if (preview.batch.status == FinanceImportStatus.COMMITTED) ImportPhase.COMMIT else ImportPhase.MAPPING,
                preview.batch.fileName, preview.batch.fileName.substringAfterLast('.').uppercase(), preview.rows.size),
            stage = if (preview.batch.status == FinanceImportStatus.COMMITTED) ImportStage.COMPLETED else ImportStage.ACCOUNTS,
            selected = emptySet(),
            fees = false, splitTags = false, policy = FinanceImportReviewPolicy(), overrides = emptyMap(), history = repository.history()) }
    }
    fun map(mapping: FinanceAccountMapping) {
        if (!mutable.value.busy) mutable.update { it.copy(mappings = it.mappings.map { old ->
            if (old.source == mapping.source) mapping else old }, stage = ImportStage.ACCOUNTS, selected = emptySet(), diagnostic = it.diagnostic.copy(phase = ImportPhase.MAPPING)) }
    }
    /** Presentation navigation only; mapping values and staged/canonical records stay unchanged. */
    fun backToAccounts() {
        val current = mutable.value
        if (!current.busy && current.preview != null && current.preview.batch.status != FinanceImportStatus.UNDONE)
            mutable.update { it.copy(stage = ImportStage.ACCOUNTS, selected = emptySet(), error = null, message = null, diagnostic = it.diagnostic.copy(phase = ImportPhase.MAPPING)) }
    }
    fun prepare() = run {
        mutable.update { it.copy(diagnostic = it.diagnostic.copy(phase = ImportPhase.MAPPING)) }
        val current = mutable.value
        val preview = repository.preview(requireNotNull(current.preview).batch.id, current.mappings)
        mutable.update { it.copy(preview = preview, stage = ImportStage.REVIEW, diagnostic = it.diagnostic.copy(phase = ImportPhase.REVIEW, rowCount = preview.rows.size),
            policy = FinanceImportReviewPolicy(), overrides = emptyMap(), fees = false,
            selected = FinanceImportReview.selected(preview.rows, FinanceImportReviewPolicy())) }
    }
    fun choose(id: String, selected: Boolean) {
        if (!mutable.value.busy) mutable.update {
            val overrides = it.overrides + (id to selected)
            it.copy(overrides = overrides, selected = FinanceImportReview.selected(it.preview?.rows.orEmpty(), it.policy, overrides))
        }
    }
    fun tags(value: Boolean) { mutable.update { it.copy(splitTags = value) } }
    fun policy(group: FinanceImportGroup, value: Boolean) {
        if (mutable.value.busy) return
        mutable.update {
            val policy = when (group) {
                FinanceImportGroup.READY -> it.policy.copy(ready = value)
                FinanceImportGroup.REIMBURSEMENT -> it.policy.copy(reimbursement = value)
                FinanceImportGroup.REFUND -> it.policy.copy(refund = value)
                FinanceImportGroup.FEES -> it.policy.copy(fees = value)
                else -> it.policy
            }
            // A group action replaces previous individual choices only within that group.
            val ids = it.preview?.rows.orEmpty().filter { row -> group in FinanceImportReview.groups(row) }.map { row -> row.id }.toSet()
            val overrides = it.overrides.filterKeys { id -> id !in ids }
            it.copy(policy = policy, overrides = overrides, fees = policy.fees,
                selected = FinanceImportReview.selected(it.preview?.rows.orEmpty(), policy, overrides))
        }
    }
    fun commit() = run {
        val current = mutable.value
        require(current.stage == ImportStage.REVIEW)
        val id = requireNotNull(current.preview).batch.id
        mutable.update { it.copy(diagnostic = it.diagnostic.copy(phase = ImportPhase.COMMIT)) }
        val count = repository.commit(id, current.mappings, current.selected,
            current.preview.rows.filter { it.status == FinanceRowStatus.DUPLICATE && it.id in current.selected }.map { it.id }.toSet(),
            current.splitTags, current.fees)
        val refreshed = repository.open(id)
        val history = repository.history()
        mutable.update { it.copy(preview = refreshed,
            stage = if (refreshed.batch.status == FinanceImportStatus.COMMITTED) ImportStage.COMPLETED else ImportStage.REVIEW,
            selected = emptySet(),
            mappings = it.mappings.map { mapping -> refreshed.createdMappings.find { it.source == mapping.source } ?: mapping },
            history = history, message = "已导入 $count 条记录") }
    }
    fun undo(id: String) = run {
        mutable.update { it.copy(diagnostic = it.diagnostic.copy(phase = ImportPhase.COMMIT)) }
        val result = repository.undo(id)
        mutable.update { it.copy(preview = repository.open(id), stage = ImportStage.ACCOUNTS, selected = emptySet(),
            history = repository.history(), message = "已作废 ${result.voided} 条流水；保留 ${result.retained} 条后来修改或关联的流水") }
    }
}
