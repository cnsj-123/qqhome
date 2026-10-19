package com.qq.closie.ui.lifeos.finance

enum class FinanceCalendarStop(val progress:Float) { MONTH(0f),WEEK(.5f),COLLAPSED(1f) }
internal fun financeCalendarRelease(progress:Float,velocity:Float):FinanceCalendarStop {
    val value=progress.coerceIn(0f,1f)
    return when {
        velocity < -300f -> FinanceCalendarStop.entries.firstOrNull { it.progress>value+.02f } ?: FinanceCalendarStop.COLLAPSED
        velocity > 300f -> FinanceCalendarStop.entries.lastOrNull { it.progress<value-.02f } ?: FinanceCalendarStop.MONTH
        else -> FinanceCalendarStop.entries.minBy { kotlin.math.abs(it.progress-value) }
    }
}
