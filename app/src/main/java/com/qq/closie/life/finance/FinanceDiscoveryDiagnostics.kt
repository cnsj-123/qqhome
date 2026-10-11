package com.qq.closie.life.finance

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicLong

enum class DiscoveryResult(val label: String) {
    RECEIVED("已收到，正在识别"), MASTER_OFF("自动发现已关闭"), SOURCE_OFF("此来源已关闭"),
    GROUP_SUMMARY("通知为分组摘要，等待单条支付提示"), NOT_PAYMENT("不是明确的支付通知"),
    UNSUCCESSFUL("未完成付款、验证码或推广提示"), NO_AMOUNT("未找到有效金额"),
    AMBIGUOUS_AMOUNT("有多个金额，需手动填写"), UNKNOWN_DIRECTION("无法确定收入或支出"),
    DUPLICATE("重复通知，已有待确认记录"), STALE("通知已超过 24 小时，未重复发现"),
    SAVE_FAILED("未能保存，请检查应用恢复状态后重试"),
    CREATED("已进入待确认，尚未入账"), MALFORMED("通知格式无法读取")
}

data class DiscoveryTrace(val sequence: Long, val receivedAt: Long, val result: DiscoveryResult,
    val amountMinor: Long? = null, val proposalId: String? = null)
data class DiscoveryDiagnostics(val connected: Boolean = false, val connectionChangedAt: Long? = null,
    val latest: Map<FinanceSource, DiscoveryTrace> = emptyMap(), val received: Int = 0)

/** Process-local, bounded to two allowlisted sources. Never retains titles, bodies or merchant names. */
object FinanceDiscoveryDiagnostics {
    private val sequence = AtomicLong()
    private val mutable = MutableStateFlow(DiscoveryDiagnostics())
    val state = mutable.asStateFlow()
    fun connection(connected: Boolean) { mutable.update { it.copy(connected = connected, connectionChangedAt = System.currentTimeMillis()) } }
    fun received(source: FinanceSource): Long {
        val id = sequence.incrementAndGet()
        mutable.update { it.copy(received = it.received + 1, latest = it.latest +
            (source to DiscoveryTrace(id, System.currentTimeMillis(), DiscoveryResult.RECEIVED))) }
        return id
    }
    fun result(source: FinanceSource, id: Long, result: DiscoveryResult, amount: Long? = null, proposal: String? = null) {
        mutable.update { state ->
            val old = state.latest[source]
            if (old?.sequence != id) state else state.copy(latest = state.latest +
                (source to old.copy(result = result, amountMinor = amount, proposalId = proposal)))
        }
    }
    fun clear() { mutable.update { it.copy(latest = emptyMap(), received = 0) } }
}
