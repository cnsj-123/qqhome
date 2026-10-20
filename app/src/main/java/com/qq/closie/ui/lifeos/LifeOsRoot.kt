package com.qq.closie.ui.lifeos

import com.qq.closie.life.ui.theme.BundledText as Text

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.material3.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import com.qq.closie.ui.lifeos.components.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.qq.closie.navigation.ExternalNavCommand
import com.qq.closie.data.appearance.AppearanceSettings
import com.qq.closie.data.repository.WardrobeRepository
import com.qq.closie.life.data.LifeContainer
import com.qq.closie.navigation.ExternalCommandResolver
import com.qq.closie.navigation.intake.ExternalIntakeViewModel
import com.qq.closie.ui.lifeos.capture.CaptureMenuHost
import com.qq.closie.navigation.LifeOsNavHost
import com.qq.closie.ui.lifeos.theme.LifeOsTheme
import com.qq.closie.ui.lifeos.settings.AppearanceViewModel
import com.qq.closie.ui.lifeos.theme.LocalLifeOsColors
import com.qq.closie.ui.lifeos.drawer.LifeOsDrawer
import com.qq.closie.ui.lifeos.components.reducedLifeMotion
import com.qq.closie.navigation.navigateLifeTopLevel
import com.qq.closie.navigation.LifeOsRoute
import com.qq.closie.ui.theme.ClosieColor
import kotlinx.coroutines.launch

/** Product root. Closet is a destination inside this shell, with its original storage owner. */
@Composable
fun LifeOsRoot(
    repository: WardrobeRepository,
    lifeContainer: LifeContainer,
    appearance: AppearanceSettings,
    shellViewModel: LifeOsShellViewModel,
    appearanceViewModel: AppearanceViewModel,
    intake: ExternalIntakeViewModel,
    externalCommand: ExternalNavCommand?,
    onExternalCommandConsumed: (Long) -> Unit,
    onDarkAppearance: (Boolean) -> Unit = {}
) {
    LifeOsTheme(appearance) {
        val colors = LocalLifeOsColors.current
        val nav = rememberNavController()
        val entry by nav.currentBackStackEntryAsState()
        val legacySurface = entry?.destination?.route in setOf(LifeOsRoute.CLOSET, LifeOsRoute.BACKUP)
        val home by shellViewModel.home.collectAsStateWithLifecycle()
        var companionProgress by remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
        var drawerOpen by rememberSaveable { mutableStateOf(false) }
        val reduced = reducedLifeMotion()
        val gesturePolicy = remember { FunctionPagePolicy() }
        val scope = rememberCoroutineScope()
        var captureMenu by rememberSaveable { mutableStateOf(false) }
        val intakeNavigation by intake.navigation.collectAsStateWithLifecycle()
        val intakeError by intake.error.collectAsStateWithLifecycle()
        suspend fun closeDrawer() { drawerOpen = false }
        fun openDrawer() { drawerOpen = true }
        LaunchedEffect(entry?.destination?.route) {
            if (entry?.destination?.route != LifeOsRoute.HOME && drawerOpen) closeDrawer()
        }
        LaunchedEffect(externalCommand) {
            externalCommand?.let { command ->
                captureMenu = false
                scope.launch { closeDrawer() }
                if (ExternalCommandResolver.isClosetCommand(command)) {
                    intake.supersede()
                    nav.navigateLifeTopLevel(LifeOsRoute.CLOSET)
                } else intake.accept(command)
            }
        }
        LaunchedEffect(intakeNavigation) {
            intakeNavigation?.let { result ->
                if (result.externalNonce == null || result.externalNonce == externalCommand?.nonce) {
                    nav.navigateLifeTopLevel(result.route)
                    result.externalNonce?.let(onExternalCommandConsumed)
                }
                intake.acknowledge(result)
            }
        }
        SideEffect { onDarkAppearance(colors.isDark && !legacySurface) }
        SideEffect { gesturePolicy.requestDrawer = { drawerOpen = true } }
        CompositionLocalProvider(LocalFunctionPagePolicy provides gesturePolicy) {
        Surface(Modifier.fillMaxSize(), color = if (legacySurface) ClosieColor.Canvas else colors.paper) {
            Box(Modifier.safeDrawingPadding()
                .lifeCompanionSwipe(enabled = supportsFunctionPage(entry?.destination?.route) &&
                    !drawerOpen && gesturePolicy.blockers == 0 && !captureMenu && intakeError == null,
                    onProgress = { companionProgress = it },
                    onOpen = { nav.navigateLifeTopLevel(LifeOsRoute.COMPANION) })) {
                com.qq.closie.ui.lifeos.components.LifeFunctionPages(
                    open = drawerOpen,
                    enabled = supportsFunctionPage(entry?.destination?.route) && gesturePolicy.blockers == 0 && !captureMenu && intakeError == null,
                    horizontalPages = entry?.destination?.route == LifeOsRoute.FINANCE,
                    home = entry?.destination?.route == LifeOsRoute.HOME,
                    effects = appearance.functionEffects, style = appearance.functionPageStyle, reducedMotion = reduced,
                    onOpenChange = { drawerOpen = it },
                    functionPage = {
                        LifeOsDrawer(onOpenModule = { module ->
                            drawerOpen = false
                            if (module.id == "capture") captureMenu = true
                            else nav.navigateLifeTopLevel(module.route)
                        }, onClose = { drawerOpen = false }, active = drawerOpen, moduleOrder = appearance.moduleOrder,
                            onSaveOrder = appearanceViewModel::saveModuleOrder)
                    }
                ) {
                    LifeOsNavHost(repository, lifeContainer, externalCommand, onExternalCommandConsumed, nav, home,
                        shellViewModel::selectDate, appearanceViewModel, ::openDrawer,
                        onCapture = { captureMenu = true }, onFileToLibrary = intake::fileToLibrary)
                }
                if (companionProgress > 0f) Surface(
                    Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp).graphicsLayer {
                        translationY = -32.dp.toPx() * companionProgress
                    }, shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp), color = colors.paperTertiary) {
                    Text(if (companionProgress >= 1f) "松开找伙伴" else "上滑找伙伴", Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        style = com.qq.closie.ui.lifeos.theme.LifeText.caption)
                }
            }
        }
        }
        // Keep registration stable after module content. A closed drawer yields to the
        // module/NavHost, and leased editors/viewers keep their own modal Back priority.
        BackHandler(enabled = drawerOpen && gesturePolicy.blockers == 0 && !captureMenu && intakeError == null) {
            if (!gesturePolicy.cancelActiveReorder()) drawerOpen = false
        }
        // Dialog/sheet hosts retain modal Back priority over the shell callback.
        com.qq.closie.ui.lifeos.finance.FinanceGlobalReviewHost(lifeContainer)
        if (captureMenu) CaptureMenuHost(intake, onDismiss = { captureMenu = false },
            onManual = { nav.navigateLifeTopLevel(LifeOsRoute.capture(LifeOsRoute.NEW_ID)) })
        intakeError?.let { message ->
            AlertDialog(onDismissRequest = intake::clearError, title = { Text("采集未完成") },
                text = { Text(message) }, confirmButton = {
                    TextButton(onClick = {
                        intake.clearError()
                        externalCommand?.takeUnless(ExternalCommandResolver::isClosetCommand)?.let(intake::accept)
                    }) { Text("重试") }
                }, dismissButton = { TextButton(onClick = intake::clearError) { Text("关闭") } })
        }
    }
}
