@file:OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)

package com.qq.closie.navigation

import androidx.compose.runtime.Composable
import androidx.compose.animation.SharedTransitionLayout
import com.qq.closie.ui.lifeos.settings.SettingsMotionScopeProvider
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.TransformOrigin
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
    onCapture: () -> Unit, onFileToLibrary: (String) -> Unit, onRemoveHomePhoto: (String) -> Unit = {},
    onSavePhotoCaption: (suspend (String, String, String?, Long) -> Unit)? = null) {
    val reducedMotion = com.qq.closie.ui.lifeos.components.reducedLifeMotion()
    val paperOrigin = TransformOrigin(.5f, 0f)
    SharedTransitionLayout {
    val settingsSharedScope=this
    NavHost(navController = nav, startDestination = LifeOsRoute.DEFAULT, route = LifeOsRoute.GRAPH,
        enterTransition = {
            when {
                reducedMotion || isFastLifeModuleSwitch(initialState.destination.route, targetState.destination.route) -> EnterTransition.None
                initialState.destination.route in settingsHubs && targetState.destination.route in settingsPaperRoutes ->
                    fadeIn(tween(500))
                else -> fadeIn(tween(140))
            }
        },
        exitTransition = {
            if (reducedMotion || isFastLifeModuleSwitch(initialState.destination.route, targetState.destination.route)) ExitTransition.None
            else fadeOut(tween(140))
        },
        popEnterTransition = {
            if (reducedMotion || isFastLifeModuleSwitch(initialState.destination.route, targetState.destination.route)) EnterTransition.None
            else fadeIn(tween(140))
        },
        popExitTransition = {
            when {
                reducedMotion || isFastLifeModuleSwitch(initialState.destination.route, targetState.destination.route) -> ExitTransition.None
                initialState.destination.route in settingsPaperRoutes && targetState.destination.route in settingsHubs ->
                    fadeOut(tween(250))
                else -> fadeOut(tween(140))
            }
        }) {
        composable(LifeOsRoute.HOME) {
            LifeOsHomeScreen(home, onDrawer, onCalendar = { nav.navigateLifeTopLevel(LifeOsRoute.CALENDAR) },
                onMap = { nav.navigateLifeTopLevel(LifeOsRoute.MAP) }, onCompanion = { nav.navigateLifeTopLevel(LifeOsRoute.COMPANION) },
                onCapture = onCapture, onToday = { onHomeDateSelected(LocalDate.now()) }, onDateSelected = onHomeDateSelected,
                onRemovePhoto = onRemoveHomePhoto, onSavePhotoCaption = onSavePhotoCaption)
        }
        closetRoutes(nav, repository, container, externalCommand, onExternalCommandConsumed, settingsSharedScope)
        captureRoutes(nav, container, onCapture, onFileToLibrary)
        referenceRoutes(nav, container, onCapture)
        planRoutes(nav, container)
        composable(LifeOsRoute.FINANCE) {
            FinanceRoute(container.financeRepository, container.financeImportRepository, container.financeAutomationRepository,
                onFinanceSettings = { nav.navigate(LifeOsRoute.FINANCE_SETTINGS) }) { nav.returnToLifeHome() }
        }
        composable(LifeOsRoute.CALENDAR) {
            LifeCalendarScreen(initialDate = home.date, state = com.qq.closie.ui.lifeos.calendar.CalendarUiState(java.time.YearMonth.from(home.date), home.calendarDays), onBack = { nav.returnToLifeHome() }, onOpenHomeDate = {
                onHomeDateSelected(it)
                nav.returnToLifeHome()
            })
        }
        composable(LifeOsRoute.MAP) { LifeMapScreen { nav.returnToLifeHome() } }
        composable(LifeOsRoute.COMPANION) { CompanionShellScreen { nav.returnToLifeHome() } }
        composable(LifeOsRoute.APPEARANCE) {
            SettingsMotionScopeProvider(settingsSharedScope,this) { AppearanceSettingsRoute(appearanceViewModel) { nav.returnWithinLifeModule() }
            }}
        composable(LifeOsRoute.FINANCE_AUTOMATION) {
            SettingsMotionScopeProvider(settingsSharedScope,this) {
            com.qq.closie.ui.lifeos.finance.FinanceAutomationScreen(container.financeAutomationRepository, container.financeRepository) { nav.returnWithinLifeModule() }

            }}
        composable(LifeOsRoute.FINANCE_REVIEW) {
            SettingsMotionScopeProvider(settingsSharedScope,this) {
            com.qq.closie.ui.lifeos.finance.FinanceAutomationScreen(container.financeAutomationRepository,
                container.financeRepository, com.qq.closie.ui.lifeos.finance.FinanceAutomationSection.REVIEW) { nav.returnWithinLifeModule() }

            }}
        composable(LifeOsRoute.SETTINGS_PRIVACY) {
            SettingsMotionScopeProvider(settingsSharedScope,this) {
            com.qq.closie.ui.lifeos.settings.SettingsPrivacyScreen(container.financeRepository) { nav.returnWithinLifeModule() }

            }}
        composable(LifeOsRoute.FINANCE_SETTINGS) {
            SettingsMotionScopeProvider(settingsSharedScope,this) {
            com.qq.closie.ui.lifeos.finance.FinanceSettingsRoute(container.financeRepository, container.financeImportRepository,
                onDiscovery = { nav.navigate(LifeOsRoute.FINANCE_AUTOMATION) },
                onReview = { nav.navigate(LifeOsRoute.FINANCE_REVIEW) },
                onPrivacy = { nav.navigate(LifeOsRoute.SETTINGS_PRIVACY) },
                onBackup = { nav.navigate(LifeOsRoute.BACKUP) },
                onBack = { nav.returnWithinLifeModule() })

            }}
        composable(LifeOsRoute.SETTINGS) {
            SettingsMotionScopeProvider(settingsSharedScope,this) {
            LifeSettingsScreen(appearanceViewModel, container.financeAutomationRepository,
                onBack = { nav.returnToLifeHome() }, onAppearance = { nav.navigate(LifeOsRoute.APPEARANCE) },
                onBackup = { nav.navigate(LifeOsRoute.BACKUP) },
                onFinanceAutomation = { nav.navigate(LifeOsRoute.FINANCE_AUTOMATION) },
                onReviewInbox = { nav.navigate(LifeOsRoute.FINANCE_REVIEW) },
                onFinanceSettings = { nav.navigate(LifeOsRoute.FINANCE_SETTINGS) },
                onPrivacy = { nav.navigate(LifeOsRoute.SETTINGS_PRIVACY) },
                onExport = { nav.navigate(LifeOsRoute.SETTINGS_EXPORT) })

            }}
        composable(LifeOsRoute.MODULE, arguments = listOf(navArgument("moduleId") { type = NavType.StringType })) { entry ->
            ModuleLandingScreen(entry.arguments?.getString("moduleId").orEmpty()) { nav.returnToLifeHome() }
        }
    }
}
}

private val settingsHubs = setOf(LifeOsRoute.SETTINGS, LifeOsRoute.FINANCE_SETTINGS)
private val settingsPaperRoutes = setOf(LifeOsRoute.APPEARANCE, LifeOsRoute.FINANCE_SETTINGS,
    LifeOsRoute.FINANCE_AUTOMATION, LifeOsRoute.FINANCE_REVIEW, LifeOsRoute.BACKUP,
    LifeOsRoute.SETTINGS_EXPORT, LifeOsRoute.SETTINGS_PRIVACY)

private val fastLifeModuleRoutes = setOf(LifeOsRoute.HOME, LifeOsRoute.FINANCE, LifeOsRoute.CLOSET,
    LifeOsRoute.CAPTURE, LifeOsRoute.REFERENCE, LifeOsRoute.PLAN, LifeOsRoute.READING,
    LifeOsRoute.CALENDAR, LifeOsRoute.MAP, LifeOsRoute.COMPANION, LifeOsRoute.MODULE)

internal fun isFastLifeModuleSwitch(from: String?, to: String?): Boolean =
    from != null && to != null && from in fastLifeModuleRoutes && to in fastLifeModuleRoutes
