package com.qq.closie.navigation

import androidx.navigation.NavHostController

/** Top-level modules share one stable Home base; module detail routes use normal push/pop. */
fun NavHostController.navigateLifeTopLevel(route: String) {
    if (route == LifeOsRoute.HOME) {
        returnToLifeHome()
        return
    }
    // Also repairs an externally restored stack that has lost its Home entry.
    ensureLifeHome()
    navigate(route) {
        popUpTo(LifeOsRoute.HOME) { inclusive = false }
        launchSingleTop = true
    }
}

fun NavHostController.returnToLifeHome() {
    if (currentDestination?.route == LifeOsRoute.HOME) return
    if (!popBackStack(LifeOsRoute.HOME, inclusive = false)) ensureLifeHome()
}

/** For detail/editor exits only. A missing parent must never leave an empty host. */
fun NavHostController.returnWithinLifeModule() {
    if (currentDestination?.route == LifeOsRoute.HOME) return
    if (!popBackStack()) returnToLifeHome()
}

private fun NavHostController.ensureLifeHome() {
    val hasHome = try {
        getBackStackEntry(LifeOsRoute.HOME)
        true
    } catch (_: IllegalArgumentException) {
        false
    }
    if (!hasHome) navigate(LifeOsRoute.HOME) {
        popUpTo(graph.id) { inclusive = false }
        launchSingleTop = true
    }
}
