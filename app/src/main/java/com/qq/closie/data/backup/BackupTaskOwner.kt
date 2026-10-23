package com.qq.closie.data.backup

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Application-owned work: removing a settings composition is never a cancellation request. */
internal class BackupTaskOwner(private val scope: CoroutineScope) {
    data class State(val running: Boolean = false, val result: String? = null, val failed: Boolean = false)
    private val mutable = MutableStateFlow(State())
    val state = mutable.asStateFlow()
    @Synchronized fun start(success: String, operation: suspend () -> Result<Unit>): Boolean {
        if (mutable.value.running) return false
        mutable.value = State(running = true)
        scope.launch {
            try {
                val result = operation()
                mutable.value = result.fold({ State(result = success) },
                    { State(result = it.message ?: "恢复未完成，请查看恢复状态", failed = true) })
            } catch (cancelled: CancellationException) {
                mutable.value = State(result = "恢复任务中断，请按恢复状态处理", failed = true)
                throw cancelled
            } catch (error: Exception) {
                mutable.value = State(result = error.message ?: "恢复未完成", failed = true)
            }
        }
        return true
    }
    fun acknowledge() { if (!mutable.value.running) mutable.value = State() }
}
