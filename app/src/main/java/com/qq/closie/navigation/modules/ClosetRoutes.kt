@file:OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)

package com.qq.closie.navigation.modules

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import com.qq.closie.data.repository.WardrobeRepository
import com.qq.closie.life.data.LifeContainer
import com.qq.closie.navigation.*
import com.qq.closie.ui.settings.DataSettingsScreen
import com.qq.closie.ui.theme.ClosieTheme

fun NavGraphBuilder.closetRoutes(nav: NavHostController, repository: WardrobeRepository,
    container: LifeContainer, command: ExternalNavCommand?, onConsumed: (Long) -> Unit,
    sharedScope: androidx.compose.animation.SharedTransitionScope? = null) {
    composable(LifeOsRoute.CLOSET) {
        ClosieTheme {
            ClosieNavHost(repository = repository, externalCommand = command,
                onExternalCommandConsumed = { command?.let { onConsumed(it.nonce) } },
                startDestination = TopLevel.Closet.route,
                onExit = { nav.returnToLifeHome() }, lifeDatabase = container.lifeDatabase)
        }
    }
    composable(LifeOsRoute.BACKUP) {
        val body:@androidx.compose.runtime.Composable ()->Unit = { DataSettingsScreen(repo = repository, lifeDatabase = container.lifeDatabase,
            back = { nav.returnWithinLifeModule() }, finance = container.financeRepository) }
        if(sharedScope==null) body() else com.qq.closie.ui.lifeos.settings.SettingsMotionScopeProvider(sharedScope,this,body)
    }
    composable(LifeOsRoute.SETTINGS_EXPORT) {
        val body:@androidx.compose.runtime.Composable ()->Unit = { DataSettingsScreen(repo = repository, lifeDatabase = container.lifeDatabase,
            back = { nav.returnWithinLifeModule() }, finance = container.financeRepository, openExportCenter = true) }
        if(sharedScope==null) body() else com.qq.closie.ui.lifeos.settings.SettingsMotionScopeProvider(sharedScope,this,body)
    }
}
