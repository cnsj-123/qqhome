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
    container: LifeContainer, command: ExternalNavCommand?, onConsumed: (Long) -> Unit) {
    composable(LifeOsRoute.CLOSET) {
        ClosieTheme {
            ClosieNavHost(repository = repository, externalCommand = command,
                onExternalCommandConsumed = { command?.let { onConsumed(it.nonce) } },
                startDestination = TopLevel.Closet.route,
                onExit = { nav.returnToLifeHome() }, lifeDatabase = container.lifeDatabase)
        }
    }
    composable(LifeOsRoute.BACKUP) {
        ClosieTheme { DataSettingsScreen(repo = repository, lifeDatabase = container.lifeDatabase,
            back = { nav.returnToLifeHome() }) }
    }
}
