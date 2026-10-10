package com.qq.closie.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.navArgument
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.qq.closie.data.repository.WardrobeRepository
import com.qq.closie.life.data.LifeContainer
import com.qq.closie.navigation.modules.*
import com.qq.closie.ui.lifeos.home.LifeOsHomeScreen
import com.qq.closie.ui.lifeos.finance.FinanceRoute
import com.qq.closie.ui.lifeos.home.HomeUiState
import com.qq.closie.ui.lifeos.settings.AppearanceViewModel
import com.qq.closie.ui.lifeos.calendar.LifeCalendarScreen
import com.qq.closie.ui.lifeos.map.LifeMapScreen
import com.qq.closie.ui.lifeos.chat.CompanionShellScreen
import com.qq.closie.ui.lifeos.modules.ModuleLandingScreen
import com.qq.closie.ui.lifeos.settings.AppearanceSettingsRoute
import com.qq.closie.ui.lifeos.settings.LifeSettingsScreen
import java.time.LocalDate

/** Product destinations and module composition only; no intake writes or database queries. */
@Composable
fun LifeOsNavHost(repository: WardrobeRepository, container: LifeContainer,
    externalCommand: ExternalNavCommand?, onExternalCommandConsumed: (Long) -> Unit,
    nav: NavHostController, home: HomeUiState, onHomeDateSelected: (LocalDate) -> Unit,
    appearanceViewModel: AppearanceViewModel, onDrawer: () -> Unit,
    onCapture: () -> Unit, onFileToLibrary: (String) -> Unit) {
    NavHost(navController = nav, startDestination = LifeOsRoute.DEFAULT, route = LifeOsRoute.GRAPH) {
        composable(LifeOsRoute.HOME) {
            LifeOsHomeScreen(home, onDrawer, onCalendar = { nav.navigateLifeTopLevel(LifeOsRoute.CALENDAR) },
                onMap = { nav.navigateLifeTopLevel(LifeOsRoute.MAP) }, onCompanion = { nav.navigateLifeTopLevel(LifeOsRoute.COMPANION) },
                onCapture = onCapture)
        }
        closetRoutes(nav, repository, container, externalCommand, onExternalCommandConsumed)
        captureRoutes(nav, container, onCapture, onFileToLibrary)
        referenceRoutes(nav, container, onCapture)
        planRoutes(nav, container)
        composable(LifeOsRoute.FINANCE) {
            FinanceRoute(container.financeRepository, container.financeImportRepository, container.financeAutomationRepository) { nav.returnToLifeHome() }
        }
        composable(LifeOsRoute.CALENDAR) {
            LifeCalendarScreen(initialDate = home.date, onBack = { nav.returnToLifeHome() }, onOpenHomeDate = {
                onHomeDateSelected(it)
                nav.returnToLifeHome()
            })
        }
        composable(LifeOsRoute.MAP) { LifeMapScreen { nav.returnToLifeHome() } }
        composable(LifeOsRoute.COMPANION) { CompanionShellScreen { nav.returnToLifeHome() } }
        composable(LifeOsRoute.APPEARANCE) { AppearanceSettingsRoute(appearanceViewModel) { nav.returnWithinLifeModule() } }
        composable(LifeOsRoute.SETTINGS) {
            LifeSettingsScreen(onBack = { nav.returnToLifeHome() }, onAppearance = { nav.navigate(LifeOsRoute.APPEARANCE) },
                onBackup = { nav.navigateLifeTopLevel(LifeOsRoute.BACKUP) })
        }
        composable(LifeOsRoute.MODULE, arguments = listOf(navArgument("moduleId") { type = NavType.StringType })) { entry ->
            ModuleLandingScreen(entry.arguments?.getString("moduleId").orEmpty()) { nav.returnToLifeHome() }
        }
    }
}
