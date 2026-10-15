package com.qq.closie.life.finance.acquisition

import com.qq.closie.life.finance.NormalizedTransactionEvidence

enum class DetailRecognition { UNSUPPORTED, NOT_A_DETAIL_PAGE, LOW_CONFIDENCE, RECOGNIZED }
data class DetailResult(val recognition: DetailRecognition, val evidence: NormalizedTransactionEvidence? = null)

/** A short-lived allowlisted field view, not a serialized page/tree. */
data class VisibleDetailFields(val fields: Map<String, String>, val amounts: Set<String>,
    val markers: Set<String> = emptySet(),
    val transactionSource: com.qq.closie.life.finance.TransactionSourceHint? = null)
interface TransactionDetailAdapter {
    val requiredLabels: Set<String>
    val fieldLabels: Set<String> get() = requiredLabels
    val pageMarkers: Set<String> get() = emptySet()
    val headlineStatuses: Set<String> get() = emptySet()
    val signedHeadline: Boolean get() = false
    fun hasFingerprint(labels: Set<String>, markers: Set<String>) = labels.containsAll(requiredLabels)
    fun acceptsValue(label: String, value: String): Boolean
    fun normalizeValue(value: String): String = value.trim()
    fun fieldValue(label: String, value: String): String? =
        value.takeIf { acceptsValue(label, it) }?.let(::normalizeValue)
    fun parse(fields: VisibleDetailFields, observedAt: Long): DetailResult
}
