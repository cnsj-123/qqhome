package com.xiaoming.closie.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.navArgument
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.xiaoming.closie.ExternalNavCommand
import com.xiaoming.closie.data.repository.WardrobeRepository
import com.xiaoming.closie.ui.lifeos.home.LifeOsHomeScreen
import com.xiaoming.closie.ui.lifeos.home.HomeUiState
import com.xiaoming.closie.ui.lifeos.settings.AppearanceViewModel
import java.time.LocalDate
import com.xiaoming.closie.ui.lifeos.calendar.LifeCalendarScreen
import com.xiaoming.closie.ui.lifeos.map.LifeMapScreen
import com.xiaoming.closie.ui.lifeos.chat.CompanionShellScreen
import com.xiaoming.closie.ui.lifeos.modules.ModuleLandingScreen
import com.xiaoming.closie.ui.lifeos.modules.CaptureLandingScreen
import com.xiaoming.closie.ui.lifeos.settings.AppearanceSettingsRoute
import com.xiaoming.closie.ui.settings.DataSettingsScreen
import com.xiaoming.closie.ui.theme.ClosieTheme

@Composable
fun LifeOsNavHost(
    repository: WardrobeRepository,
    externalCommand: ExternalNavCommand?,
    onExternalCommandConsumed: () -> Unit,
    nav: NavHostController,
    home: HomeUiState,
    onHomeDateSelected: (LocalDate) -> Unit,
    appearanceViewModel: AppearanceViewModel,
    onDrawer: () -> Unit
) {
    LaunchedEffect(externalCommand) {
        externalCommand?.let {
            nav.navigate(ExternalCommandResolver.rootDestination(it)) { launchSingleTop = true }
        }
    }
    NavHost(navController = nav, startDestination = LifeOsRoute.DEFAULT) {
        composable(LifeOsRoute.HOME) {
            LifeOsHomeScreen(home, onDrawer,
                onCalendar = { nav.navigate(LifeOsRoute.CALENDAR) },
                onMap = { nav.navigate(LifeOsRoute.MAP) },
                onCompanion = { nav.navigate(LifeOsRoute.COMPANION) },
                onCapture = { nav.navigate(LifeOsRoute.CAPTURE) })
        }
        composable(LifeOsRoute.CLOSET) {
            // Keep the existing light Closet theme while Life OS supports custom dark paper.
            ClosieTheme {
                ClosieNavHost(repository, externalCommand, onExternalCommandConsumed,
                    moduleMode = true, onExitModule = { nav.popBackStack() })
            }
        }
        composable(LifeOsRoute.BACKUP) {
            ClosieTheme { DataSettingsScreen(repository) { nav.popBackStack() } }
        }
        composable(LifeOsRoute.CALENDAR) {
            LifeCalendarScreen(initialDate = home.date, onBack = { nav.popBackStack() }, onOpenHomeDate = {
                onHomeDateSelected(it)
                nav.popBackStack(LifeOsRoute.HOME, inclusive = false)
            })
        }
        composable(LifeOsRoute.MAP) { LifeMapScreen { nav.popBackStack() } }
        composable(LifeOsRoute.COMPANION) { CompanionShellScreen { nav.popBackStack() } }
        composable(LifeOsRoute.CAPTURE) {
            CaptureLandingScreen(onBack = { nav.popBackStack() },
                onOpenExistingCapture = { nav.navigate(LifeOsRoute.BACKUP) })
        }
        composable(LifeOsRoute.APPEARANCE) {
            AppearanceSettingsRoute(appearanceViewModel) { nav.popBackStack() }
        }
        composable(LifeOsRoute.MODULE, arguments = listOf(navArgument("moduleId") { type = NavType.StringType })) { entry ->
            ModuleLandingScreen(entry.arguments?.getString("moduleId").orEmpty()) { nav.popBackStack() }
        }
    }
}
