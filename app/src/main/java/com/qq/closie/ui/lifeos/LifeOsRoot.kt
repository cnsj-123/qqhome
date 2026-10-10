package com.qq.closie.ui.lifeos

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
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
import com.qq.closie.ui.lifeos.components.lifeHorizontalSwipe
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
        var drawerOpen by rememberSaveable { mutableStateOf(false) }
        val reduced = reducedLifeMotion()
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
        Surface(Modifier.fillMaxSize(), color = if (legacySurface) ClosieColor.Canvas else colors.paper) {
            Box(Modifier.safeDrawingPadding().consumeWindowInsets(WindowInsets.safeDrawing)) {
                com.qq.closie.ui.lifeos.components.LifeFunctionPages(
                    open = drawerOpen,
                    enabled = entry?.destination?.route == LifeOsRoute.HOME,
                    style = appearance.functionPageStyle, reducedMotion = reduced,
                    onOpenChange = { drawerOpen = it },
                    functionPage = {
                        LifeOsDrawer(onOpenModule = { module ->
                            drawerOpen = false
                            if (module.id == "capture") captureMenu = true
                            else nav.navigateLifeTopLevel(module.route)
                        }, onClose = { drawerOpen = false })
                    }
                ) {
                    LifeOsNavHost(repository, lifeContainer, externalCommand, onExternalCommandConsumed, nav, home,
                        shellViewModel::selectDate, appearanceViewModel, ::openDrawer,
                        onCapture = { captureMenu = true }, onFileToLibrary = intake::fileToLibrary)
                }
            }
        }
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
