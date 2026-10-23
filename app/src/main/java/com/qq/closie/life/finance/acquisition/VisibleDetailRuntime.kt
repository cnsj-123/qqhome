package com.qq.closie.life.finance.acquisition

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Process-local diagnostics only. No raw text, amounts, references, screenshots or disk writes. */
object VisibleDetailRuntime {
    enum class Phase(val label: String) {
        IDLE("尚未读取"), SETTLING("正在等待页面稳定"),
        ROOT_NOT_READY("页面尚未就绪"), WINDOW_CHANGED("页面已切换"),
        TREE_LIMIT("页面层级过大，已停止读取"), AMBIGUOUS_ROWS("同一字段有多个值，需手动核对"),
        LOW_CONFIDENCE("交易字段不足或相互冲突，未生成小票"), NOT_DETAIL("当前页面不是受支持的交易详情"),
        LOCKED("锁屏时已停止读取"), PAGE_UNSUPPORTED("未读到受支持的完整详情"),
        PROPOSAL_READY("已保存待确认小票"), DELIVERY_REQUESTED("正在显示小票"),
        OVERLAY_ATTACHED("小票已显示"), DELIVERY_DEFERRED("待确认已保留，请在账目中查看"),
        STORE_FAILED("本次未能保存，请在账目中检查"), INTERRUPTED("读取已中断")
    }
    data class Stage(val timestamp: Long, val phase: Phase)
    data class State(val connected: Boolean = false, val phase: Phase = Phase.IDLE, val recent: List<Stage> = emptyList())
    private val value = MutableStateFlow(State())
    val state = value.asStateFlow()
    fun connected(connected: Boolean) { value.value = value.value.copy(connected = connected, phase = Phase.IDLE) }
    fun report(phase: Phase) {
        val current = value.value
        if (current.phase == phase) return
        value.value = current.copy(phase = phase, recent = (current.recent + Stage(System.currentTimeMillis(), phase)).takeLast(32))
    }
}
