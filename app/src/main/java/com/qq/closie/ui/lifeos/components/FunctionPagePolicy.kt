package com.qq.closie.ui.lifeos.components

import androidx.compose.runtime.*
import com.qq.closie.navigation.LifeOsRoute

/** Shell-owned arbitration; nested editors temporarily lease gesture ownership. */
@Stable
class FunctionPagePolicy {
    /** A navigation intent only. Local Finance regions recognize their own gestures. */
    var requestDrawer: (() -> Unit)? = null
    /** True only when the visible drawer actually cancelled a live reorder. */
    var cancelReorder: (() -> Boolean)? = null
    fun cancelActiveReorder(): Boolean {
        val cancel = cancelReorder ?: return false
        cancelReorder = null
        return cancel()
    }
    /** Leases for opening gestures, not Back ownership of an already-open drawer. */
    var blockers by mutableIntStateOf(0)
        private set
    fun acquire() { blockers++ }
    fun release() { blockers = (blockers - 1).coerceAtLeast(0) }
}
val LocalFunctionPagePolicy = staticCompositionLocalOf<FunctionPagePolicy?> { null }

@Composable
fun SuspendFunctionPageSwipe(active: Boolean = true) {
    val policy = LocalFunctionPagePolicy.current
    DisposableEffect(policy, active) {
        if (active) policy?.acquire()
        onDispose { if (active) policy?.release() }
    }
}

fun supportsFunctionPage(route: String?): Boolean = route in setOf(
    LifeOsRoute.HOME, LifeOsRoute.FINANCE, LifeOsRoute.CLOSET, LifeOsRoute.CALENDAR,
    LifeOsRoute.CAPTURE, LifeOsRoute.REFERENCE, LifeOsRoute.PLAN, LifeOsRoute.READING,
    LifeOsRoute.MODULE, LifeOsRoute.MAP, LifeOsRoute.SETTINGS
)
