package com.qq.closie.life.finance.acquisition

import com.qq.closie.life.finance.FinanceCommandToken
import com.qq.closie.life.finance.FinanceOcrFields
import kotlinx.coroutines.delay

internal data class FinanceCapturePage(val packageName: String, val windowId: Int, val orientation: Int, val pageGeneration: Long)
internal data class FinanceCaptureRequest(val page: FinanceCapturePage, val requestGeneration: Long,
    val restoreGeneration: Long, val token: FinanceCommandToken)

/** Shipping capture sequence with injectable platform ports, so races can be exercised at each suspension.
 * Token binding precedes the hide delay and OCR. The caller fixes page/generations at the click.
 */
internal suspend fun <F> runFinanceCapture(
    page: FinanceCapturePage, requestGeneration: Long, restoreGeneration: Long,
    current: () -> Boolean, currentForStorage: () -> Boolean,
    token: suspend () -> FinanceCommandToken, hide: () -> Unit,
    capture: suspend (FinanceCaptureRequest) -> F, recognize: suspend (F) -> FinanceOcrFields,
    releaseUnused: (F) -> Unit,
    store: suspend (FinanceCaptureRequest, FinanceOcrFields, () -> Boolean) -> String,
    present: suspend (FinanceCaptureRequest, String, () -> Boolean) -> Unit
): String {
    check(current()) { "当前页面无法采集，请重新点击" }
    val request = FinanceCaptureRequest(page, requestGeneration, restoreGeneration, token())
    check(current()) { "采集期间页面或恢复状态已改变" }
    hide()
    delay(180) // Only removes our own surfaces; it never chooses a new capture target.
    check(current()) { "页面已切换，截图已取消" }
    val frame = capture(request)
    var handedToRecognizer = false
    val fields = try {
        check(current()) { "页面已切换，截图已放弃" }
        handedToRecognizer = true
        recognize(frame)
    } finally { if (!handedToRecognizer) releaseUnused(frame) }
    check(current()) { "识别期间页面或恢复状态已改变" }
    check(fields.amountMinor != null || fields.occurredAt != null || fields.suggestedKind != null) { "未发现可核对的交易字段" }
    val id = store(request, fields, currentForStorage)
    // Store may succeed just as the user leaves: the candidate survives, presentation must recheck.
    present(request, id, current)
    return id
}
