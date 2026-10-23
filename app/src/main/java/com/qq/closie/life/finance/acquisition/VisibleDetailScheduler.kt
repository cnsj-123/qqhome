package com.qq.closie.life.finance.acquisition

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** One bounded burst per event batch. A busy read retains a trailing event, never a node. */
internal class VisibleDetailScheduler(
    private val scope: CoroutineScope,
    private val read: suspend (Target) -> Boolean
) {
    data class Target(val packageName: String, val windowId: Int)
    @Volatile private var generation = 0L
    @Volatile private var target: Target? = null
    private var pending: Job? = null
    private var dirty = false

    fun isCurrent(value: Target, expectedGeneration: Long = generation) =
        target == value && generation == expectedGeneration

    fun submit(value: Target) {
        if (target != value) {
            cancel()
            target = value
        }
        if (pending?.isActive == true) { dirty = true; return }
        val token = generation
        pending = scope.launch {
            delay(300)
            // At most three fresh roots; no permanent polling of a static page.
            for (attempt in 0..2) {
                if (!isCurrent(value, token)) return@launch
                dirty = false
                val complete = read(value)
                if (complete && !dirty) return@launch
                if (attempt < 2) delay(if (complete) 300 else 800)
            }
        }
    }

    fun cancel() {
        generation++
        pending?.cancel()
        pending = null
        target = null
        dirty = false
    }
}
